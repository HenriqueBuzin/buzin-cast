#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

class RelaySender {
public:
  RelaySender();
  ~RelaySender();
  void start(const std::string& host, uint16_t port, const std::string& code);
  std::string state() const;
  void video(int width, int height, const std::vector<uint8_t>& config,
             const std::vector<uint8_t>& sample, int64_t ptsUs) noexcept;
  void audio(int samplesPerFrame, const std::vector<uint8_t>& sample, int64_t ptsUs) noexcept;
  void endSource() noexcept;
  void finish() noexcept;
  void stop() noexcept;

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
