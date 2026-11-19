#include "../kepler/turbo-modules/RelayReceiver.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
using namespace std::chrono_literals;

void report(const char* file, const std::string& data) {
  const auto temporary = std::string(file) + ".tmp";
  std::ofstream output(temporary);
  output << data;
  output.close();
  if (!output || std::rename(temporary.c_str(), file)) throw std::runtime_error("Could not write relay probe state");
}
}

int main() {
  try {
    RelayReceiver relay;
    const auto endpoint = relay.start();
    report("/data/buzin-relay-probe-endpoint.json",
           "{\"address\":\"" + endpoint.address + "\",\"code\":\"" + endpoint.code + "\"}");
    const auto deadline = std::chrono::steady_clock::now() + 40s;
    std::string previous;
    while (std::chrono::steady_clock::now() < deadline) {
      const auto phase = relay.state();
      if (phase != previous) {
        report("/data/buzin-relay-probe-state", phase);
        previous = phase;
      }
      AirPlayVideoCommand command;
      if (relay.poll(command) && command.kind == AirPlayVideoCommand::Kind::Play) {
        report("/data/buzin-relay-probe-url", command.url);
      }
      if (phase == "ended") return 0;
      if (phase == "error" || phase == "expired") throw std::runtime_error("Relay probe stopped receiving");
      std::this_thread::sleep_for(10ms);
    }
    throw std::runtime_error("Relay probe timeout");
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
