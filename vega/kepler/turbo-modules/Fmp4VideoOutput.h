#pragma once
#include "VideoSampleSink.h"
#include <memory>

// Owns both the muxer and its bounded loopback stream for one output session.
// No media payload or muxer lifetime crosses into JavaScript.
class Fmp4VideoOutput final : public VideoSampleSink {
public:
  Fmp4VideoOutput();
  ~Fmp4VideoOutput() override;
  Fmp4VideoOutput(const Fmp4VideoOutput&) = delete;
  Fmp4VideoOutput& operator=(const Fmp4VideoOutput&) = delete;
  std::string start(int width, int height, const std::vector<uint8_t>& parameterSets,
                    int aacSamplesPerFrame = 0) override;
  void append(const std::vector<uint8_t>& sample, int64_t ptsUs, int64_t dtsUs, int64_t durationUs) override;
  void appendAac(const std::vector<uint8_t>& sample, int64_t ptsUs) override;
  void finish() override;
  void waitFinished(std::chrono::milliseconds timeout = std::chrono::seconds(15)) override;
  void cancel() noexcept override;
  void stop() noexcept override;
  LocalVideoStreamState state() const override;
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
