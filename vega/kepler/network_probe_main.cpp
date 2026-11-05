#include "turbo-modules/NetworkProbe.h"
#include <iostream>
int main() {
  const auto report = runNetworkProbe();
  std::cout << report << '\n';
  return report.find("false") == std::string::npos ? 0 : 1;
}
