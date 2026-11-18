#pragma once
#include <cstdint>
#include <vector>

// All calls belong to one playback worker, never to a transport callback.
class AudioSampleSink {
public:
  virtual ~AudioSampleSink() = default;
  virtual void startAac(int sampleRate, int channels, const std::vector<uint8_t>& config) = 0;
  virtual bool append(const std::vector<uint8_t>& sample, int64_t timestampMs, int64_t durationMs) = 0;
  virtual double position() = 0;
  virtual void setVolume(double volume) = 0;
  virtual void stop() = 0;
};
