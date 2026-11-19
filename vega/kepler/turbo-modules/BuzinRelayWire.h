#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <string_view>
#include <vector>

namespace BuzinRelay {
using Clock = std::chrono::steady_clock;
constexpr size_t MaxSample = 4 * 1024 * 1024;
using Bytes = std::vector<uint8_t>;

// Compatible with the shared Kotlin SecureMirrorChannel and MirrorPacketCodec.
class Channel {
public:
  Channel(int fd, const std::atomic<bool>& active);
  ~Channel();
  void accept(std::string_view code);
  void connect(std::string_view code);
  Bytes receive(Clock::time_point deadline);
  void send(const Bytes& plain, Clock::time_point deadline);
private:
  void read(uint8_t* data, size_t size, Clock::time_point deadline);
  void write(const uint8_t* data, size_t size, Clock::time_point deadline);
  void derive(std::string_view code, const std::array<uint8_t, 16>& salt);

  int fd;
  const std::atomic<bool>& active;
  std::array<uint8_t, 32> key{};
  uint64_t tx = 0, rx = 0;
};

struct Packet {
  enum class Kind {Video, Audio, End};
  Kind kind;
  uint64_t timestamp = 0;
  int width = 0, height = 0;
  bool hevc = false;
  Bytes data;
};
Bytes encode(const Packet& packet);
Packet decode(Bytes wire);

}
