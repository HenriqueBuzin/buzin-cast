#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

class SubtitleUpload {
public:
  SubtitleUpload() = default;
  ~SubtitleUpload();
  std::string start();
  std::string poll();
  void stop() noexcept;

private:
  void serve(const std::string& path, const std::string& host);
  std::atomic<bool> active{false};
  int listener = -1;
  std::thread worker;
  std::mutex mutex;
  std::string upload;
};
