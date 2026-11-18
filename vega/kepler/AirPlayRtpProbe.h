#pragma once
#include "turbo-modules/AirPlayAudioWorker.h"
#include <memory>
#include <vector>

// Development-only loopback sender/receiver with public fixture keys. It does
// not replace or bypass the production session's pairing/key negotiation.
class AirPlayRtpProbe {
public:
  AirPlayRtpProbe(AirPlayAudioWorker& worker, const std::vector<std::vector<uint8_t>>& fixture, int codec = 4, int samplesPerFrame = 1024);
  ~AirPlayRtpProbe();
  void beginEpoch();
  void send(size_t index, bool duplicate);
  void flush();
  void setVolume(float db);
  void stop();
  bool socketsReleased() const;
  uint64_t received() const;
  uint64_t timed() const;
  uint64_t duplicates() const;
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
