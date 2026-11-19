#include "MdnsDiscovery.h"
#include "ReceiverSettings.h"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <poll.h>
#include <random>
#include <stdexcept>
#include <string_view>
#include <utility>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr uint32_t MdnsGroup = 0xe00000fb; // 224.0.0.251
constexpr uint16_t MdnsPort = 5353;
constexpr uint32_t RecordTtl = 120;
constexpr auto AnnounceInterval = std::chrono::seconds(60);

void u16(std::vector<uint8_t>& out, uint16_t value) {
  out.push_back(static_cast<uint8_t>(value >> 8));
  out.push_back(static_cast<uint8_t>(value));
}
void u32(std::vector<uint8_t>& out, uint32_t value) {
  out.push_back(static_cast<uint8_t>(value >> 24));
  out.push_back(static_cast<uint8_t>(value >> 16));
  out.push_back(static_cast<uint8_t>(value >> 8));
  out.push_back(static_cast<uint8_t>(value));
}
void name(std::vector<uint8_t>& out, const std::string& fqdn) {
  size_t begin = 0;
  while (begin < fqdn.size()) {
    const auto end = fqdn.find('.', begin);
    const auto length = (end == std::string::npos ? fqdn.size() : end) - begin;
    if (!length || length > 63) throw std::invalid_argument("Invalid DNS label");
    out.push_back(static_cast<uint8_t>(length));
    out.insert(out.end(), fqdn.begin() + begin, fqdn.begin() + begin + length);
    if (end == std::string::npos) { begin = fqdn.size(); break; }
    begin = end + 1;
  }
  out.push_back(0);
}
void record(std::vector<uint8_t>& out, const std::string& owner, uint16_t type,
            uint16_t klass, uint32_t ttl, const std::vector<uint8_t>& data) {
  name(out, owner);
  u16(out, type);
  u16(out, klass);
  u32(out, ttl);
  if (data.size() > UINT16_MAX) throw std::invalid_argument("DNS record too large");
  u16(out, static_cast<uint16_t>(data.size()));
  out.insert(out.end(), data.begin(), data.end());
}
uint32_t lanAddress() {
  ifaddrs* interfaces = nullptr;
  if (getifaddrs(&interfaces) != 0) throw std::runtime_error("Could not inspect network interfaces");
  uint32_t selected = 0;
  for (auto* item = interfaces; item; item = item->ifa_next) {
    if (!item->ifa_addr || item->ifa_addr->sa_family != AF_INET ||
        (item->ifa_flags & IFF_LOOPBACK) || !(item->ifa_flags & IFF_UP) ||
        !(item->ifa_flags & IFF_MULTICAST)) continue;
    const auto candidate = reinterpret_cast<sockaddr_in*>(item->ifa_addr)->sin_addr.s_addr;
    if (candidate != htonl(INADDR_ANY)) { selected = candidate; break; }
  }
  freeifaddrs(interfaces);
  if (!selected) throw std::runtime_error("No active LAN IPv4 address for mDNS discovery");
  return selected;
}

std::vector<uint8_t> announcement(const MdnsDiscovery::Service& service,
                                 const std::string& host, uint32_t address,
                                 uint32_t ttl) {
  std::vector<uint8_t> out(12, 0);
  out[2] = 0x84; // authoritative mDNS response
  out[3] = 0x00;
  out[7] = 4; // PTR, SRV, TXT and A answers

  std::vector<uint8_t> ptr;
  const auto instance = service.instance + "." + service.type;
  name(ptr, instance);
  record(out, service.type, 12, 1, ttl, ptr);

  std::vector<uint8_t> srv;
  u16(srv, 0); u16(srv, 0); u16(srv, service.port);
  name(srv, host);
  record(out, instance, 33, 0x8001, ttl, srv); // cache-flush SRV

  record(out, instance, 16, 0x8001, ttl, service.txt); // cache-flush TXT

  std::vector<uint8_t> ipv4(4);
  std::memcpy(ipv4.data(), &address, ipv4.size());
  record(out, host, 1, 0x8001, ttl, ipv4); // cache-flush A
  if (out.size() > 1400) throw std::runtime_error("mDNS packet too large");
  return out;
}

