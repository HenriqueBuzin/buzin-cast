#include "../kepler/turbo-modules/H264Fmp4Muxer.h"
#include "../kepler/turbo-modules/AlacAacTranscoder.h"
#include <algorithm>
#include <cassert>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>

const std::vector<uint8_t> parameters{0,0,0,1,0x67,0x42,0xc0,0x0c,0xda,0x05,0x06,0x7e,0x7c,0x04,0x40,0,0,3,0,0x40,0,0,7,0x83,0xc5,0x0a,0xa8,0,0,0,1,0x68,0xce,0x0f,0xc8};
const std::vector<uint8_t> idr{0,0,0,1,0x65,0x88,0x84};
const std::vector<uint8_t> delta{0,0,1,0x41,0x9a,0x20};
template<class Function> void refuses(Function function) {
  bool refused = false;
  try {function();} catch (const std::exception&) {refused = true;}
  assert(refused);
}
namespace {
size_t boxTag(const std::vector<uint8_t>& output, const std::string& tag, size_t offset = 0) {
  const auto position = std::search(output.begin()+offset,output.end(),tag.begin(),tag.end());
  assert(position != output.end());
  return static_cast<size_t>(position-output.begin());
}
uint64_t readBigEndian(const std::vector<uint8_t>& output, size_t offset, size_t width) {
  assert(offset+width <= output.size());
  uint64_t value = 0;
  for (size_t i = 0; i < width; ++i) value = (value << 8) | output[offset+i];
  return value;
}

void verifyAvTimeline(bool audioFirst, int samples, const char* exportPath) {
  const std::vector<uint8_t> aac{0x21,0x10,0x04,0x60,0x8c,0x1c};
  const int64_t videoStart = audioFirst ? 80000 : 0;
  const int64_t audioStart = audioFirst ? 0 : 80000;
  std::vector<uint8_t> output;
  H264Fmp4Muxer mux(320,180,parameters,[&](const uint8_t* bytes, size_t size) {
    output.insert(output.end(),bytes,bytes+size);
  },samples);
  assert(output.empty());
  if (audioFirst) {
    mux.appendAudio(aac,audioStart);
    refuses([&] {mux.appendAudio(aac,audioStart+40000);});
  } else {
    mux.append(idr,videoStart,videoStart,33333);
    refuses([&] {mux.append(delta,33333,33333,33333);});
  }
  assert(output.empty());
  refuses([&] {mux.finish();});
  if (audioFirst) mux.append(idr,videoStart,videoStart,33333);
  else mux.appendAudio(aac,audioStart);

  const auto header = boxTag(output,"moov");
  const auto firstFragment = boxTag(output,"moof");
  // A leading empty edit on the later track preserves its 80 ms offset.
  size_t offset = header;
  unsigned delayedTracks = 0;
  for (int track = 0; track < 2; ++track) {
    const auto edits = boxTag(output,"elst",offset);
    assert(edits < firstFragment);
    const auto count = readBigEndian(output,edits+8,4);
    if (count == 2) {
      const auto width = output[edits+4] == 1 ? 8u : 4u;
      assert(readBigEndian(output,edits+12,width) == 80000);
      assert(readBigEndian(output,edits+12+width,width) == (width == 8 ? UINT64_MAX : UINT32_MAX));
      ++delayedTracks;
    } else {
      assert(count == 1);
    }
    offset = edits+4;
  }
  assert(delayedTracks == 1);
  refuses([&] {mux.appendAudio({},audioStart+40000);});
  refuses([&] {mux.appendAudio(aac,audioStart);});
  refuses([&] {mux.appendAudio(aac,audioStart+1000);});
  refuses([&] {mux.append(delta,videoStart+10000,videoStart+10000,33333);});
  const auto beforeVideo = output.size();
  mux.append(delta,videoStart+60000,videoStart+60000,33333);
  const auto videoTfdt = boxTag(output,"tfdt",beforeVideo);
  assert(readBigEndian(output,videoTfdt+8,8) == 60000);
  const auto beforeAudio = output.size();
  mux.appendAudio(aac,audioStart+40000);
  const auto audioTfdt = boxTag(output,"tfdt",beforeAudio);
  assert(readBigEndian(output,audioTfdt+8,8) == 1764);
  mux.finish();
  if (exportPath) {
    std::ofstream file(exportPath,std::ios::binary);
    file.write(reinterpret_cast<const char*>(output.data()),static_cast<std::streamsize>(output.size()));
    assert(file);
  }
}

void verifyAlacConversion(const std::vector<uint8_t>& alac) {
  std::vector<int64_t> timestamps;
  AlacAacTranscoder transcoder([&](const std::vector<uint8_t>& packet, int64_t pts) {
    assert(!packet.empty());
    timestamps.push_back(pts);
  });
  transcoder.append(alac,3528);
  refuses([&] {transcoder.append(alac,3529);});
  // A long gap closes the preceding partial AAC frame without generating
  // silences over the full gap. The following frame retains its own start.
  transcoder.append(alac,8820);
  transcoder.finish();
  transcoder.finish();
  assert((timestamps == std::vector<int64_t>{3528,8820}));
  refuses([&] {transcoder.append(alac,9172);});

  std::vector<uint8_t> output;
  H264Fmp4Muxer mux(320,180,parameters,[&](const uint8_t* bytes,size_t size) {
    output.insert(output.end(),bytes,bytes+size);
  },352,true);
  mux.append(idr,0,0,40000);
  mux.append(delta,40000,40000,40000);
  mux.appendAudio(alac,80000);
  mux.append(delta,80000,80000,40000);
  assert(output.empty());
  for (int i = 1; i < 12; ++i) {
    mux.appendAudio(alac,80000+(int64_t(i)*352*1000000+22050)/44100);
  }
  const auto audio = boxTag(output,"mp4a");
  assert(audio < boxTag(output,"moof"));
  mux.finish();
}
}

int main(int argc, char** argv) {
  verifyAvTimeline(true,960,nullptr);
  verifyAvTimeline(false,1024,argc == 3 ? argv[2] : nullptr);
  for (int samples : {352,480,512}) verifyAvTimeline(false,samples,nullptr);
  if (argc >= 2) {
    std::ifstream fixture(argv[1],std::ios::binary);
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(fixture)),std::istreambuf_iterator<char>());
    assert(!bytes.empty());
    verifyAlacConversion(bytes);
  }
  std::vector<uint8_t> previous;
  for (int cycle = 0; cycle < 2; ++cycle) {
    std::vector<uint8_t> output;
    H264Fmp4Muxer mux(320,180,parameters,[&](const uint8_t* data, size_t size) {output.insert(output.end(),data,data+size);});
    assert(output.size() > 8);
    const auto initial = output.size();
    refuses([&] {mux.appendAudio({0x21},0);});
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
