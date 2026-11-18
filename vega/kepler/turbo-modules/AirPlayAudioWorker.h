#pragma once
#include "AudioSampleSink.h"
#include <functional>
#include <memory>
#include <string>

struct AirPlayAudioState {
  std::string phase = "stopped";
  std::string error;
  double seconds = 0;
  double volumeDb = 0;
  uint64_t accepted = 0;
  uint64_t dropped = 0;
  uint64_t flushes = 0;
  size_t queuedBytes = 0;
  bool released = true;
};

class AirPlayAudioWorker {
public:
  explicit AirPlayAudioWorker(std::function<std::unique_ptr<AudioSampleSink>()> factory);
  ~AirPlayAudioWorker();
  AirPlayAudioWorker(const AirPlayAudioWorker&) = delete;
  AirPlayAudioWorker& operator=(const AirPlayAudioWorker&) = delete;
  void start();
  void stop() noexcept;
  void format(int codec, int samplesPerFrame) noexcept;
  void sample(const uint8_t* data, size_t length, int codec, uint64_t localNtpNs, uint32_t rtpTime) noexcept;
  void flush() noexcept;
  void volume(double decibels) noexcept;
  AirPlayAudioState state() const;
  bool healthy() const;
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
