#include "ReceiverSettings.h"
#include <filesystem>
#include <fstream>
#include <iostream>
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
    std::filesystem::remove_all(folder);
  } catch (...) {std::filesystem::remove_all(folder); throw;}
  std::cout << "Receiver name: default, persistence, UTF-8 validation, failed write and recovery passed\n";
}