// Read only DNS questions, including compressed names; never search arbitrary
// payload bytes for a service string. Bound pointer traversal to reject cycles.
bool dnsName(const uint8_t* packet, size_t length, size_t& cursor, std::string& result) {
  size_t position = cursor;
  bool jumped = false;
  for (size_t hops = 0; hops < 128; ++hops) {
    if (position >= length) return false;
    const auto label = packet[position++];
    if ((label & 0xc0) == 0xc0) {
      if (position >= length) return false;
      const size_t target = ((label & 0x3f) << 8) | packet[position++];
      if (!jumped) cursor = position;
      jumped = true;
      // DNS compression references prior names only.
      if (target >= position - 2) return false;
      position = target;
      continue;
    }
    if (label & 0xc0 || position + label > length) return false;
    if (!label) {
      if (!jumped) cursor = position;
      return true;
    }
    if (!result.empty()) result += '.';
    for (size_t n = 0; n < label; ++n) {
      auto c = packet[position++];
      if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
      result += static_cast<char>(c);
    }
    if (result.size() > 253) return false;
  }
  return false;
}

bool asksForService(const uint8_t* packet, size_t length,
                  const MdnsDiscovery::Service& service, const std::string& host) {
  if (length < 12 || (packet[2] & 0xf8) || (packet[3] & 0x0f)) return false;
  const size_t count = (packet[4] << 8) | packet[5];
  if (count > 64) return false;
  size_t cursor = 12;
  bool matched = false;
  std::string lowerService = service.instance + "." + service.type;
  for (auto& c : lowerService) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
  for (size_t q = 0; q < count; ++q) {
    std::string question;
    if (!dnsName(packet, length, cursor, question) || cursor + 4 > length) return false;
    const auto type = (packet[cursor] << 8) | packet[cursor + 1];
    const auto klass = ((packet[cursor + 2] << 8) | packet[cursor + 3]) & 0x7fff;
    cursor += 4;
    if (klass != 1 && klass != 255) continue;
    matched |= (question == service.type && (type == 12 || type == 255)) ||
        (question == lowerService && (type == 16 || type == 33 || type == 255)) ||
        (question == host && (type == 1 || type == 255));
  }
  return matched;
}
}

MdnsDiscovery::~MdnsDiscovery() { stop(); }

void MdnsDiscovery::startFCast(uint16_t tcpPort, const std::string& receiverName) {
  validateReceiverName(receiverName);
  std::random_device random;
  char suffix[7];
  std::snprintf(suffix, sizeof(suffix), "%06x", random() & 0xffffff);
  std::vector<uint8_t> txt;
  for (const std::string_view value : {"v=2", "appName=Buzin Cast"}) {
    txt.push_back(static_cast<uint8_t>(value.size()));
    txt.insert(txt.end(), value.begin(), value.end());
  }
  start(std::string("buzincast-") + suffix + ".local", {{
      receiverName + "-" + suffix, "_fcast._tcp.local", tcpPort, std::move(txt),
  }});
}

