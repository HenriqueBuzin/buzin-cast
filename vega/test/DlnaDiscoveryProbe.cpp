// Development-only SSDP client used by the VVD integration test.
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <ifaddrs.h>
#include <iostream>
#include <net/if.h>
#include <poll.h>
#include <string>
#include <string_view>
#include <unistd.h>

int main(int argc, char** argv) {
  if (argc != 2) return 1;
  std::string_view port(argv[1]);
  if (port.empty() || port.size() > 5 || port.find_first_not_of("0123456789") != port.npos) return 1;
  const int fd = socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0) return 2;
  struct Close { int fd; ~Close() { close(fd); } } guard{fd};
  ifaddrs* interfaces = nullptr;
  if (getifaddrs(&interfaces) != 0) return 2;
  in_addr local{};
  for (auto* item = interfaces; item; item = item->ifa_next) {
    if (item->ifa_addr && item->ifa_addr->sa_family == AF_INET &&
        (item->ifa_flags & IFF_UP) && (item->ifa_flags & IFF_MULTICAST) &&
        !(item->ifa_flags & IFF_LOOPBACK)) {
      local = reinterpret_cast<const sockaddr_in*>(item->ifa_addr)->sin_addr;
      break;
    }
  }
  freeifaddrs(interfaces);
  if (!local.s_addr ||
      setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, &local, sizeof(local)) != 0) return 2;

  const std::string_view type = "urn:schemas-upnp-org:device:MediaRenderer:1";
  const auto request = std::string("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n") +
      "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: " + std::string(type) + "\r\n\r\n";
  sockaddr_in group{};
  group.sin_family = AF_INET;
  group.sin_addr.s_addr = htonl(0xeffffffa);
  group.sin_port = htons(1900);
  if (sendto(fd, request.data(), request.size(), 0, reinterpret_cast<const sockaddr*>(&group),
      sizeof(group)) != static_cast<ssize_t>(request.size())) return 2;

  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
  const auto suffix = ":" + std::string(port) + "/desc.xml\r\n";
  const auto target = "ST: " + std::string(type) + "\r\n";
  while (std::chrono::steady_clock::now() < deadline) {
    pollfd ready{fd, POLLIN, 0};
    if (poll(&ready, 1, 150) <= 0) continue;
    char bytes[2048];
    const auto count = recv(fd, bytes, sizeof(bytes), 0);
    if (count <= 0) continue;
    const std::string_view reply(bytes, static_cast<size_t>(count));
    if (!reply.starts_with("HTTP/1.1 200 OK\r\n") || reply.find(target) == reply.npos) continue;
    const auto location = reply.find("\r\nLOCATION: http://");
    const auto end = location == reply.npos ? reply.npos : reply.find("\r\n", location + 2);
    if (end == reply.npos || end < suffix.size() ||
        reply.substr(end + 2 - suffix.size(), suffix.size()) != suffix) continue;
    std::cout << "MediaRenderer SSDP response points to the active HTTP port\n";
    return 0;
  }
  std::cerr << "MediaRenderer SSDP response missing\n";
  return 3;
}
