#include "DlnaTransport.h"

#include <arpa/inet.h>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string_view>
#include <thread>
#include <unistd.h>

namespace {
using namespace std::chrono_literals;

void check(bool condition) {
  if (!condition) throw std::runtime_error("DLNA transport contract failed");
}

struct Client {
  int fd;
  explicit Client(uint16_t port) : fd(socket(AF_INET, SOCK_STREAM, 0)) {
    check(fd >= 0);
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    local.sin_port = htons(port);
    timeval timeout{2, 0};
    check(setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    check(connect(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) == 0);
  }
  ~Client() { close(fd); }
  void write(std::string_view data) {
    check(send(fd, data.data(), data.size(), 0) == static_cast<ssize_t>(data.size()));
  }
  std::string read() {
    std::string output;
    char buffer[256];
    for (;;) {
      const auto count = recv(fd, buffer, sizeof(buffer), 0);
      check(count >= 0);
      if (!count) return output;
      output.append(buffer, static_cast<size_t>(count));
    }
  }
};

std::string next(DlnaTransport& transport) {
  for (int i = 0; i < 200; ++i) {
    auto request = transport.poll();
    if (!request.empty()) return request;
    std::this_thread::sleep_for(5ms);
  }
  throw std::runtime_error("DLNA request not delivered");
}
}

int main() {
  DlnaTransport transport;
  const std::string identity = "uuid:00000000-0000-4000-8000-000000000001";
  const auto port = transport.start(identity, true);
  {
    Client first(port);
    first.write("POST /ctl/avt HTTP/1.1\r\nHost: localhost\r\nSoa");
    std::this_thread::sleep_for(20ms);
    first.write("pAction: \"tag#Play\"\r\nContent-Type: text/xml\r\nContent-Length: 4\r\n\r\n<x");
    std::this_thread::sleep_for(20ms);
    first.write("/>");
    const auto request = next(transport);
    check(request.find("\"id\":1") != request.npos);
    check(request.find("\"path\":\"/ctl/avt\"") != request.npos);
    check(request.find("\"soapAction\":\"\\\"tag#Play\\\"\"") != request.npos);
    check(request.find("\"body\":\"<x/>\"") != request.npos);
    transport.respond(1, 200, "<ok/>");
    check(first.read().ends_with("\r\n\r\n<ok/>"));
  }
  {
    Client pending(port);
    pending.write("GET /desc.xml HTTP/1.1\r\nHost: localhost\r\n\r\n");
    check(next(transport).find("\"id\":2") != std::string::npos);
    const auto before = std::chrono::steady_clock::now();
    transport.stop();
    check(std::chrono::steady_clock::now() - before < 500ms);
    check(pending.read().empty());
  }
  {
    Client fresh(transport.start(identity, true));
    fresh.write("HEAD /desc.xml HTTP/1.1\r\nHost: localhost\r\n\r\n");
    check(next(transport).find("\"id\":3") != std::string::npos);
    transport.respond(2, 404, "stale");
    transport.respond(3, 200, "<ok/>");
    const auto reply = fresh.read();
    check(reply.starts_with("HTTP/1.1 200 "));
    check(reply.find("Content-Length: 5\r\n") != reply.npos && reply.ends_with("\r\n\r\n"));
  }
  transport.stop();
  const auto protectedPort = transport.start(identity, true, true);
  const std::string_view control = "POST /ctl/avt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
  {
    Client unapproved(protectedPort);
    unapproved.write(control);
    check(next(transport) == "{\"approval\":{\"id\":1,\"peer\":\"127.0.0.1\"}}");
    transport.answerApproval(2, false);
    transport.answerApproval(1, true);
    check(next(transport) == "{\"approval\":null}");
    check(next(transport).find("\"id\":4") != std::string::npos);
    transport.respond(4, 200, "<ok/>");
    check(unapproved.read().starts_with("HTTP/1.1 200 "));
  }
  {
    Client granted(protectedPort);
    granted.write(control);
    check(next(transport).find("\"id\":5") != std::string::npos);
    transport.respond(5, 200, "<ok/>");
    check(granted.read().starts_with("HTTP/1.1 200 "));
  }
  transport.stop();
  const auto reopened = transport.start(identity, true, true);
  {
    Client denied(reopened);
    denied.write(control);
    check(next(transport).find("\"id\":2") != std::string::npos);
    transport.answerApproval(2, false);
    check(denied.read().starts_with("HTTP/1.1 403 "));
    check(next(transport) == "{\"approval\":null}");
    Client repeated(reopened);
    repeated.write(control);
    check(repeated.read().starts_with("HTTP/1.1 403 "));
    check(transport.poll().empty());
  }
  transport.stop();
  {
    Client pending(transport.start(identity, true, true));
    pending.write(control);
    check(next(transport).find("\"id\":3") != std::string::npos);
    const auto before = std::chrono::steady_clock::now();
    transport.stop();
    check(std::chrono::steady_clock::now() - before < 500ms && pending.read().empty());
  }
  std::cout << "DLNA control socket framing, cancellation and reopen passed\n";
}
