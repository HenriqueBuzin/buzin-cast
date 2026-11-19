#include "DlnaTransport.h"

#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <fcntl.h>
#include <ifaddrs.h>
#include <mutex>
#include <net/if.h>
#include <poll.h>
#include <stdexcept>
#include <string_view>
#include <thread>
#include <unistd.h>
#include <utility>

extern "C" {
#include "llhttp.h"
}

namespace {
using namespace std::chrono_literals;
using Clock = std::chrono::steady_clock;
constexpr size_t MaxRequest = 40 * 1024;
constexpr size_t MaxResponse = 64 * 1024;
constexpr uint16_t SsdpPort = 1900;
constexpr uint32_t SsdpGroup = 0xeffffffa;
constexpr std::string_view Server = "Vega/2.0 UPnP/1.0 BuzinCast/0.6";
constexpr auto MaxAge = "max-age=1800";
constexpr auto Renderer = "urn:schemas-upnp-org:device:MediaRenderer:1";
constexpr std::string_view Services[] = {
  "urn:schemas-upnp-org:service:AVTransport:1",
  "urn:schemas-upnp-org:service:RenderingControl:1",
  "urn:schemas-upnp-org:service:ConnectionManager:1",
};

struct Socket {
  explicit Socket(int value = -1) : fd(value) {}
  Socket(const Socket&) = delete;
  Socket& operator=(const Socket&) = delete;
  ~Socket() { if (fd >= 0) ::close(fd); }
  int release() { return std::exchange(fd, -1); }
  void reset() { if (fd >= 0) ::close(release()); }
  int fd;
};

sockaddr_in address(uint32_t host, uint16_t port) {
  sockaddr_in result{};
  result.sin_family = AF_INET;
  result.sin_addr.s_addr = host;
  result.sin_port = htons(port);
  return result;
}

bool privatePeer(uint32_t networkAddress) {
  const auto ip = ntohl(networkAddress);
  return (ip >> 24) == 127 || (ip >> 24) == 10 || (ip >> 20) == 0xac1 ||
         (ip >> 16) == 0xc0a8 || (ip >> 16) == 0xa9fe;
}

struct Lan {
  uint32_t address = 0;
  uint32_t mask = 0;
};

Lan lanIpv4() {
  ifaddrs* entries = nullptr;
  if (getifaddrs(&entries) != 0) throw std::runtime_error("Could not inspect DLNA network");
  Lan result;
  for (auto* entry = entries; entry; entry = entry->ifa_next) {
    if (!entry->ifa_addr || !entry->ifa_netmask || entry->ifa_addr->sa_family != AF_INET ||
        (entry->ifa_flags & IFF_LOOPBACK) || !(entry->ifa_flags & IFF_UP) ||
        !(entry->ifa_flags & IFF_MULTICAST)) continue;
    const auto ip = reinterpret_cast<const sockaddr_in*>(entry->ifa_addr)->sin_addr.s_addr;
    if (!privatePeer(ip)) continue;
    result = {ip, reinterpret_cast<const sockaddr_in*>(entry->ifa_netmask)->sin_addr.s_addr};
    break;
  }
  freeifaddrs(entries);
  if (!result.address || !result.mask) throw std::runtime_error("Could not find DLNA LAN interface");
  return result;
}

void nonblocking(int fd) {
  const auto flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0)
    throw std::runtime_error("Could not configure DLNA socket");
#ifdef SO_NOSIGPIPE
  const int yes = 1;
  setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof(yes));
#endif
}

std::string lower(std::string_view value) {
  std::string result(value);
  for (char& c : result) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
  return result;
}

std::string_view trim(std::string_view value) {
  const size_t start = value.find_first_not_of(" \t");
  if (start == std::string_view::npos) return {};
  return value.substr(start, value.find_last_not_of(" \t") - start + 1);
}

std::string searchTarget(std::string_view packet) {
  const auto first = packet.find("\r\n");
  if (first == packet.npos || packet.substr(0, first) != "M-SEARCH * HTTP/1.1") return {};
  packet.remove_prefix(first + 2);
  std::string target;
  bool discover = false;
  while (!packet.empty()) {
    const auto end = packet.find("\r\n");
    if (end == packet.npos) return {};
    if (end == 0) return discover ? target : std::string{};
    const auto line = packet.substr(0, end);
    const auto colon = line.find(':');
    if (colon == line.npos) return {};
    const auto header = lower(line.substr(0, colon));
    const auto value = trim(line.substr(colon + 1));
    if (header == "man") discover = lower(value) == "\"ssdp:discover\"";
    if (header == "st") target = lower(value);
    packet.remove_prefix(end + 2);
  }
  return {};
}

