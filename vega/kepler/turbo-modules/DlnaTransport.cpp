#include "DlnaTransport.h"

#include <algorithm>
#include <arpa/inet.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <charconv>
#include <chrono>
#include <condition_variable>
#include <fcntl.h>
#include <ifaddrs.h>
#include <mutex>
#include <net/if.h>
#include <optional>
#include <poll.h>
#include <random>
#include <stdexcept>
#include <string_view>
#include <thread>
#include <unistd.h>
#include <utility>
#include <vector>

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

struct EventHeaders {
  std::optional<std::string> callback;
  std::optional<std::string> nt;
  std::optional<std::string> sid;
  std::optional<std::string> timeout;
};

struct HttpRequest {
  llhttp_t parser{};
  std::string path;
  std::string key;
  std::string value;
  std::string soapAction;
  std::string contentType;
  std::string body;
  EventHeaders events;
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
        auto* event = field == "callback" ? &state.events.callback :
            field == "nt" ? &state.events.nt : field == "sid" ? &state.events.sid :
            field == "timeout" ? &state.events.timeout : nullptr;
        if (event) {
          if (*event) return static_cast<int>(HPE_USER);
          *event = std::move(state.value);
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
  std::string headers{};
  std::string initial{};
};

std::string_view reason(int status) {
  switch (status) {
    case 200: return "OK";
    case 400: return "Bad Request";
    case 403: return "Forbidden";
    case 404: return "Not Found";
    case 405: return "Method Not Allowed";
    case 412: return "Precondition Failed";
    case 413: return "Content Too Large";
    case 415: return "Unsupported Media Type";
    case 500: return "Internal Server Error";
    case 501: return "Not Implemented";
    default: return "Service Unavailable";
  }
}

bool socketReady(const std::atomic<bool>& active, int fd, short events, Clock::time_point deadline) {
  while (active && Clock::now() < deadline) {
    pollfd item{fd, events, 0};
    const auto result = ::poll(&item, 1, 50);
    if (result > 0) return item.revents & events;
    if (result < 0 && errno != EINTR) return false;
  }
  return false;
}

bool sendAll(const std::atomic<bool>& active, int fd, std::string_view data, Clock::time_point deadline) {
  while (!data.empty() && socketReady(active, fd, POLLOUT, deadline)) {
#ifdef MSG_NOSIGNAL
    const auto count = send(fd, data.data(), data.size(), MSG_NOSIGNAL);
#else
    const auto count = send(fd, data.data(), data.size(), 0);
#endif
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) continue;
    if (count <= 0) return false;
    data.remove_prefix(static_cast<size_t>(count));
  }
  return data.empty();
}

std::string subscriptionId() {
  static constexpr char digits[] = "0123456789abcdef";
  std::random_device random;
  std::string id = "uuid:";
  for (int i = 0; i < 16; ++i) {
    if (i == 4 || i == 6 || i == 8 || i == 10) id += '-';
    unsigned byte = random() & 255;
    if (i == 6) byte = 0x40 | (byte & 15);
    if (i == 8) byte = 0x80 | (byte & 63);
    id += digits[byte >> 4];
    id += digits[byte & 15];
  }
  return id;
}

struct Callback {
  sockaddr_in endpoint;
  std::string host;
  std::string path;
};

std::optional<Callback> parseCallback(std::string_view value, uint32_t peer) {
  value = trim(value);
  if (!value.starts_with("<http://") || !value.ends_with('>') || value.size() > 2048) return {};
  value.remove_prefix(8);
  value.remove_suffix(1);
  const auto slash = value.find('/');
  const auto authority = value.substr(0, slash);
  const auto path = slash == value.npos ? "/" : value.substr(slash);
  if (path.find_first_of("<>#\\") != path.npos ||
      std::any_of(path.begin(), path.end(), [](unsigned char c) { return c <= 32 || c >= 127; })) return {};
  const auto colon = authority.find(':');
  const std::string host(authority.substr(0, colon));
  in_addr ip{};
  if (inet_pton(AF_INET, host.c_str(), &ip) != 1 || ip.s_addr != peer) return {};
  unsigned port = 80;
  if (colon != authority.npos) {
    const auto text = authority.substr(colon + 1);
    const auto parsed = std::from_chars(text.data(), text.data() + text.size(), port);
    if (parsed.ec != std::errc() || parsed.ptr != text.data() + text.size() || !port || port > 65535) return {};
  }
  return Callback{address(peer, static_cast<uint16_t>(port)), host + ':' + std::to_string(port), std::string(path)};
}

