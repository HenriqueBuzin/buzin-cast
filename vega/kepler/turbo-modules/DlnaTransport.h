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

  uint16_t start(const std::string& uuid, bool loopbackOnly = false);
  void stop() noexcept;
  std::string poll();
  void respond(double requestId, double status, std::string body);

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
