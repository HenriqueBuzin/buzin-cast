#include "H264Fmp4Muxer.h"
#include <cstring>
#include <exception>
#include <limits>
#include <stdexcept>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/mem.h>
}
namespace {
struct Nal {const uint8_t* bytes; size_t size; int type;};
std::vector<Nal> split(const std::vector<uint8_t>& bytes) {
  if (bytes.empty() || bytes.size() > 2*1024*1024) throw std::invalid_argument("Invalid H.264 access unit size");
  auto prefix = [&](size_t i) -> size_t {
    if (i+3 <= bytes.size() && bytes[i] == 0 && bytes[i+1] == 0) {
      if (bytes[i+2] == 1) return 3;
      if (i+4 <= bytes.size() && bytes[i+2] == 0 && bytes[i+3] == 1) return 4;
    }
    return 0;
  };
  std::vector<Nal> units;
  size_t offset = 0;
  while (offset < bytes.size()) {
    const auto start = prefix(offset);
    if (!start || units.size() >= 128) throw std::invalid_argument("Invalid bounded Annex B packet");
    const auto first = offset+start;
    size_t end = first;
    while (end < bytes.size() && !prefix(end)) ++end;
    size_t last = end;
    while (last > first && bytes[last-1] == 0) --last;
    if (last == first || (bytes[first]&0x80)) throw std::invalid_argument("Invalid H.264 NAL header");
    const int type = bytes[first]&31;
    if (type == 0 || type > 12 || (type >= 2 && type <= 4)) throw std::invalid_argument("Unsupported H.264 NAL type");
    units.push_back({bytes.data()+first,last-first,type}); offset = end;
  }
  return units;
}

}
struct H264Fmp4Muxer::Impl {
  AVFormatContext* context = nullptr;
  AVIOContext* io = nullptr;
  Output output;
  std::exception_ptr outputError;
  int64_t lastDts = -1;
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
};
H264Fmp4Muxer::H264Fmp4Muxer(int width, int height, const std::vector<uint8_t>& parameterSets, Output output)
  : impl(std::make_unique<Impl>()) {
  if (width < 16 || width > 3840 || height < 16 || height > 2160 || !output || parameterSets.size() > 65536)
    throw std::invalid_argument("Invalid video mux configuration");
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
  AVDictionary* options = nullptr;
  av_dict_set(&options,"movflags","frag_custom+empty_moov+default_base_moof+skip_trailer+skip_sidx",0);
  av_dict_set(&options,"video_track_timescale","1000000",0);
  const int result = avformat_write_header(impl->context,&options);
  av_dict_free(&options); avio_flush(impl->io); impl->check(result);
}
H264Fmp4Muxer::~H264Fmp4Muxer() = default;
void H264Fmp4Muxer::append(const std::vector<uint8_t>& bytes, int64_t pts, int64_t dts, int64_t duration) {
  if (impl->ended || impl->failed) throw std::runtime_error("Video mux is closed");
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
  AVPacket* packet = av_packet_alloc();
  if (!packet) throw std::bad_alloc();
  if (av_new_packet(packet,static_cast<int>(bytes.size())) < 0) {av_packet_free(&packet); throw std::bad_alloc();}
  std::memcpy(packet->data,bytes.data(),bytes.size());
  packet->pts = pts; packet->dts = dts; packet->duration = duration; packet->stream_index = 0;
  if (keyframe) packet->flags |= AV_PKT_FLAG_KEY;
  const int result = av_write_frame(impl->context,packet);
  av_packet_free(&packet); impl->check(result);
  impl->check(av_write_frame(impl->context,nullptr)); avio_flush(impl->io); impl->check(0);
  impl->lastDts = dts; impl->started = true;
}
void H264Fmp4Muxer::finish() {
  if (impl->ended) return;
  if (impl->failed) throw std::runtime_error("Video mux failed before finalization");
  const int result = av_write_trailer(impl->context);
  avio_flush(impl->io); impl->check(result); impl->ended = true;
}
