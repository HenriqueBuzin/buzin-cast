#include "SubtitleUpload.h"
#include "llhttp.h"

#include <algorithm>
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <poll.h>
#include <stdexcept>
#include <string_view>
#include <sys/socket.h>
#include <unistd.h>
#include <utility>

namespace {
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
constexpr size_t MaxSubtitle = 2 * 1024 * 1024;
constexpr size_t MaxHeaders = 8192;

constexpr std::string_view Page = R"HTML(<!doctype html>
<html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Legendas do Buzin Cast</title>
<h1>Enviar legenda</h1>
<p><label>Arquivo SRT ou WebVTT, até 2 MB <input type="file" accept=".srt,.vtt,text/vtt"></label></p>
<button type="button">Enviar à TV</button>
<p role="status"></p>
<script>
const input = document.querySelector('input');
const button = document.querySelector('button');
const status = document.querySelector('[role=status]');
button.onclick = async () => {
  const file = input.files[0];
  if (!file || !file.size || file.size > 2097152) {
    status.textContent = 'Selecione um arquivo de até 2 MB.';
    return;
  }
  button.disabled = true;
  try {
    const response = await fetch(location.pathname, {method:'POST',
      headers:{'Content-Type':'application/octet-stream'}, body:file});
    if (response.ok) {
      status.textContent = 'Arquivo enviado. Confira o resultado na TV.';
      return;
    }
  } catch {}
  status.textContent = 'Envio indisponível. Confira o endereço na TV.';
  button.disabled = false;
};
</script></html>)HTML";

struct Socket {
  int fd;
  ~Socket() { if (fd >= 0) close(fd); }
};

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
  if (!*address) throw std::runtime_error("No private IPv4 interface for subtitle import");
  return address;
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

void reply(const std::atomic<bool>& active, int fd, std::string_view status, std::string_view body = {}) {
  auto packet = "HTTP/1.1 " + std::string(status) +
      "\r\nConnection: close\r\nContent-Type: text/html; charset=utf-8"
      "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
      "\r\nReferrer-Policy: no-referrer"
      "\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'"
      "\r\nContent-Length: " + std::to_string(body.size()) + "\r\n\r\n";
  packet += body;
  const auto end = Clock::now() + 1s;
  for (size_t offset = 0; offset < packet.size() && waitFor(active, fd, POLLOUT, end);) {
#ifdef MSG_NOSIGNAL
    const auto sent = send(fd, packet.data() + offset, packet.size() - offset, MSG_NOSIGNAL);
#else
    const auto sent = send(fd, packet.data() + offset, packet.size() - offset, 0);
#endif
    if (sent < 0 && (errno == EINTR || errno == EAGAIN)) continue;
    if (sent <= 0) break;
    offset += static_cast<size_t>(sent);
  }
}

struct Request {
  llhttp_t parser{};
  const std::string& expectedPath;
  const std::string& expectedHost;
  std::string path;
  std::string header;
  std::string value;
  std::string host;
  std::string origin;
  std::string type;
  std::string body;
  size_t headerBytes = 0;
  std::string_view status = "400 Bad Request";
  bool complete = false;

  Request(const std::string& path, const std::string& host) : expectedPath(path), expectedHost(host) {
    static const auto callbacks = [] {
      llhttp_settings_t settings;
      llhttp_settings_init(&settings);
      settings.on_url = [](llhttp_t* p, const char* data, size_t size) {
        return self(p).append(self(p).path, data, size);
      };
      settings.on_header_field = [](llhttp_t* p, const char* data, size_t size) {
        return self(p).append(self(p).header, data, size);
      };
      settings.on_header_value = [](llhttp_t* p, const char* data, size_t size) {
        return self(p).append(self(p).value, data, size);
      };
      settings.on_header_value_complete = [](llhttp_t* p) {
        auto& r = self(p);
        for (auto& c : r.header) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        auto* destination = r.header == "content-type" ? &r.type :
            r.header == "host" ? &r.host : r.header == "origin" ? &r.origin : nullptr;
        if (destination) {
          if (!destination->empty()) return static_cast<int>(HPE_USER);
          *destination = std::move(r.value);
        }
        r.header.clear();
        r.value.clear();
        return 0;
      };
      settings.on_headers_complete = [](llhttp_t* p) {
        auto& r = self(p);
        if (r.host != r.expectedHost || (!r.origin.empty() && r.origin != "http://" + r.expectedHost))
          r.status = "403 Forbidden";
        else if (r.path != r.expectedPath) r.status = "404 Not Found";
        else if (p->method == HTTP_GET && !p->content_length && !(p->flags & F_CHUNKED)) return 0;
        else if (p->method != HTTP_POST) r.status = "405 Method Not Allowed";
        else if (r.type != "application/octet-stream") r.status = "415 Unsupported Media Type";
        else if (p->content_length > MaxSubtitle) r.status = "413 Content Too Large";
        else if (p->content_length && !(p->flags & F_CHUNKED)) return 0;
        return -1;
      };
      settings.on_body = [](llhttp_t* p, const char* data, size_t size) {
        auto& r = self(p);
        if (size > MaxSubtitle - r.body.size()) return static_cast<int>(HPE_USER);
        r.body.append(data, size);
        return 0;
      };
      settings.on_message_complete = [](llhttp_t* p) {
        self(p).complete = true;
        return static_cast<int>(HPE_PAUSED);
      };
      return settings;
    }();
    llhttp_init(&parser, HTTP_REQUEST, &callbacks);
    parser.data = this;
  }

