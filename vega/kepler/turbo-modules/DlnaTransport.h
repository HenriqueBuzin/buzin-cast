#pragma once

#include <cstdint>
#include <memory>
#include <string>

class DlnaTransport {
public:
  DlnaTransport();
  ~DlnaTransport();
  DlnaTransport(const DlnaTransport&) = delete;
  DlnaTransport& operator=(const DlnaTransport&) = delete;

  uint16_t start(const std::string& uuid, bool loopbackOnly = false, bool requireApproval = false);
  void stop() noexcept;
  void publish(std::string transport, std::string rendering, std::string connection);
  std::string poll();
  void answerApproval(double id, bool allow);
  void respond(double requestId, double status, std::string body);

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
