#include "FCastTransport.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t MaxFrame = 32000;
constexpr size_t MaxQueue = 16;
constexpr auto Deadline = std::chrono::seconds(5);

bool utf8(const std::string& s) {
  size_t i = 0;
  while (i < s.size()) {
    const auto first = static_cast<uint8_t>(s[i++]);
    if (first < 0x80) continue;
    int count;
    uint32_t value, minimum;
    if (first >= 0xc2 && first <= 0xdf) {count = 1; value = first & 31; minimum = 0x80;}
    else if (first >= 0xe0 && first <= 0xef) {count = 2; value = first & 15; minimum = 0x800;}
    else if (first >= 0xf0 && first <= 0xf4) {count = 3; value = first & 7; minimum = 0x10000;}
    else return false;
    if (s.size() - i < static_cast<size_t>(count)) return false;
    while (count--) {
      const auto next = static_cast<uint8_t>(s[i++]);
      if ((next & 0xc0) != 0x80) return false;
      value = (value << 6) | (next & 63);
    }
    if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
  }
  return true;
}

std::string quote(const std::string& s) {
  constexpr char hex[] = "0123456789abcdef";
  std::string out = "\"";
  for (const unsigned char c : s) {
    if (c == '"' || c == '\\') {out += '\\'; out += c;}
    else if (c < 32) {out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15];}
    else out += c;
  }
  return out + '"';
}

std::string frame(uint8_t opcode, const std::string& body) {
  const uint32_t size = static_cast<uint32_t>(body.size() + 1);
  if (size > MaxFrame) throw std::invalid_argument("FCast frame too large");
  std::string result;
  for (int i = 0; i < 4; ++i) result += static_cast<char>((size >> (8 * i)) & 255);
  result += static_cast<char>(opcode);
  return result + body;
}

bool localPeer(uint32_t address) {
  const auto ip = ntohl(address);
  return (ip >> 24) == 127 || (ip >> 24) == 10 ||
         (ip >> 20) == 0xac1 || (ip >> 16) == 0xc0a8;
}

void nonblocking(int fd) {
  const auto flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0)
    throw std::runtime_error("FCast nonblocking socket failed");
#ifdef SO_NOSIGPIPE
  const int yes = 1;
  setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof(yes));
#endif
}
}

struct FCastTransport::Impl {
  mutable std::mutex mutex;
  std::thread worker;
  std::atomic<bool> stopped{true};
  int listener = -1;
  int client = -1;
  int port = 0;
  uint64_t generation = 0;
  std::deque<std::string> commands;
  std::deque<std::string> output;
  std::string playback = "{\"generationTime\":0,\"time\":0,\"duration\":0,\"state\":0,\"speed\":1}";
  std::string volume = "{\"generationTime\":0,\"volume\":1}";

