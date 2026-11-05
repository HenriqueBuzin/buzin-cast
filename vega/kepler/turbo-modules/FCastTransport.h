#pragma once
#include <memory>
#include <string>

// Owns one bounded TCP session. All socket I/O happens on its worker thread.
// No player or JavaScript callbacks run on that thread.
class FCastTransport {
public:
  FCastTransport();
  ~FCastTransport();
  FCastTransport(const FCastTransport&) = delete;
  FCastTransport& operator=(const FCastTransport&) = delete;
  int start(int port = 46899, bool loopbackOnly = false);
  void stop();
  std::string pollCommand();
  bool isClient(double client) const;
  void disconnect(double client);
  void error(double client, const std::string& message);
  void update(const std::string& playback, const std::string& volume);
private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};
