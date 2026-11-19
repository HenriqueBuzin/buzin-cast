#pragma once
#include "VideoSampleSink.h"
#include <functional>
#include <memory>

struct AirPlayVideoState {
  std::string phase = "stopped", error;
  uint64_t generation = 0, accepted = 0, dropped = 0, flushes = 0;
  size_t queuedBytes = 0;
  int width = 0, height = 0;
  bool released = true;
  LocalVideoStreamState output;
};
struct AirPlayVideoCommand {
  enum class Kind {Play, Stop};
  Kind kind = Kind::Stop;
  uint64_t generation = 0;
  std::string url;
};

// Copies bounded transport callbacks. All mux/player ownership belongs to its
// native worker. Only a local URL/control command may cross to JavaScript.
// Initial implementation expects low-delay H.264 (no reordered pictures).
class AirPlayVideoWorker {
public:
  explicit AirPlayVideoWorker(std::function<std::unique_ptr<VideoSampleSink>()> factory);
  ~AirPlayVideoWorker();
  AirPlayVideoWorker(const AirPlayVideoWorker&) = delete;
  AirPlayVideoWorker& operator=(const AirPlayVideoWorker&) = delete;
  void start();
  void stop() noexcept;
  void format(int width, int height) noexcept;
  void sample(const uint8_t* bytes, size_t length, bool h265, uint64_t localNtpNs) noexcept;
  void flush() noexcept;
  void end() noexcept;
  bool poll(AirPlayVideoCommand& command);
  AirPlayVideoState state() const;
  bool healthy() const;
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