unsigned eventTimeout(const std::optional<std::string>& header) {
  if (!header) return 1800;
  auto value = trim(*header);
  if (lower(value.substr(0, 7)) != "second-") return 0;
  value.remove_prefix(7);
  if (lower(value) == "infinite") return 1800;
  unsigned seconds = 0;
  const auto result = std::from_chars(value.data(), value.data() + value.size(), seconds);
  if (result.ec != std::errc() || result.ptr != value.data() + value.size()) return 0;
  return std::min(seconds, 1800u);
}

class Events {
public:
  void start() {
    running = true;
    worker = std::thread([this] { run(); });
  }

  void stop() {
    {
      std::lock_guard lock(mutex);
      running = false;
    }
    wake.notify_all();
    if (worker.joinable()) worker.join();
    {
      std::lock_guard lock(mutex);
      subscribers.clear();
      snapshots = {};
    }
  }

  void update(std::array<std::string, 3> state) {
    std::lock_guard lock(mutex);
    if (!running || state == snapshots) return;
    snapshots = std::move(state);
    wake.notify_one();
  }

  Response request(const HttpRequest& http, uint32_t peer, size_t service, Clock::time_point grant) {
    if (!http.body.empty()) return {400, {}};
    const auto& headers = http.events;
    const bool unsubscribe = http.parser.method == HTTP_UNSUBSCRIBE;
    const auto available = std::chrono::duration_cast<std::chrono::seconds>(grant - Clock::now()).count();
    const auto seconds = std::min(eventTimeout(headers.timeout),
        static_cast<unsigned>(std::clamp<int64_t>(available, 0, 1800)));
    std::lock_guard lock(mutex);
    if (!running) return {503, {}};
    expire();
    if (headers.sid) {
      if (headers.callback || headers.nt || (unsubscribe && headers.timeout)) return {400, {}};
      auto entry = std::find_if(subscribers.begin(), subscribers.end(), [&](const auto& item) {
        return item.sid == trim(*headers.sid) && item.service == service &&
            item.callback.endpoint.sin_addr.s_addr == peer;
      });
      if (entry == subscribers.end()) return {412, {}};
      if (unsubscribe) {
        subscribers.erase(entry);
        return {200, {}};
      }
      if (!seconds) return {400, {}};
      entry->expires = Clock::now() + std::chrono::seconds(seconds);
      return accepted(entry->sid, seconds);
    }
    if (unsubscribe || !headers.callback || !headers.nt || lower(trim(*headers.nt)) != "upnp:event") return {412, {}};
    auto endpoint = parseCallback(*headers.callback, peer);
    if (!endpoint) return {412, {}};
    if (!seconds) return {400, {}};
    if (subscribers.size() >= 12) return {503, {}};
    const auto sid = subscriptionId();
    subscribers.push_back({sid, service, std::move(*endpoint), Clock::now() + std::chrono::seconds(seconds)});
    auto response = accepted(sid, seconds);
    response.initial = sid;
    return response;
  }

  void activate(const std::string& sid, bool delivered) {
    if (sid.empty()) return;
    std::lock_guard lock(mutex);
    const auto entry = std::find_if(subscribers.begin(), subscribers.end(), [&](const auto& item) {
      return item.sid == sid;
    });
    if (entry == subscribers.end()) return;
    if (!delivered) subscribers.erase(entry);
    else { entry->active = true; wake.notify_one(); }
  }

private:
  struct Subscriber {
    std::string sid;
    size_t service;
    Callback callback;
    Clock::time_point expires;
    bool active = false;
    uint32_t sequence = 0;
    std::string previous{};
  };

