#include "BuzinNetwork.h"
#include "NetworkProbe.h"
#include "ProtocolProbe.h"
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
}
