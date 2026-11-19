#include "AlacAacTranscoder.h"
#include "AlacDecoder.h"
#include <stdexcept>
#include <utility>

struct AlacAacTranscoder::Impl {
  explicit Impl(Output output) : encoder(std::move(output)) {}
  AlacDecoder decoder;
  PcmAacEncoder encoder;
  bool ended = false, failed = false;
};

AlacAacTranscoder::AlacAacTranscoder(Output output)
  : impl(std::make_unique<Impl>(std::move(output))) {}
AlacAacTranscoder::~AlacAacTranscoder() = default;

void AlacAacTranscoder::append(const std::vector<uint8_t>& packet, int64_t pts) {
  if (impl->ended || impl->failed) throw std::logic_error("ALAC transcoder is closed");
  try {
    const auto pcm = impl->decoder.decode(packet);
    impl->encoder.append(pcm.data(), pcm.size(), pts);
  } catch (...) {
    impl->failed = true;
    throw;
  }
}

void AlacAacTranscoder::finish() {
  if (impl->failed) throw std::logic_error("ALAC transcoder failed before finalization");
  impl->encoder.finish();
  impl->ended = true;
}
