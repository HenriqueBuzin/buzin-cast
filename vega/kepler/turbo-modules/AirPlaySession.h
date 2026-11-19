#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

class AirPlayAudioWorker;
class AirPlayVideoWorker;

struct AirPlayEvent {
  enum class Kind {Audio, Video, PlayUrl, MirrorUrl, Seek, Rate, Volume, Stop, FlushAudio, FlushVideo, Reset, Feedback, Ready};
  Kind kind = Kind::Stop;
  int codec = 0;
  uint64_t localTime = 0;
  uint64_t remoteTime = 0;
  double value = 0;
  std::string url;
  std::vector<uint8_t> bytes;
};

// Owns UxPlay, bounded callback queues, and DNS-SD for LAN video sessions.
class AirPlaySession {
public:
  AirPlaySession();
  ~AirPlaySession();
  AirPlaySession(const AirPlaySession&) = delete;
  AirPlaySession& operator=(const AirPlaySession&) = delete;
  uint16_t start(const std::string& keyPath, const std::string& name,
                 const std::string& identity, bool loopbackOnly = true,
                 AirPlayAudioWorker* audio = nullptr, AirPlayVideoWorker* video = nullptr);
  void stop() noexcept;
  bool poll(AirPlayEvent& event);
  double volumeDb() const;
  bool healthy() const;
  bool advertising() const noexcept;
  void updatePlayback(double position, double duration, double rate, bool ready);
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