std::string json(std::string_view value) {
  std::string result = "\"";
  constexpr char digits[] = "0123456789abcdef";
  for (const unsigned char c : value) {
    if (c == '"' || c == '\\') { result += '\\'; result += static_cast<char>(c); }
    else if (c < 32) { result += "\\u00"; result += digits[c >> 4]; result += digits[c & 15]; }
    else result += static_cast<char>(c);
  }
  return result + '"';
}

struct HttpRequest {
  llhttp_t parser{};
  std::string path;
  std::string key;
  std::string value;
  std::string soapAction;
  std::string contentType;
  std::string body;
  bool ready = false;

  HttpRequest() {
    static const auto settings = [] {
      llhttp_settings_t callbacks;
      llhttp_settings_init(&callbacks);
      callbacks.on_url = [](llhttp_t* p, const char* data, size_t length) {
        auto& state = self(p);
        if (state.path.size() + length > 256) return static_cast<int>(HPE_USER);
        state.path.append(data, length);
        return 0;
      };
      callbacks.on_header_field = [](llhttp_t* p, const char* data, size_t length) {
        auto& state = self(p);
        if (state.key.size() + length > 128) return static_cast<int>(HPE_USER);
        state.key.append(data, length);
        return 0;
      };
      callbacks.on_header_value = [](llhttp_t* p, const char* data, size_t length) {
        auto& state = self(p);
        if (state.value.size() + length > 4096) return static_cast<int>(HPE_USER);
        state.value.append(data, length);
        return 0;
      };
      callbacks.on_header_value_complete = [](llhttp_t* p) {
        auto& state = self(p);
        const auto field = lower(state.key);
        auto* destination = field == "soapaction" ? &state.soapAction :
            field == "content-type" ? &state.contentType : nullptr;
        if (destination) {
          if (!destination->empty()) return static_cast<int>(HPE_USER);
          *destination = std::move(state.value);
        }
        state.key.clear();
        state.value.clear();
        return 0;
      };
      callbacks.on_body = [](llhttp_t* p, const char* data, size_t length) {
        self(p).body.append(data, length);
        return 0;
      };
      callbacks.on_message_complete = [](llhttp_t* p) {
        self(p).ready = true;
        return static_cast<int>(HPE_PAUSED);
      };
      return callbacks;
    }();
    llhttp_init(&parser, HTTP_REQUEST, &settings);
    parser.data = this;
  }

  static HttpRequest& self(llhttp_t* p) { return *static_cast<HttpRequest*>(p->data); }
};

struct Response {
  int status;
  std::string body;
};

std::string_view reason(int status) {
  switch (status) {
    case 200: return "OK";
    case 400: return "Bad Request";
    case 404: return "Not Found";
    case 405: return "Method Not Allowed";
    case 413: return "Content Too Large";
    case 415: return "Unsupported Media Type";
    case 500: return "Internal Server Error";
    case 501: return "Not Implemented";
    default: return "Service Unavailable";
  }
}

Socket multicastSocket(const Lan& lan) {
  Socket socket{::socket(AF_INET, SOCK_DGRAM, 0)};
  const int yes = 1;
  const unsigned char ttl = 2;
  const auto local = address(htonl(INADDR_ANY), SsdpPort);
  const ip_mreq membership{{htonl(SsdpGroup)}, {lan.address}};
  if (socket.fd < 0 ||
      setsockopt(socket.fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes)) != 0 ||
      setsockopt(socket.fd, SOL_SOCKET, SO_REUSEPORT, &yes, sizeof(yes)) != 0 ||
      ::bind(socket.fd, reinterpret_cast<const sockaddr*>(&local), sizeof(local)) != 0 ||
      setsockopt(socket.fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &membership, sizeof(membership)) != 0 ||
      setsockopt(socket.fd, IPPROTO_IP, IP_MULTICAST_IF, &lan.address, sizeof(lan.address)) != 0 ||
      setsockopt(socket.fd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof(ttl)) != 0)
    throw std::runtime_error("Could not bind DLNA discovery");
  nonblocking(socket.fd);
  return Socket{socket.release()};
}
}

struct DlnaTransport::Impl {
  std::atomic<bool> active{false};
  std::thread worker;
  Socket listener;
  Socket ssdp;
  std::mutex mutex;
  std::condition_variable changed;
  unsigned int sequence = 0;
  unsigned int pendingId = 0;
  std::string pending;
  Response reply{0, {}};
  Lan lan;
  std::string uuid;
  std::string location;

