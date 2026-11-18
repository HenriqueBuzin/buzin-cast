#pragma once
#include <chrono>
#include <cstdint>
#include <memory>
#include <string>

struct LocalVideoStreamState {
  size_t queuedBytes = 0;
  uint64_t sentBytes = 0;
  bool connected = false, complete = false, released = true;
  std::string error;
};
// One loopback-only, non-seekable HTTP stream. Output is bounded to 8 MiB,
// including the chunk currently being sent; at most 2048 chunks. Never publishes on the LAN.
class LocalVideoStream {
public:
  LocalVideoStream();
  ~LocalVideoStream();
  LocalVideoStream(const LocalVideoStream&) = delete;
  LocalVideoStream& operator=(const LocalVideoStream&) = delete;
  std::string start();
  void write(const uint8_t* bytes, size_t size);
  void finish();
  void waitFinished(std::chrono::milliseconds timeout = std::chrono::seconds(15));
  void stop() noexcept;
  LocalVideoStreamState state() const;
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
