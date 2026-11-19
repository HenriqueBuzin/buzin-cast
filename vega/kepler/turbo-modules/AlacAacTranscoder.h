#pragma once

#include "PcmAacEncoder.h"

// Repackages the AirPlay ALAC stereo profile as AAC-LC. PTS values are
// expressed in samples at 44.1 kHz, relative to the mirror session.
class AlacAacTranscoder {
public:
  static constexpr int FrameSamples = PcmAacEncoder::FrameSamples;
  using Output = PcmAacEncoder::Output;

  explicit AlacAacTranscoder(Output output);
  ~AlacAacTranscoder();
  void append(const std::vector<uint8_t>& packet, int64_t pts);
  void finish();

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
