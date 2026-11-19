#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

// Repackages the AirPlay ALAC stereo profile as AAC-LC. PTS values are
// expressed in samples at 44.1 kHz, relative to the mirror session.
class AlacAacTranscoder {
public:
  static constexpr int FrameSamples = 1024;
  using Output = std::function<void(const std::vector<uint8_t>&, int64_t)>;

  explicit AlacAacTranscoder(Output output);
  ~AlacAacTranscoder();
  void append(const std::vector<uint8_t>& packet, int64_t pts);
  void finish();

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
