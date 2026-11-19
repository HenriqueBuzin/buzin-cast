#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

class RecordingExport {
public:
  ~RecordingExport();
  std::string start(const std::string& filename, const std::string& path);
  std::string state() const;
  void stop() noexcept;

private:
  enum class Phase {Idle, Ready, Sending, Sent, Expired, Error};
  Phase serve(int listener, int file, uint64_t size, const std::string& filename,
              const std::string& route, const std::string& host);
  std::atomic<Phase> phase{Phase::Idle};
  std::atomic<bool> active{false};
  std::thread worker;
};
