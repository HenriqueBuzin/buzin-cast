#include "ReceiverSettings.h"
#include <cerrno>
#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <cstdint>

void validateReceiverName(const std::string& name) {
  // Leave room in the DNS label for a collision-resistant instance suffix.
  if (name.empty() || name.size() > 48 || name.front() == ' ' || name.back() == ' ')
    throw std::invalid_argument("Receiver name must contain 1 to 48 UTF-8 bytes");
  size_t i = 0;
  while (i < name.size()) {
    uint32_t c = static_cast<unsigned char>(name[i++]);
    uint32_t minimum = 0;
    int extra = 0;
    if (c >= 0xc2 && c <= 0xdf) {extra = 1; minimum = 0x80; c &= 31;}
    else if (c >= 0xe0 && c <= 0xef) {extra = 2; minimum = 0x800; c &= 15;}
    else if (c >= 0xf0 && c <= 0xf4) {extra = 3; minimum = 0x10000; c &= 7;}
    else if (c >= 0x80) throw std::invalid_argument("Invalid UTF-8 name");
    while (extra--) {
      if (i >= name.size()) throw std::invalid_argument("Truncated UTF-8 name");
      const auto next = static_cast<unsigned char>(name[i++]);
      if ((next & 0xc0) != 0x80) throw std::invalid_argument("Invalid UTF-8 name");
      c = (c << 6) | (next & 63);
    }
    if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff) ||
        c < 32 || (c >= 127 && c <= 159) || c == '.' || c == '\\')
      throw std::invalid_argument("Invalid character in receiver name");
  }
}
std::string readReceiverName(const std::string& path) {
  errno = 0;
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    if (errno == ENOENT) return "Buzin Cast";
    throw std::runtime_error("Could not read receiver name");
  }
  char bytes[49];
  input.read(bytes, sizeof(bytes));
  if (input.bad()) throw std::runtime_error("Could not read receiver name");
  const std::string name(bytes, static_cast<size_t>(input.gcount()));
  validateReceiverName(name);
  return name;
}
void writeReceiverName(const std::string& path, const std::string& name) {
  validateReceiverName(name);
  const auto temporary = path + ".tmp";
  std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
  output.write(name.data(), static_cast<std::streamsize>(name.size()));
  output.close();
  if (!output || std::rename(temporary.c_str(), path.c_str()) != 0) {
    std::remove(temporary.c_str());
    throw std::runtime_error("Could not save receiver name");
  }
}
