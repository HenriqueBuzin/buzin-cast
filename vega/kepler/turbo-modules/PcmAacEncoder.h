#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

// Encodes interleaved 16-bit stereo PCM at 44.1 kHz as AAC-LC.
class PcmAacEncoder {
public:
  static constexpr int FrameSamples = 1024;
  using Output = std::function<void(const std::vector<uint8_t>&, int64_t)>;

  explicit PcmAacEncoder(Output output);
  ~PcmAacEncoder();
  void append(const uint8_t* pcm, size_t bytes, int64_t pts);
  void finish();

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