  void discovery(const sockaddr_in& peer, std::string_view target, bool response, bool goodbye) const {
    const std::string_view types[] = {"upnp:rootdevice", uuid, Renderer, Services[0], Services[1], Services[2]};
    for (const auto type : types) {
      if (response && target != "ssdp:all" && target != lower(type)) continue;
      std::string message = response ? "HTTP/1.1 200 OK\r\nEXT:\r\nST: " :
          "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: ";
      message += type;
      message += "\r\nUSN: " + uuid;
      if (type != uuid) { message += "::"; message += type; }
      if (!response) message += goodbye ? "\r\nNTS: ssdp:byebye" : "\r\nNTS: ssdp:alive";
      if (!goodbye) {
        message += "\r\nCACHE-CONTROL: ";
        message += MaxAge;
        message += "\r\nLOCATION: " + location + "\r\nSERVER: ";
        message += Server;
      }
      message += "\r\n\r\n";
      sendto(ssdp.fd, message.data(), message.size(), 0,
          reinterpret_cast<const sockaddr*>(&peer), sizeof(peer));
    }
  }

  bool sameLan(uint32_t peer) const {
    return lan.address && privatePeer(peer) && (peer & lan.mask) == (lan.address & lan.mask);
  }

  bool ready(int fd, short events, Clock::time_point deadline) const {
    while (active && Clock::now() < deadline) {
      pollfd item{fd, events, 0};
      const auto result = ::poll(&item, 1, 50);
      if (result > 0) return item.revents & events;
      if (result < 0 && errno != EINTR) return false;
    }
    return false;
  }

  Response dispatch(const HttpRequest& request) {
    std::unique_lock lock(mutex);
    if (++sequence == 0) ++sequence;
    pending = "{\"id\":" + std::to_string(sequence) + ",\"method\":" +
        json(llhttp_method_name(static_cast<llhttp_method_t>(request.parser.method))) +
        ",\"path\":" + json(request.path) + ",\"soapAction\":" + json(request.soapAction) +
        ",\"contentType\":" + json(request.contentType) + ",\"body\":" + json(request.body) + "}";
    pendingId = sequence;
    changed.wait_for(lock, 1500ms, [this] { return !active || reply.status; });
    pending.clear();
    pendingId = 0;
    auto response = std::exchange(reply, {0, {}});
    return response.status ? std::move(response) : Response{503, {}};
  }

  void serve(int fd) {
    HttpRequest request;
    Response response{400, {}};
    const auto end = Clock::now() + 1500ms;
    size_t bytes = 0;
    char input[4096];
    while (ready(fd, POLLIN, end)) {
      const auto count = recv(fd, input, sizeof(input), 0);
      if (count < 0 && (errno == EAGAIN || errno == EINTR)) continue;
      if (count <= 0) return;
      bytes += static_cast<size_t>(count);
      if (bytes > MaxRequest) { response.status = 413; break; }
      const auto parsed = llhttp_execute(&request.parser, input, static_cast<size_t>(count));
      if (request.ready && parsed == HPE_PAUSED) {
        response = dispatch(request);
        break;
      }
      if (parsed != HPE_OK) break;
    }
    std::string output = "HTTP/1.1 " + std::to_string(response.status) + " ";
    output += reason(response.status);
    output += "\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nConnection: close\r\nContent-Length: " +
        std::to_string(response.body.size()) + "\r\n\r\n";
    if (request.parser.method != HTTP_HEAD) output += response.body;
    const auto writeEnd = Clock::now() + 500ms;
    for (size_t sent = 0; sent < output.size() && ready(fd, POLLOUT, writeEnd);) {
#ifdef MSG_NOSIGNAL
      const auto count = send(fd, output.data() + sent, output.size() - sent, MSG_NOSIGNAL);
#else
      const auto count = send(fd, output.data() + sent, output.size() - sent, 0);
#endif
      if (count < 0 && (errno == EAGAIN || errno == EINTR)) continue;
      if (count <= 0) break;
      sent += static_cast<size_t>(count);
    }
  }

