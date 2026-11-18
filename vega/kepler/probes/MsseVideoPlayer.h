#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include <string>

// Development-only MSSE experiment. No application surface is attached;
// raw H.264 negotiation has not passed on VVD. Never advertise this as a receiver.
class MsseVideoPlayer {
public:
  MsseVideoPlayer();
  ~MsseVideoPlayer();
  MsseVideoPlayer(const MsseVideoPlayer&) = delete;
  MsseVideoPlayer& operator=(const MsseVideoPlayer&) = delete;
  void start(int width, int height, const std::vector<uint8_t>& config, const std::string& mime = "video/avc");
  bool append(const std::vector<uint8_t>& sample, int64_t timestampMs, int64_t durationMs, bool keyframe);
  double position();
  uint32_t totalFrames();
  void end();
  void stop();
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
