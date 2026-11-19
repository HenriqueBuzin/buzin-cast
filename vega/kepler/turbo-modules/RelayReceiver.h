#pragma once

#include "AirPlayVideoWorker.h"
#include <atomic>
#include <string>
#include <thread>

class RelayReceiver {
public:
  struct Endpoint {std::string address, code;};
  RelayReceiver();
  ~RelayReceiver();
  Endpoint start();
  std::string state() const;
  bool poll(AirPlayVideoCommand& command);
  void stop() noexcept;

private:
  enum class Phase {Idle, Waiting, Receiving, Ending, Expired, Error};
  void listen(int listener, const std::string& code) noexcept;
  std::atomic<Phase> phase{Phase::Idle};
  std::atomic<bool> active{false};
  std::thread worker;
  AirPlayVideoWorker video;
};