  void run() noexcept {
    const auto group = address(htonl(SsdpGroup), SsdpPort);
    auto nextAnnounce = Clock::now();
    auto nextSearch = nextAnnounce;
    try {
      while (active) {
        const auto now = Clock::now();
        if (ssdp.fd >= 0 && now >= nextAnnounce) {
          discovery(group, {}, false, false);
          nextAnnounce = now + 10min;
        }
        pollfd items[] = {{listener.fd, POLLIN, 0}, {ssdp.fd, POLLIN, 0}};
        const auto count = ::poll(items, 2, 50);
        if (count < 0 && errno != EINTR) break;
        if (count <= 0) continue;
        if (items[1].revents & POLLIN) {
          char packet[1500];
          sockaddr_in peer{};
          socklen_t size = sizeof(peer);
          const auto received = recvfrom(ssdp.fd, packet, sizeof(packet), 0,
              reinterpret_cast<sockaddr*>(&peer), &size);
          if (received > 0 && received < static_cast<ssize_t>(sizeof(packet)) &&
              sameLan(peer.sin_addr.s_addr) && now >= nextSearch) {
            const auto target = searchTarget({packet, static_cast<size_t>(received)});
            if (!target.empty()) {
              discovery(peer, target, true, false);
              nextSearch = now + 100ms;
            }
          }
        }
        if (items[0].revents & POLLIN) {
          sockaddr_in peer{};
          socklen_t size = sizeof(peer);
          Socket client{::accept(listener.fd, reinterpret_cast<sockaddr*>(&peer), &size)};
          if (client.fd >= 0 && (sameLan(peer.sin_addr.s_addr) || (ntohl(peer.sin_addr.s_addr) >> 24) == 127)) {
            nonblocking(client.fd);
            serve(client.fd);
          }
        }
      }
      if (ssdp.fd >= 0) discovery(group, {}, false, true);
    } catch (...) {
      // poll() reports shutdown to the screen so it can release the player.
    }
    active = false;
  }
};

DlnaTransport::DlnaTransport() : impl(std::make_unique<Impl>()) {}
DlnaTransport::~DlnaTransport() { stop(); }

uint16_t DlnaTransport::start(const std::string& uuid, bool loopbackOnly) {
  if (impl->worker.joinable()) throw std::runtime_error("DLNA receiver already started");
  impl->uuid = uuid;
  impl->lan = loopbackOnly ? Lan{} : lanIpv4();
  Socket listener{::socket(AF_INET, SOCK_STREAM, 0)};
  auto endpoint = address(htonl(loopbackOnly ? INADDR_LOOPBACK : INADDR_ANY), 0);
  socklen_t length = sizeof(endpoint);
  if (listener.fd < 0 || ::bind(listener.fd, reinterpret_cast<const sockaddr*>(&endpoint), length) != 0 ||
      ::listen(listener.fd, 8) != 0 || ::getsockname(listener.fd, reinterpret_cast<sockaddr*>(&endpoint), &length) != 0)
    throw std::runtime_error("Could not bind DLNA control listener");
  nonblocking(listener.fd);
  if (!loopbackOnly) {
    char ip[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &impl->lan.address, ip, sizeof(ip)))
      throw std::runtime_error("Could not format DLNA LAN address");
    impl->location = std::string("http://") + ip + ":" + std::to_string(ntohs(endpoint.sin_port)) + "/desc.xml";
    auto ssdp = multicastSocket(impl->lan);
    impl->ssdp.fd = ssdp.release();
  }
  impl->listener.fd = listener.release();
  try {
    impl->active = true;
    impl->worker = std::thread([this] { impl->run(); });
  } catch (...) {
    stop();
    throw;
  }
  return ntohs(endpoint.sin_port);
}

void DlnaTransport::stop() noexcept {
  {
    std::lock_guard lock(impl->mutex);
    impl->active = false;
  }
  impl->changed.notify_all();
  if (impl->worker.joinable()) impl->worker.join();
  impl->ssdp.reset();
  impl->listener.reset();
}

std::string DlnaTransport::poll() {
  std::lock_guard lock(impl->mutex);
  if (!impl->active) throw std::runtime_error("DLNA receiver stopped");
  return std::exchange(impl->pending, {});
}

void DlnaTransport::respond(double requestId, double status, std::string body) {
  std::lock_guard lock(impl->mutex);
  if (!impl->pendingId || requestId != impl->pendingId || impl->reply.status) return;
  if (body.size() > MaxResponse) throw std::invalid_argument("DLNA response too large");
  if (status != 200 && status != 400 && status != 404 && status != 405 &&
      status != 415 && status != 500 && status != 501)
    throw std::invalid_argument("Invalid DLNA response status");
  impl->reply = {static_cast<int>(status), std::move(body)};
  impl->changed.notify_one();
}
