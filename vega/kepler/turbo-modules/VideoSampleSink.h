#pragma once
#include "LocalVideoStream.h"
#include <vector>

// Native media-worker boundary: complete Annex B access units in, URL out.
// start/append/finish/waitFinished/stop have exactly one owning media worker.
// cancel/state may also be called concurrently by session/control threads.
// Cancellation only affects the current output; the caller must serialize
// starting a replacement with its own session generation.
class VideoSampleSink {
public:
  virtual ~VideoSampleSink() = default;
  virtual std::string start(int width, int height, const std::vector<uint8_t>& parameterSets) = 0;
  virtual void append(const std::vector<uint8_t>& sample, int64_t ptsUs, int64_t dtsUs, int64_t durationUs) = 0;
  virtual void finish() = 0;
  virtual void waitFinished(std::chrono::milliseconds timeout = std::chrono::seconds(15)) = 0;
  virtual void cancel() noexcept = 0;
  virtual void stop() noexcept = 0;
  virtual LocalVideoStreamState state() const = 0;
};
