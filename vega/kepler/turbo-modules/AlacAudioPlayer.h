#pragma once
#include <cstdint>
#include <memory>
#include <vector>

// PCM output and focus are owned by the same worker as ALAC decoding.
class AlacAudioPlayer {
public:
  AlacAudioPlayer();
  ~AlacAudioPlayer();
  void start();
  bool append(const std::vector<uint8_t>& packet, int64_t timestampMs, int64_t durationMs);
  double position();
  void setVolume(double volume);
  void stop();
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
