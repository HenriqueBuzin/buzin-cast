#include "AacPcmDecoder.h"
#include "AirPlayAudioFormat.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
}

struct AacPcmDecoder::Impl {
  AVCodecContext* codec = nullptr;
  AVPacket* packet = nullptr;
  AVFrame* frame = nullptr;
  ~Impl() {av_frame_free(&frame); av_packet_free(&packet); avcodec_free_context(&codec);}
};

AacPcmDecoder::AacPcmDecoder(int samples) : impl(std::make_unique<Impl>()) {
  const auto config = airPlayAacConfig(samples);
  if (config.empty()) throw std::invalid_argument("Unsupported relay AAC profile");
  const auto* decoder = avcodec_find_decoder(AV_CODEC_ID_AAC);
  if (!decoder) throw std::runtime_error("AAC decoder unavailable");
  impl->codec = avcodec_alloc_context3(decoder);
  impl->packet = av_packet_alloc();
  impl->frame = av_frame_alloc();
  if (!impl->codec || !impl->packet || !impl->frame) throw std::bad_alloc();
  auto& codec = *impl->codec;
  codec.extradata = static_cast<uint8_t*>(av_mallocz(config.size() + AV_INPUT_BUFFER_PADDING_SIZE));
  if (!codec.extradata) throw std::bad_alloc();
  std::memcpy(codec.extradata, config.data(), config.size());
  codec.extradata_size = static_cast<int>(config.size());
  // The raw ELD decoder reports the configured sample rate on each frame.
  codec.sample_rate = 44100;
  av_channel_layout_default(&codec.ch_layout, 2);
  codec.thread_count = 1;
  codec.err_recognition = AV_EF_EXPLODE;
  if (avcodec_open2(&codec, decoder, nullptr) < 0)
    throw std::runtime_error("Could not initialize relay AAC decoder");
}

AacPcmDecoder::~AacPcmDecoder() = default;

std::vector<uint8_t> AacPcmDecoder::decode(const std::vector<uint8_t>& bytes) {
  if (bytes.empty() || bytes.size() > 65536) throw std::invalid_argument("Invalid relay AAC packet");
  av_packet_unref(impl->packet);
  if (av_new_packet(impl->packet, static_cast<int>(bytes.size())) < 0) throw std::bad_alloc();
  std::memcpy(impl->packet->data, bytes.data(), bytes.size());
  if (avcodec_send_packet(impl->codec, impl->packet) < 0) throw std::runtime_error("Invalid relay AAC packet");
  std::vector<uint8_t> pcm;
  for (;;) {
    const int result = avcodec_receive_frame(impl->codec, impl->frame);
    if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return pcm;
    if (result < 0) throw std::runtime_error("Could not decode relay AAC");
    const auto& frame = *impl->frame;
    const auto format = static_cast<AVSampleFormat>(frame.format);
    const bool planar = av_sample_fmt_is_planar(format) != 0;
    const auto packed = av_get_packed_sample_fmt(format);
    if ((packed != AV_SAMPLE_FMT_S16 && packed != AV_SAMPLE_FMT_FLT) || frame.sample_rate != 44100 ||
        frame.ch_layout.nb_channels != 2 || frame.nb_samples < 1 || frame.nb_samples > 4096 ||
        pcm.size() + static_cast<size_t>(frame.nb_samples) * 4 > 128 * 1024) {
      throw std::runtime_error("Unsupported decoded relay AAC format");
    }
    pcm.reserve(pcm.size() + static_cast<size_t>(frame.nb_samples) * 4);
    for (int sample = 0; sample < frame.nb_samples; ++sample) {
      for (int channel = 0; channel < 2; ++channel) {
        const auto index = planar ? sample : 2 * sample + channel;
        const auto* plane = frame.extended_data[planar ? channel : 0];
        int16_t value = 0;
        if (packed == AV_SAMPLE_FMT_S16) {
          std::memcpy(&value, plane + index * sizeof(value), sizeof(value));
        } else {
          float input = 0;
          std::memcpy(&input, plane + index * sizeof(input), sizeof(input));
          if (std::isfinite(input)) {
            const auto scaled = std::lround(std::clamp(input, -1.0f, 1.0f) * 32768.0f);
            value = static_cast<int16_t>(std::clamp(scaled, -32768L, 32767L));
          }
        }
        const auto word = static_cast<uint16_t>(value);
        pcm.push_back(static_cast<uint8_t>(word));
        pcm.push_back(static_cast<uint8_t>(word >> 8));
      }
    }
  }
}
