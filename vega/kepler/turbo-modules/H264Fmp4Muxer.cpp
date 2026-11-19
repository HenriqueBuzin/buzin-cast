#include "H264Fmp4Muxer.h"
#include "H264AnnexB.h"
#include <cstring>
#include <exception>
#include <limits>
#include <stdexcept>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
}
using BuzinH264::split;
struct H264Fmp4Muxer::Impl {
  AVFormatContext* context = nullptr;
  AVIOContext* io = nullptr;
  Output output;
  std::exception_ptr outputError;
  int64_t lastDts = -1;
  int64_t lastAudioDts = -1;
  int64_t expectedVideoDts = 0;
  int aacSamplesPerFrame = 0;
  bool firstFragmentWritten = false;
  bool started = false, ended = false, failed = false;
  std::vector<uint8_t> sps, pps;
  ~Impl() {
    avformat_free_context(context);
    if (io) {av_freep(&io->buffer); avio_context_free(&io);}
  }
  static int write(void* opaque, const uint8_t* bytes, int size) noexcept {
    auto& self = *static_cast<Impl*>(opaque);
    try {
      if (size <= 0 || size > 2*1024*1024 || self.outputError) return AVERROR_EXTERNAL;
      self.output(bytes,static_cast<size_t>(size)); return size;
    } catch (...) {self.outputError = std::current_exception(); return AVERROR_EXTERNAL;}
  }
  void check(int result) {
    if (outputError) {failed = true; std::rethrow_exception(outputError);}
    if (result < 0 || (io && io->error < 0)) {failed = true; throw std::runtime_error("Native MP4 mux failed");}
  }
  void writePacket(const std::vector<uint8_t>& bytes, int stream, int64_t pts,
                   int64_t dts, int64_t duration, bool keyframe) {
    if (aacSamplesPerFrame && firstFragmentWritten) {
      const auto expected = stream == 0 ? expectedVideoDts : lastAudioDts + aacSamplesPerFrame;
      if (dts < expected || dts - expected >= std::numeric_limits<int>::max()) {
        throw std::invalid_argument("MP4 track timestamps overlap or exceed the fragment gap limit");
      }
      if (dts != expected) {
        // The MOV muxer otherwise moves each new fragment to the end of
        // its previous sample, which hides gaps and changes A/V alignment.
        check(av_opt_set(context->priv_data, "movflags", "+frag_discont", 0));
      }
    }
    std::unique_ptr<AVPacket, void(*)(AVPacket*)> packet(av_packet_alloc(), [](AVPacket* value) {
      av_packet_free(&value);
    });
    if (!packet || av_new_packet(packet.get(), static_cast<int>(bytes.size())) < 0) {
      throw std::bad_alloc();
    }
    std::memcpy(packet->data, bytes.data(), bytes.size());
    packet->pts = pts;
    packet->dts = dts;
    packet->duration = duration;
    packet->stream_index = stream;
    if (keyframe) packet->flags |= AV_PKT_FLAG_KEY;
    check(av_write_frame(context, packet.get()));
  }
  void flushFragment() {
    if (aacSamplesPerFrame && (!started || lastAudioDts < 0)) return;
    if (aacSamplesPerFrame && !firstFragmentWritten) {
      // delay_moov writes the initialization segment on the first flush.
      // It needs both initial timestamps to write the tracks' edit lists.
      check(av_write_frame(context, nullptr));
    }
    check(av_write_frame(context, nullptr));
    avio_flush(io);
    check(0);
    firstFragmentWritten = true;
  }
};
H264Fmp4Muxer::H264Fmp4Muxer(int width, int height, const std::vector<uint8_t>& parameterSets,
                             Output output, int aacSamplesPerFrame)
  : impl(std::make_unique<Impl>()) {
  if (width < 16 || width > 3840 || height < 16 || height > 2160 || !output || parameterSets.size() > 65536)
    throw std::invalid_argument("Invalid video mux configuration");
  if (aacSamplesPerFrame != 0 && aacSamplesPerFrame != 960 && aacSamplesPerFrame != 1024)
    throw std::invalid_argument("Expected 960 or 1024 samples for AAC-LC");
  impl->aacSamplesPerFrame = aacSamplesPerFrame;
  for (const auto& nal : split(parameterSets)) {
    auto* target = nal.type == 7 ? &impl->sps : nal.type == 8 ? &impl->pps : nullptr;
    if (!target || !target->empty()) throw std::invalid_argument("Expected one SPS and one PPS");
    target->assign(nal.bytes,nal.bytes+nal.size);
  }
  if (impl->sps.size() < 4 || impl->pps.empty()) throw std::invalid_argument("Missing H.264 parameter sets");
  // The pinned MOV muxer derives avcC (including High-profile extensions)
  // from Annex B SPS/PPS and converts each Annex B access unit itself.
  std::vector<uint8_t> config{0,0,0,1};
  config.insert(config.end(),impl->sps.begin(),impl->sps.end());
  config.insert(config.end(),{0,0,0,1}); config.insert(config.end(),impl->pps.begin(),impl->pps.end());
  impl->output = std::move(output);
  if (avformat_alloc_output_context2(&impl->context,nullptr,"mp4",nullptr) < 0 || !impl->context) throw std::runtime_error("Missing MP4 muxer");
  auto* buffer = static_cast<uint8_t*>(av_malloc(4096));
  if (!buffer) throw std::bad_alloc();
  impl->io = avio_alloc_context(buffer,4096,1,impl.get(),nullptr,Impl::write,nullptr);
  if (!impl->io) {av_free(buffer); throw std::bad_alloc();}
  impl->context->pb = impl->io; impl->context->flags |= AVFMT_FLAG_CUSTOM_IO;
  auto* stream = avformat_new_stream(impl->context,nullptr);
  if (!stream) throw std::bad_alloc();
  stream->time_base = AVRational{1,1000000};
  auto* codec = stream->codecpar;
  codec->codec_type = AVMEDIA_TYPE_VIDEO; codec->codec_id = AV_CODEC_ID_H264;
  codec->width = width; codec->height = height;
  codec->extradata = static_cast<uint8_t*>(av_mallocz(config.size()+AV_INPUT_BUFFER_PADDING_SIZE));
  if (!codec->extradata) throw std::bad_alloc();
  std::memcpy(codec->extradata,config.data(),config.size()); codec->extradata_size = static_cast<int>(config.size());
  if (aacSamplesPerFrame) {
    auto* audio = avformat_new_stream(impl->context, nullptr);
    if (!audio) throw std::bad_alloc();
    audio->time_base = AVRational{1, 44100};
    auto* audioCodec = audio->codecpar;
    audioCodec->codec_type = AVMEDIA_TYPE_AUDIO;
    audioCodec->codec_id = AV_CODEC_ID_AAC;
    audioCodec->sample_rate = 44100;
    audioCodec->frame_size = aacSamplesPerFrame;
    av_channel_layout_default(&audioCodec->ch_layout, 2);
    audioCodec->extradata = static_cast<uint8_t*>(av_mallocz(2 + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!audioCodec->extradata) throw std::bad_alloc();
    audioCodec->extradata_size = 2;
    audioCodec->extradata[0] = 0x12;
    audioCodec->extradata[1] = aacSamplesPerFrame == 960 ? 0x14 : 0x10;
    impl->context->avoid_negative_ts = AVFMT_AVOID_NEG_TS_DISABLED;
  }
  AVDictionary* options = nullptr;
  av_dict_set(&options, "movflags", aacSamplesPerFrame
    ? "frag_custom+delay_moov+default_base_moof+skip_trailer+skip_sidx"
    : "frag_custom+empty_moov+default_base_moof+skip_trailer+skip_sidx", 0);
  if (aacSamplesPerFrame) {
    av_dict_set(&options, "use_editlist", "1", 0);
    av_dict_set(&options, "movie_timescale", "1000000", 0);
  }
  av_dict_set(&options,"video_track_timescale","1000000",0);
  const int result = avformat_write_header(impl->context,&options);
  av_dict_free(&options); avio_flush(impl->io); impl->check(result);
}
H264Fmp4Muxer::~H264Fmp4Muxer() = default;
void H264Fmp4Muxer::append(const std::vector<uint8_t>& bytes, int64_t pts, int64_t dts, int64_t duration) {
  if (impl->ended || impl->failed) throw std::runtime_error("Video mux is closed");
  if (impl->aacSamplesPerFrame && impl->started && impl->lastAudioDts < 0) {
    throw std::logic_error("Supply the first AAC frame before more video");
  }
  if (pts < 0 || dts < 0 || pts < dts || dts <= impl->lastDts || duration <= 0 || duration > 1000000 ||
      pts > std::numeric_limits<int64_t>::max()-duration || dts > std::numeric_limits<int64_t>::max()-duration)
    throw std::invalid_argument("Invalid H.264 stream timestamps");
  bool keyframe = false, vcl = false;
  for (const auto& nal : split(bytes)) {
    if (nal.type == 7 || nal.type == 8) {
      const auto& expected = nal.type == 7 ? impl->sps : impl->pps;
      if (expected.size() != nal.size || std::memcmp(expected.data(),nal.bytes,nal.size))
        throw std::invalid_argument("H.264 format change requires a new mux session");
    }
    keyframe = keyframe || nal.type == 5; vcl = vcl || nal.type == 1 || nal.type == 5;

  }
  if (!vcl || (!impl->started && !keyframe)) throw std::invalid_argument("Video mux requires an initial IDR access unit");
  impl->writePacket(bytes, 0, pts, dts, duration, keyframe);
  impl->expectedVideoDts = dts + duration;
  impl->lastDts = dts; impl->started = true;
  impl->flushFragment();
}
void H264Fmp4Muxer::appendAac(const std::vector<uint8_t>& sample, int64_t ptsUs) {
  if (impl->ended || impl->failed || !impl->aacSamplesPerFrame) {
    throw std::logic_error("AAC track is unavailable");
  }
  if (!impl->started && impl->lastAudioDts >= 0) {
    throw std::logic_error("Supply the first IDR before more AAC");
  }
  if (sample.empty() || sample.size() > 65536 || ptsUs < 0) {
    throw std::invalid_argument("Invalid AAC packet");
  }
  const auto dts = av_rescale_q(ptsUs, AVRational{1, 1000000}, AVRational{1, 44100});
  if (dts <= impl->lastAudioDts) throw std::invalid_argument("Invalid AAC timestamp");
  impl->writePacket(sample, 1, dts, dts, impl->aacSamplesPerFrame, true);
  impl->lastAudioDts = dts;
  impl->flushFragment();
}
void H264Fmp4Muxer::finish() {
  if (impl->ended) return;
  if (impl->failed) throw std::runtime_error("Video mux failed before finalization");
  if (impl->aacSamplesPerFrame && !impl->firstFragmentWritten) {
    throw std::logic_error("Both media tracks must start before finalization");
  }
  const int result = av_write_trailer(impl->context);
  avio_flush(impl->io); impl->check(result); impl->ended = true;
}
