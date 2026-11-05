#include "BuzinNetwork.h"
#include "NetworkProbe.h"
#include "ProtocolProbe.h"
#include <cmath>
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
}
