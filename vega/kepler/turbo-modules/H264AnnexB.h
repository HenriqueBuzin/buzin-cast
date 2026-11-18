#pragma once
#include <cstdint>
#include <stdexcept>
#include <vector>
namespace BuzinH264 {
struct Nal {const uint8_t* bytes; size_t size; int type;};
inline std::vector<Nal> split(const std::vector<uint8_t>& bytes) {
  if (bytes.empty() || bytes.size() > 2*1024*1024) throw std::invalid_argument("Invalid H.264 access unit size");
  auto prefix = [&](size_t i) -> size_t {
    if (i+3 <= bytes.size() && bytes[i] == 0 && bytes[i+1] == 0) {
      if (bytes[i+2] == 1) return 3;
      if (i+4 <= bytes.size() && bytes[i+2] == 0 && bytes[i+3] == 1) return 4;
    }
    return 0;
  };
  std::vector<Nal> units;
  size_t offset = 0;
  while (offset < bytes.size()) {
    const auto start = prefix(offset);
    if (!start || units.size() >= 128) throw std::invalid_argument("Invalid bounded Annex B packet");
    const auto first = offset+start;
    size_t end = first;
    while (end < bytes.size() && !prefix(end)) ++end;
    size_t last = end;
    while (last > first && bytes[last-1] == 0) --last;
    if (last == first || (bytes[first]&0x80)) throw std::invalid_argument("Invalid H.264 NAL header");
    const int type = bytes[first]&31;
    if (type == 0 || type > 12 || (type >= 2 && type <= 4)) throw std::invalid_argument("Unsupported H.264 NAL type");
    units.push_back({bytes.data()+first,last-first,type}); offset = end;
  }
  return units;
}

}
