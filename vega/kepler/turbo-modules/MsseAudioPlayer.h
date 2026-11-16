#pragma once
#include <cstdint>
#include <memory>
#include <vector>

// Native compressed audio sink. The caller owns the stream clock and must
// enqueue access units in decoding order with timestamps relative to its epoch.
class MsseAudioPlayer {
public:
  MsseAudioPlayer();
  ~MsseAudioPlayer();
  MsseAudioPlayer(const MsseAudioPlayer&) = delete;
  MsseAudioPlayer& operator=(const MsseAudioPlayer&) = delete;
  void startAac(int sampleRate, int channels, const std::vector<uint8_t>& config);
  bool append(const std::vector<uint8_t>& sample, int64_t timestampMs, int64_t durationMs);
  double position();
  void end();
  void stop();
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
