#include "LocalTransfer.h"

#include <arpa/inet.h>
#include <cerrno>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <openssl/rand.h>
#include <poll.h>
#include <stdexcept>
#include <sys/socket.h>
#include <unistd.h>
#include <utility>

namespace {
bool privateAddress(uint32_t address) {
  const auto ip = ntohl(address);
  return (ip >> 24) == 10 || (ip >> 20) == 0xac1 || (ip >> 16) == 0xc0a8 || (ip >> 16) == 0xa9fe;
}

std::string lanAddress() {
  ifaddrs* list = nullptr;
  if (getifaddrs(&list) != 0) throw std::runtime_error("Could not inspect network interfaces");
  char address[INET_ADDRSTRLEN]{};
  for (auto* item = list; item; item = item->ifa_next) {
    if (!item->ifa_addr || item->ifa_addr->sa_family != AF_INET ||
        (item->ifa_flags & IFF_LOOPBACK) || !(item->ifa_flags & IFF_UP)) continue;
    const auto ip = reinterpret_cast<sockaddr_in*>(item->ifa_addr)->sin_addr;
    if (privateAddress(ip.s_addr) && inet_ntop(AF_INET, &ip, address, sizeof(address))) break;
  }
  freeifaddrs(list);
  if (!*address) throw std::runtime_error("No private IPv4 interface for local transfer");
  return address;
}
}

namespace LocalTransfer {
Socket::~Socket() {if (fd >= 0) close(fd);}

int listen(std::string& host) {
  const auto ip = lanAddress();
  Socket socket{::socket(AF_INET, SOCK_STREAM, 0)};
  sockaddr_in address{};
  address.sin_family = AF_INET;
  socklen_t size = sizeof(address);
  if (socket.fd < 0 || fcntl(socket.fd, F_SETFL, O_NONBLOCK) < 0 ||
      bind(socket.fd, reinterpret_cast<sockaddr*>(&address), size) != 0 ||
      ::listen(socket.fd, 4) != 0 || getsockname(socket.fd, reinterpret_cast<sockaddr*>(&address), &size) != 0)
    throw std::runtime_error("Could not start a local transfer");
  host = ip + ":" + std::to_string(ntohs(address.sin_port));
  return std::exchange(socket.fd, -1);
}

int accept(int listener) {
  sockaddr_in peer{};
  socklen_t size = sizeof(peer);
  Socket client{::accept(listener, reinterpret_cast<sockaddr*>(&peer), &size)};
  if (client.fd < 0 || fcntl(client.fd, F_SETFL, O_NONBLOCK) < 0) return -1;
#ifdef SO_NOSIGPIPE
  constexpr int enabled = 1;
  if (setsockopt(client.fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) != 0) return -1;
#endif
  const auto ip = ntohl(peer.sin_addr.s_addr);
  if (!privateAddress(peer.sin_addr.s_addr) && (ip >> 24) != 127) return -1;
  return std::exchange(client.fd, -1);
}

std::string randomPath(unsigned bytes) {
  unsigned char random[16];
  if (!bytes || bytes > sizeof(random) || RAND_bytes(random, static_cast<int>(bytes)) != 1)
    throw std::runtime_error("Could not generate a local transfer link");
  constexpr char hex[] = "0123456789abcdef";
  std::string path = "/";
  for (unsigned i = 0; i < bytes; ++i) {
    path += hex[random[i] >> 4];
    path += hex[random[i] & 15];
  }
  return path;
}

bool waitFor(const std::atomic<bool>& active, int fd, short event, Clock::time_point end) {
  while (active && Clock::now() < end) {
    pollfd ready{fd, event, 0};
    const int result = ::poll(&ready, 1, 50);
    if (result > 0) return (ready.revents & event) != 0;
    if (result < 0 && errno != EINTR) break;
  }
  return false;
}

bool sendAll(const std::atomic<bool>& active, int fd, std::string_view data, Clock::time_point end) {
  while (!data.empty() && waitFor(active, fd, POLLOUT, end)) {
#ifdef MSG_NOSIGNAL
    const auto sent = send(fd, data.data(), data.size(), MSG_NOSIGNAL);
#else
    const auto sent = send(fd, data.data(), data.size(), 0);
#endif
    if (sent < 0 && (errno == EINTR || errno == EAGAIN)) continue;
    if (sent <= 0) return false;
    data.remove_prefix(static_cast<size_t>(sent));
  }
  return data.empty();
}
}
