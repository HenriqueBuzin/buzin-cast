#pragma once
#include <cstdint>
#include <memory>
#include <vector>

// Fixed RAOP ALAC profile: 352 frames, 16-bit stereo, 44.1 kHz.
class AlacDecoder {
public:
  AlacDecoder();
  ~AlacDecoder();
  AlacDecoder(const AlacDecoder&) = delete;
  AlacDecoder& operator=(const AlacDecoder&) = delete;
  std::vector<uint8_t> decode(const std::vector<uint8_t>& packet);
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
