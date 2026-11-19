#pragma once

#include <cstdint>
#include <memory>
#include <vector>

class AacPcmDecoder {
public:
  explicit AacPcmDecoder(int samplesPerFrame);
  ~AacPcmDecoder();
  std::vector<uint8_t> decode(const std::vector<uint8_t>& packet);
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
