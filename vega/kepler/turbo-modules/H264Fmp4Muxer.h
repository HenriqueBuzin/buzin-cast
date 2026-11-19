#pragma once
#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

// Remuxes complete Annex B H.264 access units without decoding/re-encoding.
// All operations and output callbacks belong to one media worker.
class H264Fmp4Muxer {
public:
  using Output = std::function<void(const uint8_t*, size_t)>;
  // With AAC-LC enabled, supply one packet for each track before further
  // packets. Both PTS values are relative to the same capture clock, and
  // packets on each track must not overlap.
  H264Fmp4Muxer(int width, int height, const std::vector<uint8_t>& parameterSets,
                Output output, int aacSamplesPerFrame = 0);
  ~H264Fmp4Muxer();
  H264Fmp4Muxer(const H264Fmp4Muxer&) = delete;
  H264Fmp4Muxer& operator=(const H264Fmp4Muxer&) = delete;
  void append(const std::vector<uint8_t>& annexB, int64_t ptsUs, int64_t dtsUs, int64_t durationUs);
  void appendAac(const std::vector<uint8_t>& sample, int64_t ptsUs);
  void finish();
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
