#include "../kepler/turbo-modules/LocalVideoStream.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <thread>
#include <vector>

struct Target {
  int port;
  std::string path;
  explicit Target(const std::string& url) {
    const auto start = url.find(':',7)+1, slash = url.find('/',start);
    assert(url.substr(0,start) == "http://127.0.0.1:");
    port = std::stoi(url.substr(start,slash-start)); path = url.substr(slash);
    assert(path.size() == 69);
  }
  int connect() const {
    const int fd = socket(AF_INET,SOCK_STREAM,0); assert(fd >= 0);
    timeval timeout{5,0}; setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(port);
    assert(::connect(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address)) == 0); return fd;
  }
  void released() const {
    const int fd = socket(AF_INET,SOCK_STREAM,0); assert(fd >= 0);
    const int one = 1; setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(port);
    assert(bind(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address)) == 0); close(fd);
  }
};
std::string all(int fd) {
  std::string data; char buffer[4096];
  for (;;) {const auto size = recv(fd,buffer,sizeof(buffer),0); assert(size >= 0); if (!size) return data; data.append(buffer,static_cast<size_t>(size));}
}
std::string request(const Target& target, const std::string& line) {
  const auto fd = target.connect();
  assert(send(fd,line.data(),line.size(),0) == static_cast<ssize_t>(line.size()));
  auto response = all(fd); close(fd); return response;
}
template<class Function> void refuses(Function function) {
  bool rejected = false; try {function();} catch (const std::exception&) {rejected = true;} assert(rejected);
}
int main() {
  LocalVideoStream stream;
  std::string old;
  for (int cycle = 0; cycle < 2; ++cycle) {
    const auto url = stream.start(); assert(url != old); old = url; Target target(url);
    assert(request(target,"GET /wrong.mp4 HTTP/1.1\r\nHost: localhost\r\n\r\n").find("404") != std::string::npos);
    assert(request(target,"GET "+target.path+" HTTP/1.1\r\nRange: bytes=10-\r\n\r\n").find("416") != std::string::npos);
    assert(request(target,"HEAD "+target.path+" HTTP/1.1\r\nHost: localhost\r\n\r\n").find("200 OK") != std::string::npos);
    const uint8_t bytes[] = {0,1,255,3}; stream.write(bytes,sizeof(bytes)); stream.write(bytes,2); stream.finish(); stream.finish();
    const auto response = request(target,"GET "+target.path+" HTTP/1.1\r\nRange: bytes=0-\r\nContent-Length: 0\r\n\r\n");
    assert(response.find("Transfer-Encoding: chunked") != std::string::npos);
    const auto body = response.substr(response.find("\r\n\r\n")+4);
    std::string expected = "4\r\n"; expected.append(reinterpret_cast<const char*>(bytes),4);
    expected += "\r\n2\r\n"; expected.append(reinterpret_cast<const char*>(bytes),2); expected += "\r\n0\r\n\r\n";
    assert(body == expected); stream.waitFinished(std::chrono::seconds(2));
    assert(stream.state().complete && stream.state().sentBytes == 6 && stream.state().queuedBytes == 0);
    stream.stop(); stream.stop(); assert(stream.state().released); target.released();
    refuses([&] {stream.write(bytes,1);});
  }
  const std::vector<uint8_t> large(2*1024*1024,42);
  Target cancelled(stream.start());
  for (int i = 0; i < 4; ++i) stream.write(large.data(),large.size());
  assert(stream.state().queuedBytes == 8*1024*1024);
  std::atomic<bool> waiting{false}, rejected{false};
  std::thread producer([&] {waiting = true; try {stream.write(large.data(),large.size());} catch (...) {rejected = true;}});
  while (!waiting) std::this_thread::yield();
  std::this_thread::sleep_for(std::chrono::milliseconds(20)); assert(!rejected);
  const auto before = std::chrono::steady_clock::now();
  // Session cancellation must wake a writer without joining its owner or
  // freeing the queue while that owner is still unwinding its muxer callback.
  stream.cancel(); stream.cancel(); producer.join();
  assert(rejected && !stream.state().released && stream.state().error.empty());
  refuses([&] {stream.finish();});
  refuses([&] {stream.waitFinished(std::chrono::milliseconds(10));});
  assert(std::chrono::steady_clock::now()-before < std::chrono::seconds(1));
  stream.stop(); assert(stream.state().released && stream.state().queuedBytes == 0); cancelled.released();
  Target finishing(stream.start());
  for (int i = 0; i < 4; ++i) stream.write(large.data(),large.size());
  waiting = false; rejected = false;
  std::thread pending([&] {waiting = true; try {stream.write(large.data(),large.size());} catch (...) {rejected = true;}});
  while (!waiting) std::this_thread::yield();
  std::this_thread::sleep_for(std::chrono::milliseconds(20)); assert(!rejected);
  stream.finish(); pending.join(); assert(rejected && stream.state().error.empty());
  stream.stop(); finishing.released();
  Target idle(stream.start()); const auto idleClient = idle.connect();
  const auto idleStart = std::chrono::steady_clock::now(); stream.stop();
  assert(std::chrono::steady_clock::now()-idleStart < std::chrono::seconds(1));
  close(idleClient); idle.released();
  Target chunks(stream.start());
  for (int i = 0; i < 2048; ++i) stream.write(large.data(),1);
  waiting = false; rejected = false;
  std::atomic<bool> finishedWrite{false};
  std::thread tiny([&] {waiting = true; try {stream.write(large.data(),1);} catch (...) {rejected = true;} finishedWrite = true;});
  while (!waiting) std::this_thread::yield();
  std::this_thread::sleep_for(std::chrono::milliseconds(20)); assert(!finishedWrite);
  stream.stop(); tiny.join(); assert(rejected && stream.state().queuedBytes == 0); chunks.released();
  Target pressure(stream.start());
  for (int i = 0; i < 4; ++i) stream.write(large.data(),large.size());
  refuses([&] {stream.write(large.data(),large.size());});
  assert(stream.state().error == "outputBackpressure" && stream.state().queuedBytes == 8*1024*1024);
  stream.stop(); pressure.released();
  Target disconnected(stream.start());
  const auto fd = disconnected.connect();
  const auto get = "GET "+disconnected.path+" HTTP/1.1\r\nHost: localhost\r\n\r\n";
  assert(send(fd,get.data(),get.size(),0) == static_cast<ssize_t>(get.size()));
  char header[1024]; assert(recv(fd,header,sizeof(header),0) > 0); close(fd);
  bool failed = false;
  for (int i = 0; i < 20 && !failed; ++i) {try {stream.write(large.data(),large.size());} catch (...) {failed = true;}}
  assert(failed && !stream.state().error.empty()); stream.stop(); disconnected.released();
}
