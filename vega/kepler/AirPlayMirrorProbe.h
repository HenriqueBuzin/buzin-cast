#pragma once
#include "turbo-modules/AirPlayVideoWorker.h"
#include <memory>
#include <vector>

struct AirPlayVideoFixtureFrame {
  uint32_t pts, duration;
  bool keyframe;
  std::vector<uint8_t> data;
};

// Development-only TCP/AES-CTR sender and actual UxPlay mirror receiver, with
// public deterministic test keys. Never negotiates or replaces FairPlay keys.
class AirPlayMirrorProbe {
public:
  AirPlayMirrorProbe(AirPlayVideoWorker& worker, int width, int height,
    const std::vector<uint8_t>& configuration, const std::vector<AirPlayVideoFixtureFrame>& frames);
  ~AirPlayMirrorProbe();
  void send(size_t index);
  void stop();
  bool healthy() const;
  bool socketsReleased() const;
  uint64_t received() const;
  uint64_t timed() const;
  uint64_t fragmentedWrites() const;
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
