#include "FCastTransport.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <chrono>
#include <thread>
#include <iostream>
#include <stdexcept>

using Clock = std::chrono::steady_clock;
void check(bool value) {if (!value) throw std::runtime_error("FCast transport assertion failed");}
struct Socket {
  int fd;
  explicit Socket(int port) : fd(socket(AF_INET, SOCK_STREAM, 0)) {
    check(fd >= 0);
    timeval timeout{2, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    sockaddr_in address{}; address.sin_family = AF_INET;
    address.sin_port = htons(port); address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    check(connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0);
  }
  ~Socket() {close(fd);}
  std::string read(size_t size) {
    std::string result(size, '\0'); size_t received = 0;
    while (received < size) {
      const auto count = recv(fd, result.data() + received, size - received, 0);
      check(count > 0); received += count;
    }
    return result;
  }
  std::pair<int, std::string> packet() {
    auto header = read(4); uint32_t size = 0;
    for (int i = 0; i < 4; ++i) size |= static_cast<uint32_t>(static_cast<unsigned char>(header[i])) << (8 * i);
    check(size >= 1 && size <= 32000);
    auto data = read(size); return {static_cast<unsigned char>(data[0]), data.substr(1)};
  }
  void sendPacket(int opcode, const std::string& body = "", bool fragmented = false) {
    uint32_t length = body.size() + 1; std::string bytes;
    for (int i = 0; i < 4; ++i) bytes += static_cast<char>(length >> (8 * i));
    bytes += static_cast<char>(opcode); bytes += body;
    for (size_t i = 0; i < bytes.size();) {
      const auto count = fragmented ? 1 : bytes.size() - i;
      check(send(fd, bytes.data() + i, count, 0) == static_cast<ssize_t>(count));
      i += count;
      if (fragmented) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
  }
  void until(int opcode, const std::string& expected) {
    for (int i = 0; i < 8; ++i) {
      const auto [op, body] = packet();
      if (op == opcode) {check(body == expected); return;}
    }
    check(false);
  }
  void closed() {
    char bytes[4096];
    while (true) {
      const auto count = recv(fd, bytes, sizeof(bytes), 0);
      if (count == 0 || (count < 0 && errno == ECONNRESET)) return;
      check(count > 0);
    }
  }
};
int main() {
  try {
    FCastTransport receiver;
    const int port = receiver.start(0, true);
    {
      Socket client(port);
      client.until(11, "{\"version\":2}");
      Socket extra(port); extra.closed();
      client.sendPacket(1, "{\"title\":\"vídeo\\n\"}", true);
      std::string command;
      for (int i = 0; i < 100 && command.empty(); ++i) {
        command = receiver.pollCommand();
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
      }
      check(command.find("\"client\":1,\"opcode\":1") != std::string::npos);
      check(command.find("vídeo\\\\n") != std::string::npos);
      check(receiver.isClient(1));
      client.sendPacket(12); client.until(13, "");
      receiver.error(1, "Invalid media URL"); client.until(9, "{\"message\":\"Invalid media URL\"}");
      receiver.update("{\"state\":2}", "{\"volume\":0.5}");
      client.until(6, "{\"state\":2}"); client.until(7, "{\"volume\":0.5}");
      client.sendPacket(1, std::string("\xc0\xaf", 2)); client.closed();
    }
    {
      Socket client(port); client.until(11, "{\"version\":2}");
      check(receiver.isClient(2)); check(!receiver.isClient(1));
      receiver.disconnect(1); // stale owner cannot close the new controller
      client.sendPacket(12); client.until(13, "");
      const char oversized[] = {1, 125, 0, 0}; // 32001
      check(send(client.fd, oversized, 4, 0) == 4); client.closed();
    }
    {
      Socket client(port); client.until(11, "{\"version\":2}");
      const char first = 1;
      check(send(client.fd, &first, 1, 0) == 1);
      std::this_thread::sleep_for(std::chrono::milliseconds(5200));
      client.closed(); // partial frame expires, while idle connections do not
    }
    {
      Socket client(port); client.until(11, "{\"version\":2}");
      auto before = Clock::now(); receiver.stop(); client.closed();
      check(Clock::now() - before < std::chrono::milliseconds(500));
    }
    receiver.start(port, true); receiver.stop();
    std::cout << "FCast transport: fragmented UTF-8, exclusive client, ping, updates, stale owner, oversize, deadline and restart passed\n";
    return 0;
  } catch (const std::exception& error) {std::cerr << error.what() << '\n'; return 1;}
}
