#pragma once

#include <cstdint>
#include <memory>
#include <string>

class CastV2Transport {
public:
  CastV2Transport();
  ~CastV2Transport();
  CastV2Transport(const CastV2Transport&) = delete;
  CastV2Transport& operator=(const CastV2Transport&) = delete;

  uint16_t start(const std::string& name, const std::string& keyPath, bool requireApproval);
  void stop() noexcept;
  std::string poll();
  void answerApproval(double id, bool allow);
  void send(double client, const std::string& payload);
  void disconnect(double client);

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