  static Response accepted(const std::string& sid, unsigned seconds) {
    return {200, {}, "SID: " + sid + "\r\nTIMEOUT: Second-" + std::to_string(seconds) + "\r\n"};
  }

  void expire() {
    const auto now = Clock::now();
    std::erase_if(subscribers, [&](const auto& item) { return item.expires <= now; });
  }

  bool notify(const Subscriber& item) const {
    Socket socket{::socket(AF_INET, SOCK_STREAM, 0)};
    if (socket.fd < 0) return false;
    nonblocking(socket.fd);
    const auto end = Clock::now() + 800ms;
    if (::connect(socket.fd, reinterpret_cast<const sockaddr*>(&item.callback.endpoint),
        sizeof(item.callback.endpoint)) != 0) {
      if (errno != EINPROGRESS || !socketReady(running, socket.fd, POLLOUT, end)) return false;
      int error = 0;
      socklen_t size = sizeof(error);
      if (getsockopt(socket.fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0 || error) return false;
    }
    const auto message = "NOTIFY " + item.callback.path + " HTTP/1.1\r\nHOST: " + item.callback.host +
        "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " +
        item.sid + "\r\nSEQ: " + std::to_string(item.sequence) + "\r\nConnection: close\r\nContent-Length: " +
        std::to_string(item.previous.size()) + "\r\n\r\n" + item.previous;
    if (!sendAll(running, socket.fd, message, end)) return false;
    std::string reply;
    while (reply.size() < 64 && socketReady(running, socket.fd, POLLIN, end)) {
      char buffer[64];
      const auto received = recv(socket.fd, buffer, sizeof(buffer), 0);
      if (received < 0 && (errno == EAGAIN || errno == EINTR)) continue;
      if (received <= 0) return false;
      reply.append(buffer, static_cast<size_t>(received));
      if (reply.size() >= 13) {
        return (reply.starts_with("HTTP/1.1 200") || reply.starts_with("HTTP/1.0 200")) &&
            (reply[12] == ' ' || reply[12] == '\r');
      }
    }
    return false;
  }

  void run() noexcept {
    try {
      std::unique_lock lock(mutex);
      while (running) {
        expire();
        auto entry = std::find_if(subscribers.begin(), subscribers.end(), [&](const auto& item) {
          return item.active && !snapshots[item.service].empty() && snapshots[item.service] != item.previous;
        });
        if (entry == subscribers.end()) {
          wake.wait_for(lock, 100ms);
          continue;
        }
        entry->previous = snapshots[entry->service];
        const auto work = *entry;
        if (++entry->sequence == 0) entry->sequence = 1;
        std::rotate(entry, entry + 1, subscribers.end());
        lock.unlock();
        // An unavailable subscriber can still receive the next state before expiry.
        try { notify(work); } catch (const std::runtime_error&) {}
        lock.lock();
      }
    } catch (...) {
      running = false;
    }
  }

  std::atomic<bool> running{false};
  std::thread worker;
  std::mutex mutex;
  std::condition_variable wake;
  std::vector<Subscriber> subscribers;
  std::array<std::string, 3> snapshots;
};

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
  std::thread discoveryWorker;
  Socket listener;
  Socket ssdp;
  Events events;
  std::mutex mutex;
  std::condition_variable changed;
  unsigned int sequence = 0;
  unsigned int pendingId = 0;
  std::string pending;
  bool requireApproval = false;
  unsigned int approvalId = 0;
  std::optional<bool> approvalAnswer;
  std::string approvalEvent;
  struct Decision { uint32_t peer; Clock::time_point expires; bool allow; };
  std::vector<Decision> decisions;
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

  unsigned int nextId() {
    if (++sequence == 0) ++sequence;
    return sequence;
  }