  static Request& self(llhttp_t* parser) { return *static_cast<Request*>(parser->data); }
  int append(std::string& target, const char* data, size_t size) {
    if (size > MaxHeaders - headerBytes) return static_cast<int>(HPE_USER);
    headerBytes += size;
    target.append(data, size);
    return 0;
  }
};

std::string encode(const std::string& bytes) {
  std::string encoded(4 * ((bytes.size() + 2) / 3) + 1, '\0');
  const auto size = EVP_EncodeBlock(reinterpret_cast<unsigned char*>(encoded.data()),
      reinterpret_cast<const unsigned char*>(bytes.data()), static_cast<int>(bytes.size()));
  encoded.resize(static_cast<size_t>(size));
  return encoded;
}
}

SubtitleUpload::~SubtitleUpload() { stop(); }

std::string SubtitleUpload::start() {
  stop();
  const auto ip = lanAddress();
  unsigned char random[5];
  if (RAND_bytes(random, sizeof(random)) != 1) throw std::runtime_error("Could not generate an import link");
  std::string path = "/";
  constexpr char hex[] = "0123456789abcdef";
  for (const auto byte : random) {
    path += hex[byte >> 4];
    path += hex[byte & 15];
  }
  Socket socket{::socket(AF_INET, SOCK_STREAM, 0)};
  sockaddr_in address{};
  address.sin_family = AF_INET;
  socklen_t size = sizeof(address);
  if (socket.fd < 0 || fcntl(socket.fd, F_SETFL, O_NONBLOCK) < 0 || bind(socket.fd, reinterpret_cast<sockaddr*>(&address), size) != 0 ||
      listen(socket.fd, 4) != 0 || getsockname(socket.fd, reinterpret_cast<sockaddr*>(&address), &size) != 0)
    throw std::runtime_error("Could not start subtitle import");
  const auto host = ip + ":" + std::to_string(ntohs(address.sin_port));
  listener = std::exchange(socket.fd, -1);
  active = true;
  try {
    worker = std::thread([this, path, host] {
      try { serve(path, host); } catch (...) {}
      active = false;
    });
  } catch (...) { stop(); throw; }
  return "http://" + host + path;
}

void SubtitleUpload::serve(const std::string& path, const std::string& host) {
  const auto end = Clock::now() + 120s;
  while (waitFor(active, listener, POLLIN, end)) {
    sockaddr_in peer{};
    socklen_t size = sizeof(peer);
    Socket client{accept(listener, reinterpret_cast<sockaddr*>(&peer), &size)};
    if (client.fd < 0 || fcntl(client.fd, F_SETFL, O_NONBLOCK) < 0) continue;
#ifdef SO_NOSIGPIPE
    constexpr int enabled = 1;
    if (setsockopt(client.fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) != 0) continue;
#endif
    const auto ip = ntohl(peer.sin_addr.s_addr);
    if (!privateAddress(peer.sin_addr.s_addr) && (ip >> 24) != 127) continue;
    Request request(path, host);
    const auto deadline = std::min(end, Clock::now() + 6s);
    size_t received = 0;
    char input[8192];
    while (waitFor(active, client.fd, POLLIN, deadline)) {
      const auto count = recv(client.fd, input, sizeof(input), 0);
      if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
      if (count <= 0) break;
      received += static_cast<size_t>(count);
      if (received > MaxSubtitle + MaxHeaders) break;
      const auto parsed = llhttp_execute(&request.parser, input, static_cast<size_t>(count));
      if (request.complete) {
        if (request.parser.method == HTTP_GET) {
          reply(active, client.fd, "200 OK", Page);
        } else {
          reply(active, client.fd, "202 Accepted");
          std::lock_guard lock(mutex);
          upload = encode(request.body);
          return;
        }
        break;
      }
      if (parsed != HPE_OK) {
        reply(active, client.fd, request.status);
        break;
      }
    }
  }
}

std::string SubtitleUpload::poll() {
  std::lock_guard lock(mutex);
  if (!upload.empty()) return std::exchange(upload, {});
  return active ? "" : "expired";
}

void SubtitleUpload::stop() noexcept {
  active = false;
  if (worker.joinable()) worker.join();
  if (listener >= 0) close(std::exchange(listener, -1));
  std::lock_guard lock(mutex);
  upload.clear();
}
