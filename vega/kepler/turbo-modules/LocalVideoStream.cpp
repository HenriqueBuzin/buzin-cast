#include "LocalVideoStream.h"
#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <condition_variable>
#include <deque>
#include <fstream>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t MaxBytes = 8*1024*1024;
constexpr size_t MaxChunks = 2048;
void nonblocking(int fd) {
  const auto flags = fcntl(fd,F_GETFL,0);
  if (flags < 0 || fcntl(fd,F_SETFL,flags|O_NONBLOCK)) throw std::runtime_error("Local stream socket setup failed");
#ifdef SO_NOSIGPIPE
  const int one = 1;
  if (setsockopt(fd,SOL_SOCKET,SO_NOSIGPIPE,&one,sizeof(one))) throw std::runtime_error("Local stream signal setup failed");
#endif
}
std::string token() {
  uint8_t bytes[32];
  std::ifstream input("/dev/urandom",std::ios::binary);
  input.read(reinterpret_cast<char*>(bytes),sizeof(bytes));
  if (input.gcount() != sizeof(bytes)) throw std::runtime_error("Local stream token generation failed");
  std::string value;
  for (auto byte : bytes) {value += "0123456789abcdef"[byte>>4]; value += "0123456789abcdef"[byte&15];}
  return value;
}
}
struct LocalVideoStream::Impl {
  mutable std::mutex mutex;
  std::condition_variable changed;
  std::deque<std::vector<uint8_t>> queue;
  LocalVideoStreamState observation;
  std::atomic<bool> closing{true};
  bool ended = false;
  size_t queuedChunks = 0;
  int listener = -1;
  std::string path;
  std::thread worker;
  const char* sendFailure = "clientClosed";
  void fail(const char* error) {
    std::lock_guard lock(mutex);
    if (!closing && observation.error.empty()) observation.error = error;
    changed.notify_all();
  }
  bool send(int fd, const uint8_t* bytes, size_t size) {
    sendFailure = "clientClosed";
    size_t offset = 0;
    auto deadline = Clock::now()+std::chrono::seconds(3);
    while (!closing && offset < size) {
      pollfd item{fd,POLLOUT,0};
      const int status = poll(&item,1,25);
      if (status < 0) {if (errno == EINTR) continue; sendFailure = "socketFailed"; return false;}
      if (Clock::now() >= deadline) {sendFailure = "consumerTimeout"; return false;}
      if (!status) continue;
      if (item.revents&(POLLERR|POLLHUP|POLLNVAL)) return false;
#ifdef MSG_NOSIGNAL
      const auto count = ::send(fd,bytes+offset,size-offset,MSG_NOSIGNAL);
#else
      const auto count = ::send(fd,bytes+offset,size-offset,0);
#endif
      if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
      if (count <= 0) {if (count < 0 && errno != EPIPE && errno != ECONNRESET) sendFailure = "socketFailed"; return false;}
      offset += static_cast<size_t>(count); deadline = Clock::now()+std::chrono::seconds(3);
    }
    return offset == size;
  }
  bool text(int fd, const std::string& data) {return send(fd,reinterpret_cast<const uint8_t*>(data.data()),data.size());}
  bool request(int fd) {
    std::string header;
    const auto deadline = Clock::now()+std::chrono::seconds(3);
    while (!closing && header.find("\r\n\r\n") == std::string::npos) {
      pollfd item{fd,POLLIN,0}; const auto status = poll(&item,1,25);
      if (status < 0) {if (errno == EINTR) continue; return false;}
      if (Clock::now() >= deadline) return false;
      if (!status) continue;
      char bytes[512]; const auto count = recv(fd,bytes,sizeof(bytes),0);
      if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
      if (count <= 0 || header.size()+static_cast<size_t>(count) > 4096) return false;
      header.append(bytes,static_cast<size_t>(count));
    }
    if (closing) return false;
    const auto end = header.find("\r\n");
    const auto line = header.substr(0,end);
    const bool get = line == "GET "+path+" HTTP/1.1" || line == "GET "+path+" HTTP/1.0";
    const bool head = line == "HEAD "+path+" HTTP/1.1" || line == "HEAD "+path+" HTTP/1.0";
    if ((!get && !head) || header.find('\0') != std::string::npos) {
      text(fd,"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); return false;
    }
    size_t cursor = end+2;
    while (cursor < header.find("\r\n\r\n")) {
      const auto next = header.find("\r\n",cursor);
      const auto row = header.substr(cursor,next-cursor);
      const auto colon = row.find(':');
      if (colon == std::string::npos || colon == 0 || row[0] == ' ' || row[0] == '\t') return false;
      auto name = row.substr(0,colon);
      for (auto& c : name) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
      auto value = row.substr(colon+1);
      const auto start = value.find_first_not_of(" \t"); value = start == std::string::npos ? "" : value.substr(start);
      if (name == "range" && value != "bytes=0-") {
        text(fd,"HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); return false;
      }
      if (name == "transfer-encoding" || (name == "content-length" && value != "0")) return false;
      cursor = next+2;
    }
    const std::string headers = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nCache-Control: no-store\r\nAccept-Ranges: none\r\nConnection: close\r\n";
    if (head) {text(fd,headers+"\r\n"); return false;}
    if (!text(fd,headers+"Transfer-Encoding: chunked\r\n\r\n")) return false;
    {std::lock_guard lock(mutex); observation.connected = true; changed.notify_all();}
    return true;
  }
  void stream(int fd) {
    for (;;) {
      std::vector<uint8_t> bytes;
      {
        std::unique_lock lock(mutex);
        changed.wait_for(lock,std::chrono::milliseconds(25),[&] {return closing || !queue.empty() || ended;});
        if (closing) return;
        if (queue.empty()) {
          if (!ended) continue;
          lock.unlock();
          if (!text(fd,"0\r\n\r\n")) {fail(sendFailure); return;}
          std::lock_guard done(mutex); observation.complete = true; changed.notify_all(); return;
        }
        bytes = std::move(queue.front()); queue.pop_front();
        // queuedBytes includes this in-flight chunk until every byte was sent.
      }
      char size[32]; const auto length = std::snprintf(size,sizeof(size),"%zx\r\n",bytes.size());
      if (length <= 0 || !text(fd,std::string(size,static_cast<size_t>(length))) ||
          !send(fd,bytes.data(),bytes.size()) || !text(fd,"\r\n")) {fail(sendFailure); return;}
      {std::lock_guard lock(mutex); --queuedChunks; observation.queuedBytes -= bytes.size(); observation.sentBytes += bytes.size(); changed.notify_all();}
    }
  }
  void run() noexcept {
    try {
      while (!closing) {
        pollfd item{listener,POLLIN,0}; const auto status = poll(&item,1,25);
        if (status < 0) {if (errno == EINTR) continue; fail("listenerFailed"); return;}
        if (!status) continue;
        sockaddr_in peer{}; socklen_t size = sizeof(peer);
        const int fd = accept(listener,reinterpret_cast<sockaddr*>(&peer),&size);
        if (fd < 0) {if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue; fail("listenerFailed"); return;}
        bool active = false;
        try {nonblocking(fd); if ((ntohl(peer.sin_addr.s_addr)>>24) == 127) active = request(fd); if (active) stream(fd);}
        catch (...) {close(fd); throw;}
        close(fd); if (active) return;
      }
    } catch (...) {fail("streamFailed");}
  }
};
LocalVideoStream::LocalVideoStream() : impl(std::make_unique<Impl>()) {}
LocalVideoStream::~LocalVideoStream() {stop();}
std::string LocalVideoStream::start() {
  stop();
  impl->path = "/"+token()+".mp4";
  const int fd = socket(AF_INET,SOCK_STREAM,0);
  if (fd < 0) throw std::runtime_error("Could not create local media listener");
  try {
    nonblocking(fd);
    const int one = 1; if (setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one))) throw std::runtime_error("Local media socket configuration failed");
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address)) || listen(fd,4)) throw std::runtime_error("Could not bind local media listener");
    socklen_t size = sizeof(address);
    if (getsockname(fd,reinterpret_cast<sockaddr*>(&address),&size)) throw std::runtime_error("Could not query local media listener");
    impl->listener = fd;
    {std::lock_guard lock(impl->mutex); impl->observation = {}; impl->observation.released = false; impl->ended = false; impl->queuedChunks = 0; impl->closing = false;}
    std::string url = "http://127.0.0.1:"+std::to_string(ntohs(address.sin_port))+impl->path;
    impl->worker = std::thread([this] {impl->run();});
    return url;
  } catch (...) {
    impl->closing = true; impl->changed.notify_all();
    if (impl->worker.joinable()) impl->worker.join();
    close(fd); impl->listener = -1;
    std::lock_guard lock(impl->mutex); impl->observation.released = true; throw;
  }
}
void LocalVideoStream::write(const uint8_t* bytes, size_t size) {
  if (!bytes || !size || size > 2*1024*1024) throw std::invalid_argument("Invalid local media chunk");
  std::unique_lock lock(impl->mutex);
  if (!impl->changed.wait_for(lock,std::chrono::seconds(3),[&] {return impl->closing || impl->ended || !impl->observation.error.empty() || (impl->queuedChunks < MaxChunks && impl->observation.queuedBytes+size <= MaxBytes);})) {
    impl->observation.error = "outputBackpressure"; impl->closing = true; impl->changed.notify_all();
    throw std::runtime_error("Local media consumer timeout");
  }
  if (impl->closing || impl->ended || !impl->observation.error.empty()) throw std::runtime_error("Local media stream is closed");
  impl->queue.emplace_back(bytes,bytes+size); ++impl->queuedChunks; impl->observation.queuedBytes += size; impl->changed.notify_all();
}
void LocalVideoStream::finish() {
  std::lock_guard lock(impl->mutex);
  if (impl->closing || !impl->observation.error.empty()) throw std::runtime_error("Local media stream failed");
  impl->ended = true; impl->changed.notify_all();
}
void LocalVideoStream::waitFinished(std::chrono::milliseconds timeout) {
  std::unique_lock lock(impl->mutex);
  if (!impl->changed.wait_for(lock,timeout,[&] {return impl->closing || impl->observation.complete || !impl->observation.error.empty();}) || !impl->observation.complete)
    throw std::runtime_error("Local media stream did not complete");
}
void LocalVideoStream::stop() noexcept {
  // Change the wait predicate under its mutex so cancellation cannot race
  // between a producer checking it and entering the condition-variable wait.
  {std::lock_guard lock(impl->mutex); impl->closing = true;}
  impl->changed.notify_all();
  if (impl->worker.joinable()) impl->worker.join();
  if (impl->listener >= 0) {close(impl->listener); impl->listener = -1;}
  std::lock_guard lock(impl->mutex); impl->queue.clear(); impl->queuedChunks = 0; impl->observation.queuedBytes = 0; impl->observation.released = true;
}
LocalVideoStreamState LocalVideoStream::state() const {std::lock_guard lock(impl->mutex); return impl->observation;}