void MdnsDiscovery::start(const std::string& host, std::vector<Service> definitions) {
  if (worker.joinable() || active) throw std::runtime_error("mDNS discovery already started");
  if (definitions.empty()) throw std::invalid_argument("Missing mDNS services");
  const auto address = lanAddress();
  std::vector<PublishedService> prepared;
  for (auto& service : definitions) {
    if (!service.port || service.instance.empty() || service.instance.find('.') != std::string::npos)
      throw std::invalid_argument("Invalid mDNS service");
    auto records = announcement(service, host, address, RecordTtl);
    auto goodbye = announcement(service, host, address, 0);
    prepared.push_back({std::move(service), std::move(records), std::move(goodbye)});
  }
  hostName = host;
  services = std::move(prepared);

  const int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0) throw std::runtime_error("Could not create mDNS socket");
  socketFd = fd;
  const int yes = 1;
  if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes)) != 0 ||
      setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, sizeof(yes)) != 0) {
    stop(); throw std::runtime_error("Could not share the mDNS socket");
  }
  sockaddr_in local{};
  local.sin_family = AF_INET;
  local.sin_port = htons(MdnsPort);
  local.sin_addr.s_addr = htonl(INADDR_ANY);
  if (::bind(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) != 0) {
    stop(); throw std::runtime_error("Could not bind mDNS port 5353");
  }
  ip_mreq membership{};
  membership.imr_multiaddr.s_addr = htonl(MdnsGroup);
  membership.imr_interface.s_addr = address;
  if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &membership, sizeof(membership)) != 0) {
    stop(); throw std::runtime_error("Could not join the mDNS multicast group");
  }
  const unsigned char ttl = 255;
  if (setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof(ttl)) != 0 ||
      setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, &address, sizeof(address)) != 0) {
    setsockopt(fd, IPPROTO_IP, IP_DROP_MEMBERSHIP, &membership, sizeof(membership));
    stop(); throw std::runtime_error("Could not configure mDNS multicast");
  }
  sockaddr_in multicast{};
  multicast.sin_family = AF_INET;
  multicast.sin_port = htons(MdnsPort);
  multicast.sin_addr.s_addr = htonl(MdnsGroup);
  try {
    active = true;
    worker = std::thread([this] { run(); });
    for (const auto& service : services) {
      const auto& initial = service.records;
      if (sendto(fd, initial.data(), initial.size(), 0,
          reinterpret_cast<const sockaddr*>(&multicast), sizeof(multicast)) !=
          static_cast<ssize_t>(initial.size()))
        throw std::runtime_error("Could not announce service over mDNS");
    }
  } catch (...) {
    stop();
    throw;
  }
}

void MdnsDiscovery::stop() noexcept {
  active = false;
  if (worker.joinable()) worker.join();
  if (socketFd >= 0) { ::close(socketFd); socketFd = -1; }
  services.clear();
}

void MdnsDiscovery::run() noexcept {
  sockaddr_in multicast{};
  multicast.sin_family = AF_INET;
  multicast.sin_port = htons(MdnsPort);
  multicast.sin_addr.s_addr = htonl(MdnsGroup);
  auto lastAnnouncement = std::chrono::steady_clock::now();
  auto sendRecords = [&](const std::vector<uint8_t>& packet) {
    const auto sent = sendto(socketFd, packet.data(), packet.size(), 0,
        reinterpret_cast<const sockaddr*>(&multicast), sizeof(multicast));
    return sent == static_cast<ssize_t>(packet.size());
  };

  while (active) {
    const auto now = std::chrono::steady_clock::now();
    if (now - lastAnnouncement >= AnnounceInterval) {
      for (const auto& service : services) {
        if (!sendRecords(service.records)) { active = false; break; }
      }
      if (!active) break;
      lastAnnouncement = now;
    }
    pollfd item{socketFd, POLLIN, 0};
    const auto ready = ::poll(&item, 1, 1000);
    if (ready < 0) { if (errno == EINTR) continue; break; }
    if (!ready || !(item.revents & POLLIN)) continue;
    uint8_t packet[1500];
    sockaddr_in peer{};
    socklen_t peerLength = sizeof(peer);
    const auto size = recvfrom(socketFd, packet, sizeof(packet), 0,
        reinterpret_cast<sockaddr*>(&peer), &peerLength);
    if (size <= 0 || peer.sin_port != htons(MdnsPort)) continue;
    for (const auto& service : services) {
      if (asksForService(packet, static_cast<size_t>(size), service.definition, hostName) &&
          !sendRecords(service.records)) {
        active = false;
        break;
      }
    }
  }
  for (const auto& service : services) sendRecords(service.goodbye);
  active = false;
}
