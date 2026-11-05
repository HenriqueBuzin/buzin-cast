#include "BuzinNetwork.h"
#include "NetworkProbe.h"
#include "ProtocolProbe.h"
#include <cmath>
#include <cstdio>
#include <sstream>
#include <locale>
#include <limits>
#include <fstream>
#include <stdexcept>

namespace BuzinNetworkTurboModule {
BuzinNetwork::BuzinNetwork() = default;
BuzinNetwork::~BuzinNetwork() noexcept = default;
std::string BuzinNetwork::getReport() {
  auto report = runNetworkProbe();
  report.pop_back();
  report += "," + runAirPlayCoreProbe().substr(1);
  // Only boolean diagnostic outcomes; no addresses or user content are saved.
  std::ofstream output("/data/network-probe.json", std::ios::trunc);
  output << report << '\n';
  output.close();
  if (!output) throw std::runtime_error("Could not save network diagnostic");
  return report;
}
std::string BuzinNetwork::recordMediaCapabilities(double h264, double hevc, double aac) {
  // Counts come from the public system media descriptor; persist no content.
  for (const double count : {h264, hevc, aac}) {
    if (!std::isfinite(count) || count < 0 || std::floor(count) != count ||
        count > std::numeric_limits<int>::max()) {
      throw std::invalid_argument("Invalid media capability count");
    }
  }
  const auto report = "{\"h264Decoders\":" + std::to_string(static_cast<int>(h264)) +
      ",\"hevcDecoders\":" + std::to_string(static_cast<int>(hevc)) +
      ",\"aacDecoders\":" + std::to_string(static_cast<int>(aac)) + "}";
  std::ofstream output("/data/media-capabilities.json", std::ios::trunc);
  output << report << '\n';
  output.close();
  if (!output) throw std::runtime_error("Could not save media diagnostic");
  return report;
}
void BuzinNetwork::recordPlaybackState(std::string state, double seconds) {
  if (state != "loading" && state != "playing" && state != "paused" &&
      state != "ended" && state != "error" && state != "released") {
    throw std::invalid_argument("Invalid playback state");
  }
  if (!std::isfinite(seconds) || seconds < 0) {
    throw std::invalid_argument("Invalid playback observation");
  }
  std::ofstream output("/data/playback-state.json.tmp", std::ios::trunc);
  output.imbue(std::locale::classic());
  output << "{\"state\":\"" << state << "\",\"seconds\":" << seconds
         << "}\n";
  output.close();
  if (!output || std::rename("/data/playback-state.json.tmp", "/data/playback-state.json") != 0) {
    throw std::runtime_error("Could not save playback observation");
  }
}

}
