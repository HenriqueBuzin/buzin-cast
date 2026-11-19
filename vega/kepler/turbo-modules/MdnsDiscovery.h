#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

class MdnsDiscovery {
public:
  MdnsDiscovery() = default;
  ~MdnsDiscovery();
  MdnsDiscovery(const MdnsDiscovery&) = delete;
  MdnsDiscovery& operator=(const MdnsDiscovery&) = delete;

  struct Service {
    std::string instance;
    std::string type;
    uint16_t port;
    std::vector<uint8_t> txt;
  };

  void startFCast(uint16_t tcpPort, const std::string& receiverName);
  void start(const std::string& host, std::vector<Service> definitions);
  void stop() noexcept;
  bool running() const noexcept { return active.load(); }

private:
  void run() noexcept;
  std::atomic<bool> active{false};
  int socketFd = -1;
  struct PublishedService {
    Service definition;
    std::vector<uint8_t> records;
    std::vector<uint8_t> goodbye;
  };
  std::vector<PublishedService> services;
  std::string hostName;
  std::thread worker;
};
