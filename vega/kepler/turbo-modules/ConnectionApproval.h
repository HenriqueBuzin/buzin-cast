#pragma once

#include <algorithm>
#include <arpa/inet.h>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <optional>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <utility>
#include <vector>

// Each receiver owns its own instance, so decisions are scoped to a protocol
// and expire when its screen closes. Only the receiver worker waits here.
class ConnectionApproval {
public:
  using Clock = std::chrono::steady_clock;

  void start(bool requireApproval) {
    std::lock_guard lock(mutex);
    enabled = requireApproval;
    active = true;
  }

  void stop() {
    {
      std::lock_guard lock(mutex);
      active = false;
      cached.clear();
      event.clear();
      pending = 0;
      answer.reset();
    }
    changed.notify_all();
  }

  std::optional<Clock::time_point> authorize(int fd, uint32_t peer, bool fullDuplex = false) {
    using namespace std::chrono_literals;
    std::unique_lock lock(mutex);
    if (!active) return {};
    if (!enabled) return Clock::time_point::max();
    const auto now = Clock::now();
    std::erase_if(cached, [&](const auto& item) { return item.expires <= now; });
    const auto known = std::find_if(cached.begin(), cached.end(), [peer](const auto& item) {
      return item.peer == peer;
    });
    if (known != cached.end()) return known->allow ? std::optional{known->expires} : std::nullopt;
    if (pending) return {};

    char ip[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &peer, ip, sizeof(ip))) return {};
    if (++sequence == 0) ++sequence;
    const auto id = sequence;
    pending = id;
    event = "{\"approval\":{\"id\":" + std::to_string(id) + ",\"peer\":\"" + ip + "\"}}";
    const auto deadline = now + 25s;
    bool disconnected = false;
    while (active && pending == id && !answer && Clock::now() < deadline) {
      // Leave any data already sent untouched until the socket is authorized.
      pollfd client{fd, 0, 0};
      char byte;
      if ((::poll(&client, 1, 0) > 0 && client.revents & (POLLHUP | POLLERR | POLLNVAL)) ||
          (fullDuplex && recv(fd, &byte, 1, MSG_PEEK | MSG_DONTWAIT) == 0)) {
        disconnected = true;
        break;
      }
      changed.wait_for(lock, 100ms);
    }
    if (!active || pending != id) return {};
    const bool allow = answer.value_or(false) && Clock::now() < deadline;
    pending = 0;
    answer.reset();
    event = "{\"approval\":null}";
    if (disconnected) return {};
    const auto expires = Clock::now() + (allow ? 5min : 30s);
    if (cached.size() >= 16) cached.erase(cached.begin());
    cached.push_back({peer, expires, allow});
    return allow ? std::optional{expires} : std::nullopt;
  }

  std::string poll() {
    std::lock_guard lock(mutex);
    return std::exchange(event, {});
  }

  void respond(double id, bool allow) {
    std::lock_guard lock(mutex);
    if (!active || !pending || id != pending || answer.has_value()) return;
    answer = allow;
    changed.notify_one();
  }

private:
  struct Decision { uint32_t peer; Clock::time_point expires; bool allow; };
  std::mutex mutex;
  std::condition_variable changed;
  bool active = false;
  bool enabled = false;
  unsigned int sequence = 0;
  unsigned int pending = 0;
  std::optional<bool> answer;
  std::string event;
  std::vector<Decision> cached;
};
