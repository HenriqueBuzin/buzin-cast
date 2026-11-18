#include "AlacDecoder.h"
#include <cstring>
#include <stdexcept>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>
}
struct AlacDecoder::Impl {
  AVCodecContext* context = nullptr;
  AVFrame* frame = nullptr;
  AVPacket* packet = nullptr;
  ~Impl() {av_packet_free(&packet); av_frame_free(&frame); avcodec_free_context(&context);}
};
AlacDecoder::AlacDecoder() : impl(std::make_unique<Impl>()) {
  const auto* codec = avcodec_find_decoder(AV_CODEC_ID_ALAC);
  if (!codec) throw std::runtime_error("ALAC decoder is unavailable");
  impl->context = avcodec_alloc_context3(codec);
  impl->frame = av_frame_alloc(); impl->packet = av_packet_alloc();
  if (!impl->context || !impl->frame || !impl->packet) throw std::bad_alloc();
  // Same ALAC atom cookie as the shared Fire OS decoder.
  const uint8_t config[] = {0,0,0,36,'a','l','a','c',0,0,0,0,0,0,1,96,0,16,40,10,14,2,0,255,0,0,0,0,0,0,0,0,0,0,172,68};
  auto& context = *impl->context;
  context.extradata = static_cast<uint8_t*>(av_mallocz(sizeof(config)+AV_INPUT_BUFFER_PADDING_SIZE));
  if (!context.extradata) throw std::bad_alloc();
  std::memcpy(context.extradata,config,sizeof(config)); context.extradata_size = sizeof(config);
  context.thread_count = 1; context.err_recognition = AV_EF_EXPLODE;
  if (avcodec_open2(&context,codec,nullptr) < 0) throw std::runtime_error("Could not initialize ALAC decoder");
}
AlacDecoder::~AlacDecoder() = default;
std::vector<uint8_t> AlacDecoder::decode(const std::vector<uint8_t>& input) {
  if (input.empty() || input.size() > 65536) throw std::invalid_argument("Invalid ALAC packet size");
  av_packet_unref(impl->packet); av_frame_unref(impl->frame);
  if (av_new_packet(impl->packet,static_cast<int>(input.size())) < 0) throw std::bad_alloc();
  std::memcpy(impl->packet->data,input.data(),input.size());
  if (avcodec_send_packet(impl->context,impl->packet) < 0 || avcodec_receive_frame(impl->context,impl->frame) < 0)
    throw std::runtime_error("Invalid ALAC access unit");
  const auto& frame = *impl->frame;
  if (frame.nb_samples != 352 || frame.sample_rate != 44100 || frame.ch_layout.nb_channels != 2 || frame.format != AV_SAMPLE_FMT_S16P)
    throw std::runtime_error("Unsupported decoded ALAC profile");
  std::vector<uint8_t> pcm(352*2*2);
  for (int sample = 0; sample < 352; ++sample)
    for (int channel = 0; channel < 2; ++channel)
      std::memcpy(pcm.data()+(sample*2+channel)*2,frame.extended_data[channel]+sample*2,2);
  return pcm;
}
