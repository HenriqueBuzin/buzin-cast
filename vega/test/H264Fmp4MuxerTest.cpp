#include "../kepler/turbo-modules/H264Fmp4Muxer.h"
#include <algorithm>
#include <cassert>
#include <stdexcept>

const std::vector<uint8_t> parameters{0,0,0,1,0x67,0x42,0xc0,0x0c,0xda,0x05,0x06,0x7e,0x7c,0x04,0x40,0,0,3,0,0x40,0,0,7,0x83,0xc5,0x0a,0xa8,0,0,0,1,0x68,0xce,0x0f,0xc8};
const std::vector<uint8_t> idr{0,0,0,1,0x65,0x88,0x84};
const std::vector<uint8_t> delta{0,0,1,0x41,0x9a,0x20};
template<class Function> void refuses(Function function) {
  bool refused = false;
  try {function();} catch (const std::exception&) {refused = true;}
  assert(refused);
}
int main() {
  std::vector<uint8_t> previous;
  for (int cycle = 0; cycle < 2; ++cycle) {
    std::vector<uint8_t> output;
    H264Fmp4Muxer mux(320,180,parameters,[&](const uint8_t* data, size_t size) {output.insert(output.end(),data,data+size);});
    assert(output.size() > 8);
    const auto initial = output.size();
    refuses([&] {mux.append(delta,0,0,66667);});
    refuses([&] {mux.append({},0,0,66667);});
    refuses([&] {mux.append({0,0,1,0xe5},0,0,66667);});
    refuses([&] {mux.append(std::vector<uint8_t>(2*1024*1024+1),0,0,66667);});
    assert(output.size() == initial);
    mux.append(idr,0,0,66667);
    assert(output.size() > initial); const auto first = output.size();
    refuses([&] {mux.append(delta,0,0,66667);});
    refuses([&] {mux.append(delta,66667,66667,1000001);});
    refuses([&] {mux.append(delta,66666,66667,66667);});
    auto changed = parameters; changed[7] ^= 1;
    changed.insert(changed.end(),idr.begin(),idr.end());
    refuses([&] {mux.append(changed,66667,66667,66667);});
    assert(output.size() == first);
    mux.append(delta,66667,66667,66667);
    assert(output.size() > first);
    mux.finish(); const auto finished = output.size(); mux.finish();
    assert(output.size() == finished);
    refuses([&] {mux.append(idr,133334,133334,66667);});
    if (cycle) assert(output == previous);
    previous = std::move(output);
  }
  refuses([&] {H264Fmp4Muxer mux(0,180,parameters,[](const uint8_t*,size_t) {});});
  refuses([&] {H264Fmp4Muxer mux(320,180,{},[](const uint8_t*,size_t) {});});
  refuses([&] {H264Fmp4Muxer mux(320,180,parameters,[](const uint8_t*,size_t) {throw std::runtime_error("Output failed");});});
  bool fail = false; size_t writes = 0;
  H264Fmp4Muxer mux(320,180,parameters,[&](const uint8_t*,size_t) {++writes; if (fail) throw std::runtime_error("Output failed");});
  fail = true;
  refuses([&] {mux.append(idr,0,0,66667);});
  const auto failedWrites = writes;
  refuses([&] {mux.append(idr,0,0,66667);});
  refuses([&] {mux.finish();});
  assert(writes == failedWrites);
}
