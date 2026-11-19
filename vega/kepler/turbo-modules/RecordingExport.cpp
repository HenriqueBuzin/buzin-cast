#include "RecordingExport.h"
#include "LocalTransfer.h"
#include "llhttp.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <fcntl.h>
#include <poll.h>
#include <stdexcept>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
using namespace LocalTransfer;
using namespace std::chrono_literals;

struct ExportRequest {
  llhttp_t parser{};
  std::string route, host, field, value;
  bool complete = false, foundHost = false;

  ExportRequest() {
    static const auto callbacks = [] {
      llhttp_settings_t settings;
      llhttp_settings_init(&settings);
      settings.on_url = [](llhttp_t* parser, const char* data, size_t size) {
        self(parser).route.append(data, size);
        return 0;
      };
      settings.on_header_field = [](llhttp_t* parser, const char* data, size_t size) {
        self(parser).field.append(data, size);
        return 0;
      };
      settings.on_header_value = [](llhttp_t* parser, const char* data, size_t size) {
        self(parser).value.append(data, size);
        return 0;
      };
      settings.on_header_value_complete = [](llhttp_t* parser) {
        auto& r = self(parser);
        for (auto& c : r.field) {
          if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        }
        if (r.field == "host") {
          if (r.foundHost) return static_cast<int>(HPE_USER);
          r.foundHost = true;
          r.host = std::move(r.value);
        }
        r.field.clear();
        r.value.clear();
        return 0;
      };
      settings.on_headers_complete = [](llhttp_t* parser) {
        if (parser->content_length || (parser->flags & (F_CHUNKED | F_TRANSFER_ENCODING | F_UPGRADE))) return -1;
        self(parser).complete = true;
        return static_cast<int>(HPE_PAUSED);
      };
      return settings;
    }();
    llhttp_init(&parser, HTTP_REQUEST, &callbacks);
    parser.data = this;
  }

  static ExportRequest& self(llhttp_t* parser) {return *static_cast<ExportRequest*>(parser->data);}

  bool readFrom(const std::atomic<bool>& active, int fd, Clock::time_point end) {
    std::array<char, 2048> bytes;
    size_t received = 0;
    while (waitFor(active, fd, POLLIN, end)) {
      const auto count = recv(fd, bytes.data(), bytes.size(), 0);
      if (count < 0 && (errno == EAGAIN || errno == EINTR)) continue;
      if (count <= 0) break;
      received += static_cast<size_t>(count);
      if (received > 8192) break;
      const auto outcome = llhttp_execute(&parser, bytes.data(), static_cast<size_t>(count));
      if (complete && outcome == HPE_PAUSED) return true;
      if (outcome != HPE_OK) break;
    }
    return false;
  }
};

bool reply(const std::atomic<bool>& active, int fd, std::string_view status,
           uint64_t size = 0, const std::string& filename = {}) {
  auto header = "HTTP/1.1 " + std::string(status) +
      "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
      "\r\nReferrer-Policy: no-referrer\r\nAccept-Ranges: none"
      "\r\nContent-Length: " + std::to_string(size) + "\r\n";
  if (!filename.empty()) header += "Content-Type: video/mp4\r\nContent-Disposition: attachment; filename=\"" + filename + "\"\r\n";
  header += "\r\n";
  return sendAll(active, fd, header, Clock::now() + 2s);
}
}

RecordingExport::~RecordingExport() {stop();}

std::string RecordingExport::start(const std::string& filename, const std::string& path) {
  stop();
  // The bridge resolves only validated, finalized library entries.
  Socket file{open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)};
  struct stat info{};
  if (file.fd < 0 || fstat(file.fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0)
    throw std::runtime_error("Recording could not be opened");
  const auto route = randomPath(8);
  std::string host;
  Socket listener{LocalTransfer::listen(host)};
  const auto link = "http://" + host + route;
  const auto bytes = static_cast<uint64_t>(info.st_size);
  phase = Phase::Ready;
  active = true;
  try {
    worker = std::thread([this, socketFd = listener.fd, fileFd = file.fd, bytes, filename, route, host] {
      const Socket socket{socketFd}, recording{fileFd};
      try {phase = serve(socket.fd, recording.fd, bytes, filename, route, host);}
      catch (...) {phase = Phase::Error;}
      active = false;
    });
    listener.fd = file.fd = -1;
  } catch (...) {
    active = false;
    phase = Phase::Error;
    throw;
  }
  return link;
}

RecordingExport::Phase RecordingExport::serve(int listener, int file, uint64_t size, const std::string& filename,
                                               const std::string& route, const std::string& host) {
  const auto expires = Clock::now() + 2min;
  while (waitFor(active, listener, POLLIN, expires)) {
    const Socket client{LocalTransfer::accept(listener)};
    if (client.fd < 0) continue;
    ExportRequest request;
    if (!request.readFrom(active, client.fd, std::min(expires, Clock::now() + 3s))) {
      reply(active, client.fd, "400 Bad Request");
      continue;
    }
    if (request.host != host || request.route != route) {
      reply(active, client.fd, "404 Not Found");
      continue;
    }
    const bool head = request.parser.method == HTTP_HEAD;
    if (!head && request.parser.method != HTTP_GET) {
      reply(active, client.fd, "405 Method Not Allowed");
      continue;
    }
    if (!reply(active, client.fd, "200 OK", size, filename) || head) continue;

    phase = Phase::Sending;
    off_t offset = 0;
    const auto deadline = Clock::now() + 30min;
    std::array<char, 64 * 1024> buffer;
    while (active && static_cast<uint64_t>(offset) < size) {
      const auto count = pread(file, buffer.data(), std::min<uint64_t>(buffer.size(), size - offset), offset);
      if (count < 0 && errno == EINTR) continue;
      if (count <= 0) return Phase::Error;
      if (!sendAll(active, client.fd, {buffer.data(), static_cast<size_t>(count)},
                   std::min(deadline, Clock::now() + 8s))) break;
      offset += count;
    }
    if (static_cast<uint64_t>(offset) == size) return Phase::Sent;
    phase = Phase::Ready;
  }
  return active ? Phase::Expired : Phase::Idle;
}

std::string RecordingExport::state() const {
  switch (phase.load()) {
    case Phase::Ready: return "ready";
    case Phase::Sending: return "sending";
    case Phase::Sent: return "sent";
    case Phase::Expired: return "expired";
    case Phase::Error: return "error";
    default: return "idle";
  }
}

void RecordingExport::stop() noexcept {
  active = false;
  if (worker.joinable()) worker.join();
  phase = Phase::Idle;
}
