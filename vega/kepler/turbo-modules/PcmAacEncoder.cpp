#include "PcmAacEncoder.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>

extern "C" {
#include <libavcodec/avcodec.h>
}

namespace {
constexpr size_t StereoFrameBytes = 4;
constexpr int SampleRate = 44100;
}

struct PcmAacEncoder::Impl {
  Output output;
  AVCodecContext* encoder = nullptr;
  AVFrame* frame = nullptr;
  AVPacket* packet = nullptr;
  int buffered = 0;
  int64_t firstPts = -1, nextInputPts = -1;
  bool ended = false, failed = false;

  ~Impl() {
    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&encoder);
  }

  void receive() {
    for (;;) {
      const int result = avcodec_receive_packet(encoder,packet);
      if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
      if (result < 0 || packet->pts == AV_NOPTS_VALUE || packet->size <= 0 || packet->size > 65536)
        throw std::runtime_error("Invalid encoded AAC packet");
      // The encoder emits one priming packet before the first PCM timestamp.
      // Dropping it keeps the container start at the first capture timestamp.
      if (packet->pts >= firstPts) {
        if (packet->duration != FrameSamples) throw std::runtime_error("Unexpected AAC packet duration");
        output(std::vector<uint8_t>(packet->data,packet->data+packet->size),packet->pts);
      }
      av_packet_unref(packet);
    }
  }

  void submit() {
    if (avcodec_send_frame(encoder,frame) < 0)
      throw std::runtime_error("Could not submit PCM to AAC encoder");
    buffered = 0;
    receive();
  }

  void appendPcm(const uint8_t* pcm, int count, int64_t pts) {
    int consumed = 0;
    while (consumed < count) {
      if (!buffered) {
        if (av_frame_make_writable(frame) < 0) throw std::bad_alloc();
        frame->pts = pts+consumed;
      }
      const int take = std::min(count-consumed,FrameSamples-buffered);
      for (int channel = 0; channel < 2; ++channel) {
        auto* plane = reinterpret_cast<float*>(frame->extended_data[channel]);
        for (int index = 0; index < take; ++index) {
          int16_t value = 0;
          if (pcm) std::memcpy(&value,pcm+((consumed+index)*2+channel)*sizeof(value),sizeof(value));
          plane[buffered+index] = static_cast<float>(value)/32768.0f;
        }
      }
      consumed += take;
      buffered += take;
      if (buffered == FrameSamples) submit();
    }
  }
};

PcmAacEncoder::PcmAacEncoder(Output output) : impl(std::make_unique<Impl>()) {
  if (!output) throw std::invalid_argument("Missing AAC packet output");
  impl->output = std::move(output);
  const auto* codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
  if (!codec) throw std::runtime_error("AAC encoder is unavailable");
  impl->encoder = avcodec_alloc_context3(codec);
  impl->frame = av_frame_alloc();
  impl->packet = av_packet_alloc();
  if (!impl->encoder || !impl->frame || !impl->packet) throw std::bad_alloc();
  auto& encoder = *impl->encoder;
  encoder.sample_fmt = AV_SAMPLE_FMT_FLTP;
  encoder.sample_rate = SampleRate;
  encoder.time_base = {1,SampleRate};
  encoder.profile = AV_PROFILE_AAC_LOW;
  encoder.bit_rate = 128000;
  encoder.thread_count = 1;
  av_channel_layout_default(&encoder.ch_layout,2);
  if (avcodec_open2(&encoder,codec,nullptr) < 0 ||
      encoder.frame_size != FrameSamples || encoder.initial_padding != FrameSamples) {
    throw std::runtime_error("Could not initialize the AAC-LC mirror encoder");
  }
  auto& frame = *impl->frame;
  frame.format = encoder.sample_fmt;
  frame.nb_samples = encoder.frame_size;
  frame.sample_rate = encoder.sample_rate;
  if (av_channel_layout_copy(&frame.ch_layout,&encoder.ch_layout) < 0 ||
      av_frame_get_buffer(&frame,0) < 0) throw std::bad_alloc();
}

PcmAacEncoder::~PcmAacEncoder() = default;

void PcmAacEncoder::append(const uint8_t* pcm, size_t bytes, int64_t pts) {
  if (!pcm || !bytes || bytes > 128 * 1024 || bytes % StereoFrameBytes)
    throw std::invalid_argument("Invalid stereo PCM packet");
  const auto samples = static_cast<int>(bytes / StereoFrameBytes);
  if (impl->ended || impl->failed) throw std::logic_error("AAC encoder is closed");
  if (pts < 0 || pts < impl->nextInputPts || pts > std::numeric_limits<int64_t>::max()-samples-FrameSamples)
    throw std::invalid_argument("Invalid PCM input timestamp");
  try {
    if (impl->firstPts < 0) impl->firstPts = pts;
    if (impl->buffered && pts > impl->nextInputPts) {
      // Fill at most the unfinished AAC frame. Leave the remaining gap on
      // the timeline instead of allocating silence for a long pause.
      const auto missing = std::min<int64_t>(pts-impl->nextInputPts,FrameSamples-impl->buffered);
      impl->appendPcm(nullptr,static_cast<int>(missing),impl->nextInputPts);
    }
    impl->appendPcm(pcm,samples,pts);
    impl->nextInputPts = pts+samples;
  } catch (...) {
    impl->failed = true;
    throw;
  }
}

void PcmAacEncoder::finish() {
  if (impl->ended) return;
  if (impl->failed) throw std::logic_error("AAC encoder failed before finalization");
  try {
    if (impl->buffered) impl->appendPcm(nullptr,FrameSamples-impl->buffered,impl->nextInputPts);
    if (impl->firstPts >= 0) {
      if (avcodec_send_frame(impl->encoder,nullptr) < 0)
        throw std::runtime_error("Could not drain the AAC encoder");
      impl->receive();
    }
    impl->ended = true;
  } catch (...) {
    impl->failed = true;
    throw;
  }
}
