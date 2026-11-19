#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

class MirrorRecording {
public:
  struct Entry {std::string name; uint64_t bytes;};

  explicit MirrorRecording(std::string directory);
  ~MirrorRecording();
  std::string state() const;
  void arm();
  void stop() noexcept;
  void endSource() noexcept;
  void video(int width, int height, const std::vector<uint8_t>& config, int audioSamples,
             const std::vector<uint8_t>& bytes, int64_t pts, int64_t dts, int64_t duration) noexcept;
  void audio(const std::vector<uint8_t>& bytes, int64_t pts) noexcept;
  std::vector<Entry> list() const;
  std::string url(const std::string& name) const;
  void remove(const std::string& name);

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
