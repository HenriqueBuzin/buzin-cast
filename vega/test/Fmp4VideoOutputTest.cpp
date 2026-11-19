#include "../kepler/turbo-modules/Fmp4VideoOutput.h"
#include "../kepler/turbo-modules/H264Fmp4Muxer.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <thread>

namespace {
const std::vector<uint8_t> parameters{0,0,0,1,0x67,0x42,0xc0,0x0c,0xda,0x05,0x06,0x7e,0x7c,0x04,0x40,0,0,3,0,0x40,0,0,7,0x83,0xc5,0x0a,0xa8,0,0,0,1,0x68,0xce,0x0f,0xc8};
const std::vector<uint8_t> idr{0,0,0,1,0x65,0x88,0x84};
const std::vector<uint8_t> delta{0,0,1,0x41,0x9a,0x20};
template<class Function> void refuses(Function function) {
  bool rejected = false; try {function();} catch (const std::exception&) {rejected = true;} assert(rejected);
}
template<class Function> void await(Function ready) {
  const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(5);
  while (!ready()) {assert(std::chrono::steady_clock::now() < deadline); std::this_thread::sleep_for(std::chrono::milliseconds(1));}
}
struct Target {
  sockaddr_in address{};
  std::string path;
  explicit Target(const std::string& url) {
    const auto start = url.find(':',7)+1, slash = url.find('/',start);
    assert(url.substr(0,start) == "http://127.0.0.1:");
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(std::stoi(url.substr(start,slash-start))); path = url.substr(slash);
  }
  void released() const {
    const int fd = socket(AF_INET,SOCK_STREAM,0); assert(fd >= 0);
    const int one = 1; assert(!setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one)));
    assert(!bind(fd,reinterpret_cast<const sockaddr*>(&address),sizeof(address))); close(fd);
  }
  std::vector<uint8_t> read() const {
    const int fd = socket(AF_INET,SOCK_STREAM,0); assert(fd >= 0);
    timeval timeout{5,0}; assert(!setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
    assert(!connect(fd,reinterpret_cast<const sockaddr*>(&address),sizeof(address)));
    const auto request = "GET "+path+" HTTP/1.1\r\nHost: localhost\r\n\r\n";
    assert(send(fd,request.data(),request.size(),0) == static_cast<ssize_t>(request.size()));
    std::string response;
    for (;;) {char bytes[8192]; const auto count = recv(fd,bytes,sizeof(bytes),0); assert(count >= 0); if (!count) break; response.append(bytes,count);}
    close(fd);
    assert(response.find("HTTP/1.1 200 OK\r\n") == 0);
    assert(response.find("Transfer-Encoding: chunked\r\n") != std::string::npos);
    size_t cursor = response.find("\r\n\r\n")+4;
    std::vector<uint8_t> media;
    for (;;) {
      const auto end = response.find("\r\n",cursor); assert(end != std::string::npos);
      const auto size = std::stoul(response.substr(cursor,end-cursor),nullptr,16); cursor = end+2;
      assert(cursor+size+2 <= response.size() && response.substr(cursor+size,2) == "\r\n");
      if (!size) {assert(cursor+2 == response.size()); return media;}
      media.insert(media.end(),response.begin()+cursor,response.begin()+cursor+size); cursor += size+2;
    }
  }
};
}
int main() {
  Fmp4VideoOutput output;
  assert(output.state().released);
  output.cancel(); output.stop();
  refuses([&] {output.append(idr,0,0,66667);});
  refuses([&] {output.finish();});
  refuses([&] {output.start(0,180,parameters);});
  assert(output.state().released && output.state().queuedBytes == 0);
  std::vector<uint8_t> expected;
  {
    H264Fmp4Muxer reference(320,180,parameters,[&](const uint8_t* data, size_t size) {expected.insert(expected.end(),data,data+size);});
    reference.append(idr,0,0,66667); reference.append(delta,66667,66667,66667); reference.finish();
  }
  std::string previous;
  for (int cycle = 0; cycle < 2; ++cycle) {
    const auto url = output.start(320,180,parameters); assert(url != previous); previous = url;
    Target target(url);
    std::vector<uint8_t> received;
    std::thread consumer([&] {received = target.read();});
    await([&] {return output.state().connected;});
    output.append(idr,0,0,66667); output.append(delta,66667,66667,66667);
    output.finish(); output.finish(); output.waitFinished(); consumer.join();
    assert(received == expected && output.state().complete && output.state().sentBytes == expected.size());
    output.stop(); output.stop(); assert(output.state().released && output.state().queuedBytes == 0); target.released();
  }
  {
    const std::vector<uint8_t> aac{0x21,0x10,0x04,0x60,0x8c,0x1c};
    std::vector<uint8_t> expectedAv;
    H264Fmp4Muxer reference(320,180,parameters,[&](const uint8_t* bytes, size_t size) {
      expectedAv.insert(expectedAv.end(),bytes,bytes+size);
    },1024);
    reference.append(idr,0,0,33333);
    reference.appendAac(aac,80000);
    reference.finish();
    Target target(output.start(320,180,parameters,1024));
    std::vector<uint8_t> received;
    std::thread consumer([&] {received = target.read();});
    await([&] {return output.state().connected;});
    output.append(idr,0,0,33333);
    assert(output.state().sentBytes == 0);
    output.appendAac(aac,80000);
    output.finish();
    output.waitFinished();
    consumer.join();
    assert(received == expectedAv);
    output.stop();
    target.released();
  }
  // Media owner is deliberately blocked in the AVIO callback, with no GET.
  // Control cancellation must unblock it before the three-second queue timeout.
  std::atomic<bool> stopped{false}, rejected{false};
  std::string blockedUrl;
  std::atomic<bool> started{false};
  std::thread producer([&] {
    blockedUrl = output.start(320,180,parameters); started = true;
    std::vector<uint8_t> large(2*1024*1024,0xaa);
    large[0] = large[1] = large[2] = 0; large[3] = 1; large[4] = 0x65;
    try {for (int i = 0; i < 8; ++i) output.append(large,int64_t(i)*66667,int64_t(i)*66667,66667);}
    catch (const std::exception&) {rejected = true;}
    output.stop(); stopped = true;
  });
  // Chunk-count limits can be reached before the byte limit because fragment
  // headers also occupy queue slots. Wait for the bounded queue to stabilize.
  await([&] {return started.load() && output.state().queuedBytes >= 7*1024*1024;});
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  const auto queued = output.state().queuedBytes;
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  assert(!stopped && output.state().queuedBytes == queued);
  const auto before = std::chrono::steady_clock::now();
  output.cancel(); output.cancel();
  await([&] {return stopped.load();}); producer.join();
  assert(rejected && std::chrono::steady_clock::now()-before < std::chrono::seconds(1));
  assert(output.state().released && output.state().queuedBytes == 0 && output.state().error.empty());
  Target(blockedUrl).released();
  // Replacing a cancelled output gets fresh tokens and a fresh muxer.
  const auto fresh = output.start(320,180,parameters); assert(fresh != blockedUrl);
  output.append(idr,0,0,66667); output.finish();
  stopped = false; rejected = false;
  std::thread control([&] {std::this_thread::sleep_for(std::chrono::milliseconds(20)); output.cancel();});
  refuses([&] {output.waitFinished();}); control.join();
  output.stop(); Target(fresh).released();
  std::string abandonedUrl;
  {
    Fmp4VideoOutput abandoned;
    abandonedUrl = abandoned.start(320,180,parameters);
    // Scope destruction must release a stream that has never had a consumer.
    abandoned.append(idr,0,0,66667);
  }
  Target(abandonedUrl).released();
}
