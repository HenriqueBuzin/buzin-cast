#include "ReceiverSettings.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <unistd.h>
void check(bool value) {if (!value) throw std::runtime_error("Receiver settings assertion failed");}
int main() {
  char folder[] = "/tmp/buzin-settings-XXXXXX";
  check(mkdtemp(folder) != nullptr);
  const auto path = std::string(folder) + "/name";
  try {
    check(readReceiverName(path) == "Buzin Cast");
    writeReceiverName(path, "Sala – televisão");
    check(readReceiverName(path) == "Sala – televisão");
    const std::string invalid[] = {"", " leading", "trailing ", "with.dot", "with\\slash", std::string(49, 'x'), "line\nbreak", std::string("\xc0\xaf", 2), std::string("\xed\xa0\x80", 3)};
    for (const auto& name : invalid) {
      bool rejected = false;
      try {writeReceiverName(path, name);} catch (const std::invalid_argument&) {rejected = true;}
      check(rejected);
      check(readReceiverName(path) == "Sala – televisão");
    }
    bool failed = false;
    try {writeReceiverName(path + "/missing", "New name");} catch (const std::runtime_error&) {failed = true;}
    check(failed);
    check(readReceiverName(path) == "Sala – televisão");
    {std::ofstream corrupt(path); corrupt << std::string(10000, 'x');}
    bool corruptRejected = false;
    try {readReceiverName(path);} catch (const std::invalid_argument&) {corruptRejected = true;}
    check(corruptRejected);
    writeReceiverName(path, "Restored");
    check(readReceiverName(path) == "Restored");
    const auto approval = std::string(folder) + "/approval";
    check(!readApprovalSetting(approval));
    writeApprovalSetting(approval, true);
    check(readApprovalSetting(approval));
    writeApprovalSetting(approval, false);
    check(!readApprovalSetting(approval));
    {std::ofstream corrupt(approval); corrupt << "10";}
    bool approvalRejected = false;
    try {readApprovalSetting(approval);} catch (const std::runtime_error&) {approvalRejected = true;}
    check(approvalRejected);
    const auto margin = std::string(folder) + "/video-margin";
    check(readVideoMargin(margin) == 2);
    writeVideoMargin(margin, 0);
    check(readVideoMargin(margin) == 0);
    writeVideoMargin(margin, 10);
    check(readVideoMargin(margin) == 10);
    for (double value : {-1.0, 11.0, 2.5, std::numeric_limits<double>::quiet_NaN()}) {
      bool rejected = false;
      try {writeVideoMargin(margin, value);} catch (const std::invalid_argument&) {rejected = true;}
      check(rejected && readVideoMargin(margin) == 10);
    }
    {std::ofstream corrupt(margin); corrupt << "10x";}
    check(readVideoMargin(margin) == 2);
    std::filesystem::remove_all(folder);
  } catch (...) {std::filesystem::remove_all(folder); throw;}
  std::cout << "Receiver settings: defaults, persistence, validation and recovery passed\n";
}
