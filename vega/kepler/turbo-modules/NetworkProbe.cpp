#include "NetworkProbe.h"
#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace {
struct Socket {
  int fd;
  explicit Socket(int value, bool adopt = false) : fd(adopt ? value : socket(AF_INET, value, 0)) {}
  ~Socket() { if (fd >= 0) close(fd); }
  Socket(const Socket&) = delete;
  Socket& operator=(const Socket&) = delete;
};

sockaddr_in address(uint32_t ip) {
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(ip);
  return addr;
}

bool ready(int fd, short events) {
  pollfd item{fd, events, 0};
  int result;
  do { result = poll(&item, 1, 200); } while (result < 0 && errno == EINTR);
  return result > 0 && (item.revents & events) != 0;
}

bool tcp() {
  Socket server(SOCK_STREAM), client(SOCK_STREAM);
  if (server.fd < 0 || client.fd < 0) return false;
  auto addr = address(INADDR_LOOPBACK);
  if (bind(server.fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
      listen(server.fd, 1) != 0) return false;
  socklen_t size = sizeof(addr);
  if (getsockname(server.fd, reinterpret_cast<sockaddr*>(&addr), &size) != 0) return false;
  const int flags = fcntl(client.fd, F_GETFL, 0);
  if (flags < 0 || fcntl(client.fd, F_SETFL, flags | O_NONBLOCK) != 0) return false;
  if (connect(client.fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 && errno != EINPROGRESS) return false;
  if (!ready(client.fd, POLLOUT)) return false;
  int error = 0;
  size = sizeof(error);
  if (getsockopt(client.fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0 || error != 0 || !ready(server.fd, POLLIN)) return false;
  // The accepted descriptor is owned by its own guard, including fd == 0.
  Socket peer(accept(server.fd, nullptr, nullptr), true);
  if (peer.fd < 0) return false;
  timeval timeout{0, 200000};
  if (setsockopt(peer.fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) return false;
  const char payload[] = "buzin-tcp-probe";
  if (send(client.fd, payload, sizeof(payload), MSG_NOSIGNAL) != sizeof(payload) || !ready(peer.fd, POLLIN)) return false;
  char buffer[sizeof(payload)]{};
  return recv(peer.fd, buffer, sizeof(buffer), MSG_WAITALL) == sizeof(payload) &&
         std::memcmp(buffer, payload, sizeof(payload)) == 0;
}

bool udp() {
  Socket receiver(SOCK_DGRAM), sender(SOCK_DGRAM);
  if (receiver.fd < 0 || sender.fd < 0) return false;
  auto addr = address(INADDR_LOOPBACK);
  if (bind(receiver.fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) return false;
  socklen_t size = sizeof(addr);
  if (getsockname(receiver.fd, reinterpret_cast<sockaddr*>(&addr), &size) != 0) return false;
  const char payload[] = "buzin-udp-probe";
  if (sendto(sender.fd, payload, sizeof(payload), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != sizeof(payload) || !ready(receiver.fd, POLLIN)) return false;
  char buffer[sizeof(payload) + 1]{};
  return recv(receiver.fd, buffer, sizeof(buffer), 0) == sizeof(payload) &&
         std::memcmp(buffer, payload, sizeof(payload)) == 0;
}

bool wildcardBind() {
  Socket server(SOCK_STREAM);
  auto addr = address(INADDR_ANY);
  return server.fd >= 0 && bind(server.fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0;
}

struct MembershipResult {
  bool success;
  const char* stage;
  int error;
};

MembershipResult multicastMembership() {
  Socket receiver(SOCK_DGRAM);
  if (receiver.fd < 0) return {false, "socket", errno};
  ip_mreq membership{};
  membership.imr_multiaddr.s_addr = inet_addr("224.0.0.251");
  membership.imr_interface.s_addr = htonl(INADDR_ANY);
  // A sandbox may have no default multicast route. Select an actual active
  // LAN interface instead of relying on INADDR_ANY route selection.
  ifaddrs* interfaces = nullptr;
  if (getifaddrs(&interfaces) != 0) return {false, "interfaces", errno};
  for (auto* item = interfaces; item; item = item->ifa_next) {
    if (!item->ifa_addr || item->ifa_addr->sa_family != AF_INET ||
        !(item->ifa_flags & IFF_UP) || !(item->ifa_flags & IFF_MULTICAST) ||
        (item->ifa_flags & IFF_LOOPBACK)) continue;
    membership.imr_interface = reinterpret_cast<sockaddr_in*>(item->ifa_addr)->sin_addr;
    break;
  }
  freeifaddrs(interfaces);
  if (membership.imr_interface.s_addr == htonl(INADDR_ANY))
    return {false, "interface", ENODEV};
  // Membership only: never transmit a DNS-SD advertisement or claim LAN reachability.
  if (setsockopt(receiver.fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &membership, sizeof(membership)) != 0)
    return {false, "join", errno};
  if (setsockopt(receiver.fd, IPPROTO_IP, IP_DROP_MEMBERSHIP, &membership, sizeof(membership)) != 0)
    return {false, "leave", errno};
  return {true, "complete", 0};
}
}

std::string runNetworkProbe() {
  const bool tcpOk = tcp(), udpOk = udp(), bindOk = wildcardBind();
  const auto multicast = multicastMembership();
  return std::string("{\"tcpLoopback\":") + (tcpOk ? "true" : "false") +
    ",\"udpLoopback\":" + (udpOk ? "true" : "false") +
    ",\"wildcardBind\":" + (bindOk ? "true" : "false") +
    ",\"multicastMembership\":" + (multicast.success ? "true" : "false") +
    ",\"multicastStage\":\"" + multicast.stage + "\",\"multicastErrno\":" +
    std::to_string(multicast.error) + "}";
}
