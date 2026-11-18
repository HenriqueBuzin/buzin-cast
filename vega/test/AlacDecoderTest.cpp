#include "../kepler/turbo-modules/AlacDecoder.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>

int main(int argc, char** argv) {
  assert(argc == 2);
  std::ifstream input(argv[1],std::ios::binary);
  const std::vector<uint8_t> packet{std::istreambuf_iterator<char>(input),{}};
  assert(!packet.empty());
  for (int cycle = 0; cycle < 2; ++cycle) {
    AlacDecoder decoder;
    for (int repeat = 0; repeat < 10; ++repeat) {
      const auto pcm = decoder.decode(packet);
      assert(pcm.size() == 352*4);
      int peak = 0; bool positive = false, negative = false;
      for (int i = 0; i < 352; ++i) {
        int16_t left, right;
        std::memcpy(&left,pcm.data()+i*4,2); std::memcpy(&right,pcm.data()+i*4+2,2);
        assert(left == right);
        peak = std::max(peak,std::abs(int(left)));
        positive = positive || left > 0; negative = negative || left < 0;
      }
      assert(peak >= 100 && peak <= 5000 && positive && negative);
    }
  }
  for (const auto& bytes : {std::vector<uint8_t>{},std::vector<uint8_t>(65537),std::vector<uint8_t>{0}}) {
    AlacDecoder decoder; bool refused = false;
    try {decoder.decode(bytes);} catch (const std::exception&) {refused = true;}
    assert(refused);
  }
}
