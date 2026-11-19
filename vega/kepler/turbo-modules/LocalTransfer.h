#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <string_view>

namespace LocalTransfer {
using Clock = std::chrono::steady_clock;

struct Socket {
  int fd;
  ~Socket();
};

int listen(std::string& host);
int accept(int listener);
bool isLocalHost(const std::string& host);
int connect(const std::atomic<bool>& active, const std::string& host, uint16_t port, Clock::time_point end);
std::string randomPath(unsigned bytes);
bool waitFor(const std::atomic<bool>& active, int fd, short event, Clock::time_point end);
bool sendAll(const std::atomic<bool>& active, int fd, std::string_view data, Clock::time_point end);
}