  std::optional<Clock::time_point> authorize(int fd, uint32_t peer) {
    if (!requireApproval) return Clock::time_point::max();
    std::unique_lock lock(mutex);
    if (!active) return {};
    const auto now = Clock::now();
    std::erase_if(decisions, [&](const auto& item) { return item.expires <= now; });
    const auto known = std::find_if(decisions.begin(), decisions.end(), [peer](const auto& item) {
      return item.peer == peer;
    });
    if (known != decisions.end()) return known->allow ? std::optional{known->expires} : std::nullopt;

    char ip[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &peer, ip, sizeof(ip))) return {};
    approvalId = nextId();
    approvalEvent = "{\"approval\":{\"id\":" + std::to_string(approvalId) + ",\"peer\":" + json(ip) + "}}";
    const auto deadline = now + 25s;
    bool disconnected = false;
    while (active && !approvalAnswer && Clock::now() < deadline) {
      pollfd client{fd, 0, 0};
      if (::poll(&client, 1, 0) > 0 && (client.revents & (POLLHUP | POLLERR | POLLNVAL))) {
        disconnected = true;
        break;
      }
      changed.wait_for(lock, 100ms);
    }
    const bool allow = active && approvalAnswer.value_or(false);
    approvalId = 0;
    approvalAnswer.reset();
    approvalEvent = "{\"approval\":null}";
    if (!active || disconnected) return {};
    const auto expires = Clock::now() + (allow ? 5min : 30s);
    if (decisions.size() >= 16) decisions.erase(decisions.begin());
    decisions.push_back({peer, expires, allow});
    return allow ? std::optional{expires} : std::nullopt;
  }

  Response dispatch(const HttpRequest& request) {
    std::unique_lock lock(mutex);
    pendingId = nextId();
    pending = "{\"id\":" + std::to_string(pendingId) + ",\"method\":" +
        json(llhttp_method_name(static_cast<llhttp_method_t>(request.parser.method))) +
        ",\"path\":" + json(request.path) + ",\"soapAction\":" + json(request.soapAction) +
        ",\"contentType\":" + json(request.contentType) + ",\"body\":" + json(request.body) + "}";
    changed.wait_for(lock, 1500ms, [this] { return !active || reply.status; });
    pending.clear();
    pendingId = 0;
    auto response = std::exchange(reply, {0, {}});
    return response.status ? std::move(response) : Response{503, {}};
  }

  void serve(int fd, uint32_t peer) {
    HttpRequest request;
    Response response{400, {}};
    const auto end = Clock::now() + 1500ms;
    size_t bytes = 0;
    char input[4096];
    while (socketReady(active, fd, POLLIN, end)) {
      const auto count = recv(fd, input, sizeof(input), 0);
      if (count < 0 && (errno == EAGAIN || errno == EINTR)) continue;
      if (count <= 0) return;
      bytes += static_cast<size_t>(count);
      if (bytes > MaxRequest) { response.status = 413; break; }
      const auto parsed = llhttp_execute(&request.parser, input, static_cast<size_t>(count));
      if (request.ready && parsed == HPE_PAUSED) {
        const auto method = request.parser.method;
        const bool event = method == HTTP_SUBSCRIBE || method == HTTP_UNSUBSCRIBE;
        const bool control = method == HTTP_POST && request.path.starts_with("/ctl/");
        const auto grant = (event || control) ? authorize(fd, peer) :
            std::optional{Clock::time_point::max()};
        if (!grant) {
          response = {403, {}};
        } else if (event) {
          constexpr std::string_view paths[] = {"/evt/avt", "/evt/rcs", "/evt/cmn"};
          const auto path = std::find(std::begin(paths), std::end(paths), request.path);
          response = path == std::end(paths) ? Response{404, {}} :
              events.request(request, peer, static_cast<size_t>(path - std::begin(paths)), *grant);
        } else {
          response = dispatch(request);
        }
        break;
      }
      if (parsed != HPE_OK) break;
    }
    std::string output = "HTTP/1.1 " + std::to_string(response.status) + " ";
    output += reason(response.status);
    output += "\r\nSERVER: ";
    output += Server;
    output += "\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nConnection: close\r\nContent-Length: " +
        std::to_string(response.body.size()) + "\r\n" + response.headers + "\r\n";
    if (request.parser.method != HTTP_HEAD) output += response.body;
    const bool delivered = sendAll(active, fd, output, Clock::now() + 500ms);
    events.activate(response.initial, delivered);
  }

  void runDiscovery() noexcept {
    const auto group = address(htonl(SsdpGroup), SsdpPort);
    auto nextAnnounce = Clock::now();
    auto nextSearch = nextAnnounce;
    try {
      while (active) {
        const auto now = Clock::now();
        if (now >= nextAnnounce) {
          discovery(group, {}, false, false);
          nextAnnounce = now + 10min;
        }
        pollfd item{ssdp.fd, POLLIN, 0};
        const auto count = ::poll(&item, 1, 50);
        if (count < 0 && errno != EINTR) break;
        if (count <= 0 || !(item.revents & POLLIN)) continue;
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
      discovery(group, {}, false, true);
    } catch (...) {
      // An interface can disappear while the HTTP listener is still serving.
    }
  }

  void run() noexcept {
    try {
      while (active) {
        pollfd item{listener.fd, POLLIN, 0};
        const auto count = ::poll(&item, 1, 50);
        if (count < 0 && errno != EINTR) break;
        if (count <= 0 || !(item.revents & POLLIN)) continue;
        sockaddr_in peer{};
        socklen_t size = sizeof(peer);
        Socket client{::accept(listener.fd, reinterpret_cast<sockaddr*>(&peer), &size)};
        if (client.fd >= 0 && (sameLan(peer.sin_addr.s_addr) || (ntohl(peer.sin_addr.s_addr) >> 24) == 127)) {
          nonblocking(client.fd);
          serve(client.fd, peer.sin_addr.s_addr);
        }
      }
    } catch (...) {
      // poll() reports shutdown to the screen so it can release the player.
    }
    active = false;
  }
};