  bool matches(double id) const {
    return client >= 0 && std::isfinite(id) && id == static_cast<double>(generation);
  }
  void closeClient() {
    std::lock_guard lock(mutex);
    if (client >= 0) {::close(client); client = -1;}
    commands.clear(); output.clear();
  }
  void run() noexcept {
    std::vector<uint8_t> input;
    size_t sent = 0;
    int count = 0;
    auto window = Clock::now(), partial = window, writeStarted = window, tick = window;
    try {
      while (!stopped) {
        int fd;
        short events = POLLIN;
        {
          std::lock_guard lock(mutex);
          fd = client;
          if (!output.empty()) events |= POLLOUT;
        }
        pollfd fds[] = {{listener, POLLIN, 0}, {fd, events, 0}};
        const auto ready = ::poll(fds, 2, 50);
        if (ready < 0) {if (errno == EINTR) continue; break;}
        if (fds[0].revents & POLLIN) {
          sockaddr_in peer{};
          socklen_t length = sizeof(peer);
          const int incoming = ::accept(listener, reinterpret_cast<sockaddr*>(&peer), &length);
          if (incoming >= 0) {
            std::lock_guard lock(mutex);
            if (client >= 0 || !localPeer(peer.sin_addr.s_addr)) ::close(incoming);
            else {
              try {nonblocking(incoming);} catch (...) {::close(incoming); throw;}
              const int yes = 1;
              setsockopt(incoming, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
              client = incoming;
              ++generation;
              commands.clear(); output.clear();
              input.clear(); sent = 0; count = 0;
              window = tick = writeStarted = Clock::now();
              output.push_back(frame(11, "{\"version\":2}"));
            }
          }
        }
        if (fd < 0) continue;
        bool drop = fds[1].revents & (POLLERR | POLLHUP | POLLNVAL);
        if (fds[1].revents & POLLIN) {
          uint8_t data[4096];
          const auto length = ::recv(fd, data, sizeof(data), 0);
          if (length == 0 || (length < 0 && errno != EAGAIN && errno != EINTR)) drop = true;
          if (length > 0) {
            if (input.empty()) partial = Clock::now();
            input.insert(input.end(), data, data + length);
            while (input.size() >= 4 && !drop) {
              uint32_t size = 0;
              for (int i = 0; i < 4; ++i) size |= static_cast<uint32_t>(input[i]) << (8 * i);
              if (size < 1 || size > MaxFrame) {drop = true; break;}
              if (input.size() < size + 4) break;
              const auto opcode = input[4];
              const std::string body(input.begin() + 5, input.begin() + 4 + size);
              if (Clock::now() - window >= std::chrono::seconds(1)) {window = Clock::now(); count = 0;}
              if (++count > 40 || !utf8(body)) {drop = true; break;}
              std::lock_guard lock(mutex);
              if (opcode == 12) {
                if (output.size() >= MaxQueue) {drop = true; break;}
                output.push_back(frame(13, ""));
              } else if (opcode != 13) {
                if (commands.size() >= MaxQueue) {drop = true; break;}
                commands.push_back("{\"client\":" + std::to_string(generation) +
                    ",\"opcode\":" + std::to_string(opcode) + ",\"body\":" + quote(body) + "}");
              }
              input.erase(input.begin(), input.begin() + 4 + size);
              partial = Clock::now();
            }
            if (input.size() > MaxFrame + 4) drop = true;
          }
        }
        if (!input.empty() && Clock::now() - partial > Deadline) drop = true;
        {
          std::lock_guard lock(mutex);
          if (Clock::now() - tick >= std::chrono::seconds(1)) {
            tick = Clock::now();
            if (output.size() + 2 > MaxQueue) drop = true;
            else {output.push_back(frame(6, playback)); output.push_back(frame(7, volume));}
          }
          if (output.empty()) writeStarted = Clock::now();
          else if (fds[1].revents & POLLOUT) {
            const auto& packet = output.front();
#ifdef MSG_NOSIGNAL
            constexpr int flags = MSG_NOSIGNAL;
#else
            constexpr int flags = 0;
#endif
            const auto n = ::send(fd, packet.data() + sent, packet.size() - sent, flags);
            if (n > 0) {
              sent += static_cast<size_t>(n);
              if (sent == packet.size()) {output.pop_front(); sent = 0; writeStarted = Clock::now();}
            } else if (n == 0 || (errno != EAGAIN && errno != EINTR)) drop = true;
          }
          if (!output.empty() && Clock::now() - writeStarted > Deadline) drop = true;
        }
        if (drop) {closeClient(); input.clear(); sent = 0;}
      }
    } catch (...) {stopped = true;}
    closeClient();
  }
};

FCastTransport::FCastTransport() : impl(std::make_unique<Impl>()) {}
FCastTransport::~FCastTransport() {stop();}
int FCastTransport::start(int requestedPort, bool loopbackOnly) {
  if (impl->worker.joinable()) throw std::runtime_error("FCast already started");
  const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) throw std::runtime_error("FCast socket failed");
  try {
    const int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    nonblocking(fd);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(requestedPort));
    address.sin_addr.s_addr = htonl(loopbackOnly ? INADDR_LOOPBACK : INADDR_ANY);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) || ::listen(fd, 1))
      throw std::runtime_error("FCast port unavailable");
    socklen_t length = sizeof(address);
    if (::getsockname(fd, reinterpret_cast<sockaddr*>(&address), &length))
      throw std::runtime_error("FCast socket address failed");
    impl->port = ntohs(address.sin_port);
    impl->listener = fd;
    impl->stopped = false;
    impl->worker = std::thread([this] {impl->run();});
    return impl->port;
  } catch (...) {::close(fd); impl->listener = -1; impl->stopped = true; throw;}
}
void FCastTransport::stop() {
  impl->stopped = true;
  if (impl->worker.joinable()) impl->worker.join();
  if (impl->listener >= 0) {::close(impl->listener); impl->listener = -1;}
}
std::string FCastTransport::pollCommand() {
  std::lock_guard lock(impl->mutex);
  if (impl->stopped) throw std::runtime_error("FCast listener stopped");
  if (impl->commands.empty()) return "";
  auto result = std::move(impl->commands.front()); impl->commands.pop_front(); return result;
}
bool FCastTransport::isClient(double id) const {std::lock_guard lock(impl->mutex); return impl->matches(id);}
void FCastTransport::disconnect(double id) {
  std::lock_guard lock(impl->mutex);
  if (impl->matches(id)) ::shutdown(impl->client, SHUT_RDWR);
}
void FCastTransport::error(double id, const std::string& message) {
  std::lock_guard lock(impl->mutex);
  if (!impl->matches(id)) return;
  if (message.size() > 512 || !utf8(message) || impl->output.size() >= MaxQueue)
    ::shutdown(impl->client, SHUT_RDWR);
  else impl->output.push_back(frame(9, "{\"message\":" + quote(message) + "}"));
}
void FCastTransport::update(const std::string& playback, const std::string& volume) {
  if (playback.size() > 2048 || volume.size() > 2048) throw std::invalid_argument("FCast state too large");
  std::lock_guard lock(impl->mutex);
  impl->playback = playback; impl->volume = volume;
}
