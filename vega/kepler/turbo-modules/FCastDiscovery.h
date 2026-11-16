#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

class FCastDiscovery {
public:
  FCastDiscovery() = default;
  ~FCastDiscovery();
  FCastDiscovery(const FCastDiscovery&) = delete;
  FCastDiscovery& operator=(const FCastDiscovery&) = delete;

  void start(uint16_t tcpPort, const std::string& receiverName = "Buzin Cast");
  void stop() noexcept;
  bool running() const noexcept { return active.load(); }

private:
  void run() noexcept;
  std::atomic<bool> active{false};
  int socketFd = -1;
  uint16_t port = 0;
  std::string serviceName;
  std::string hostName;
  uint32_t address = 0;
  std::thread worker;
};