DlnaTransport::DlnaTransport() : impl(std::make_unique<Impl>()) {}
DlnaTransport::~DlnaTransport() { stop(); }

uint16_t DlnaTransport::start(const std::string& uuid, bool loopbackOnly, bool requireApproval) {
  if (impl->worker.joinable()) throw std::runtime_error("DLNA receiver already started");
  impl->uuid = uuid;
  impl->requireApproval = requireApproval;
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
    impl->events.start();
    impl->worker = std::thread([this] { impl->run(); });
    if (impl->ssdp.fd >= 0) impl->discoveryWorker = std::thread([this] { impl->runDiscovery(); });
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
  if (impl->discoveryWorker.joinable()) impl->discoveryWorker.join();
  impl->events.stop();
  {
    std::lock_guard lock(impl->mutex);
    impl->decisions.clear();
    impl->approvalEvent.clear();
  }
  impl->ssdp.reset();
  impl->listener.reset();
}

std::string DlnaTransport::poll() {
  std::lock_guard lock(impl->mutex);
  if (!impl->active) throw std::runtime_error("DLNA receiver stopped");
  if (!impl->approvalEvent.empty()) return std::exchange(impl->approvalEvent, {});
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


void DlnaTransport::publish(std::string transport, std::string rendering, std::string connection) {
  if (transport.size() > MaxResponse || rendering.size() > MaxResponse || connection.size() > MaxResponse)
    throw std::invalid_argument("DLNA event too large");
  impl->events.update({std::move(transport), std::move(rendering), std::move(connection)});
}


void DlnaTransport::answerApproval(double id, bool allow) {
  std::lock_guard lock(impl->mutex);
  if (!impl->active || !impl->approvalId || id != impl->approvalId || impl->approvalAnswer.has_value()) return;
  impl->approvalAnswer = allow;
  impl->changed.notify_one();
}
