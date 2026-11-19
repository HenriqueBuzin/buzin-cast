#include "../kepler/turbo-modules/AirPlayVideoCallbacks.h"
#include "../kepler/turbo-modules/AirPlayAudioFormat.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <thread>

namespace {
const std::vector<uint8_t> config{0,0,0,1,0x67,0x42,0xc0,0x0c,0xda,0x05,0x06,0x7e,0x7c,0x04,0x40,0,0,3,0,0x40,0,0,7,0x83,0xc5,0x0a,0xa8,0,0,0,1,0x68,0xce,0x0f,0xc8};
const std::vector<uint8_t> idr{0,0,0,1,0x65,0x88,0x84}, delta{0,0,1,0x41,0x9a,0x20};
std::vector<uint8_t> initial() {auto bytes = config; bytes.insert(bytes.end(),idr.begin(),idr.end()); return bytes;}
template<class Function> void await(Function ready) {
  const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(5);
  while (!ready()) {assert(std::chrono::steady_clock::now() < deadline); std::this_thread::sleep_for(std::chrono::milliseconds(1));}
}
struct Trace {
  std::mutex mutex;
  std::condition_variable changed;
  LocalVideoStreamState state;
  bool holdStart = false, holdAppend = false, enteredStart = false, enteredAppend = false, cancelled = false, failRelease = false;
  unsigned starts = 0, stops = 0;
  int audioSamples = 0;
  std::thread::id owner;
  struct Sample {std::vector<uint8_t> bytes; int64_t pts, duration;};
  std::vector<Sample> samples, audio;
};
class Sink final : public VideoSampleSink {
  std::shared_ptr<Trace> trace;
  void owner() {assert(trace->owner == std::this_thread::get_id());}
public:
  explicit Sink(std::shared_ptr<Trace> value) : trace(std::move(value)) {trace->owner = std::this_thread::get_id();}
  std::string start(int width, int height, const std::vector<uint8_t>& parameters, int audioSamples) override {
    owner(); assert(width >= 16 && height >= 16 && !parameters.empty());
    std::unique_lock lock(trace->mutex);
    trace->state = {}; trace->state.released = false; trace->state.connected = true;
    trace->audioSamples = audioSamples;
    ++trace->starts; trace->cancelled = false; trace->enteredStart = true; trace->changed.notify_all();
    trace->changed.wait(lock,[&] {return !trace->holdStart || trace->cancelled;});
    if (trace->cancelled) throw std::runtime_error("Cancelled mock start");
    return "http://127.0.0.1:1234/"+std::to_string(trace->starts)+".mp4";
  }
  void appendAudio(const std::vector<uint8_t>& bytes, int64_t pts) override {
    owner();
    std::lock_guard lock(trace->mutex);
    assert(airPlayAudioCodec(trace->audioSamples) != 0);
    trace->audio.push_back({bytes,pts,trace->audioSamples});
  }
  void append(const std::vector<uint8_t>& bytes, int64_t pts, int64_t dts, int64_t duration) override {
    owner(); assert(dts == pts && duration > 0 && duration <= 1000000);
    std::unique_lock lock(trace->mutex); trace->enteredAppend = true; trace->changed.notify_all();
    trace->changed.wait(lock,[&] {return !trace->holdAppend || trace->cancelled;});
    if (trace->cancelled) throw std::runtime_error("Cancelled mock write");
    trace->samples.push_back({bytes,pts,duration});
  }
  void finish() override {owner(); std::lock_guard lock(trace->mutex); trace->state.complete = true;}
  void waitFinished(std::chrono::milliseconds) override {owner();}
  void cancel() noexcept override {std::lock_guard lock(trace->mutex); trace->cancelled = true; trace->changed.notify_all();}
  void stop() noexcept override {
    owner(); std::lock_guard lock(trace->mutex); ++trace->stops;
    trace->state.released = !trace->failRelease; trace->state.queuedBytes = 0;
  }
  LocalVideoStreamState state() const override {std::lock_guard lock(trace->mutex); return trace->state;}
};
struct Test {
  std::shared_ptr<Trace> trace = std::make_shared<Trace>();
  AirPlayVideoWorker worker{[this] {return std::make_unique<Sink>(trace);}};
  Test() {worker.start(); worker.format(320,180);}
  template<class Function> auto inspect(Function function) {std::lock_guard lock(trace->mutex); return function(*trace);}
  void submit(const std::vector<uint8_t>& bytes, uint64_t pts = 1000000000) {
    video_decode_struct frame{}; frame.data = const_cast<uint8_t*>(bytes.data()); frame.data_len = bytes.size(); frame.ntp_time_local = pts;
    forwardAirPlayVideo(worker,&frame);
  }
  void submitAudio(std::vector<uint8_t> bytes, uint64_t pts, int codec = 4) {
    audio_decode_struct frame{};
    frame.data = bytes.data();
    frame.data_len = bytes.size();
    frame.ct = codec;
    frame.ntp_time_local = pts;
    forwardAirPlayMirrorAudio(worker,&frame);
  }
  AirPlayVideoCommand play() {
    AirPlayVideoCommand command;
    await([&] {return worker.poll(command) && command.kind == AirPlayVideoCommand::Kind::Play;});
    return command;
  }
};
}
int main() {
  for (const bool audioFirst : {true,false}) {
    Test test;
    const auto base = uint64_t{1000000000};
    const auto audioStart = base+(audioFirst ? 0 : 80000000);
    const auto videoStart = base+(audioFirst ? 80000000 : 0);
    test.worker.audioFormat(4,1024);
    if (audioFirst) {
      test.submitAudio({0x21},audioStart);
      test.submitAudio({0x22},audioStart+23219954);
    }
    test.submit(initial(),videoStart);
    test.submit(delta,videoStart+20000000);
    test.submit(delta,videoStart+60000000);
    if (!audioFirst) test.submitAudio({0x21},audioStart);
    const auto first = test.play();
    await([&] {return test.worker.state().accepted == 2;});
    test.inspect([&](Trace& trace) {
      assert(trace.audioSamples == 1024);
      assert(trace.samples[0].pts == (audioFirst ? 80000 : 0));
      assert(trace.samples[0].duration == 20000 && trace.samples[1].duration == 40000);
      assert(trace.audio[0].pts == (audioFirst ? 0 : 80000));
      assert(trace.audio[0].bytes == std::vector<uint8_t>{0x21});
    });
    test.submitAudio({0xaa},audioStart);
    await([&] {return test.worker.state().audioDropped == 1;});
    test.worker.flush();
    test.submitAudio({0x23},base+200000000);
    test.submit(idr,base+240000000);
    test.submit(delta,base+260000000);
    const auto second = test.play();
    assert(second.generation > first.generation && second.url != first.url);
    test.worker.end();
    await([&] {return test.worker.state().phase == "ended";});
    test.inspect([&](Trace& trace) {
      assert(trace.samples.size() == 4);
      assert(trace.samples[2].pts == 40000 && trace.samples[3].pts == 60000);
      assert(trace.audio.back().pts == 0 && trace.audio.back().bytes == std::vector<uint8_t>{0x23});
    });
    assert(test.worker.state().queuedBytes == 0);
  }
  {
    Test test;
    test.worker.audioFormat(8,1024);
    test.submitAudio({0x21},1000000000,8);
    test.submit(initial());
    test.play();
    test.inspect([](Trace& trace) {assert(trace.audioSamples == 0 && trace.audio.empty());});
  }
  for (const int samples : {352,480,512}) {
    Test test;
    const auto codec = airPlayAudioCodec(samples);
    test.worker.audioFormat(codec,samples);
    test.submit(initial());
    test.submit(delta,1066667000);
    test.submitAudio({0x21},1080000000,4);
    test.submitAudio({0x22},1080000000,codec);
    test.play();
    test.inspect([&](Trace& trace) {
      assert(trace.audioSamples == samples && trace.audio.size() == 1);
      assert(trace.audio[0].pts == 80000 && trace.audio[0].bytes == std::vector<uint8_t>{0x22});
    });
  }
  {
    Test test;
    test.worker.audioFormat(4,960);
    test.submit(initial());
    test.submit(delta,1066667000);
    await([&] {return test.worker.state().error == "mirrorTrackTimeout";});
    test.worker.stop();
    assert(test.worker.state().queuedBytes == 0 && test.worker.state().released);
  }
  {
    Test test;
    test.submit(delta); await([&] {return test.worker.state().dropped == 1;});
    test.submit(config); test.submit(delta); await([&] {return test.worker.state().dropped == 2;});
    test.submit(idr); const auto first = test.play();
    await([&] {return test.worker.state().accepted == 1;});
    auto bytes = delta; test.submit(bytes,1066667000); bytes.assign(bytes.size(),0xff);
    await([&] {return test.worker.state().accepted == 2;});
    test.inspect([&](Trace& trace) {assert(trace.samples[0].pts == 0 && trace.samples[0].duration == 33333);
      assert(trace.samples[1].bytes == delta && trace.samples[1].pts == 66667 && trace.samples[1].duration == 66667);});
    test.submit(delta,1066667000); test.submit(delta,999999999);
    await([&] {return test.worker.state().dropped == 4;});
    const auto generation = test.worker.state().generation;
    test.worker.format(320,180); assert(test.worker.state().generation == generation);
    test.worker.flush();
    AirPlayVideoCommand command; assert(test.worker.poll(command) && command.kind == AirPlayVideoCommand::Kind::Stop);
    test.submit(delta,2000000000); await([&] {return test.worker.state().dropped == 5;});
    // Flush preserves the negotiated parameter sets, but still requires IDR.
    test.submit(idr,2000000000); const auto second = test.play();
    assert(second.generation > first.generation && second.url != first.url);
    await([&] {return test.worker.state().accepted == 3;});
    test.inspect([&](Trace& trace) {assert(trace.samples.back().pts == 0);});
    test.worker.format(640,360); test.submit(idr,3000000000);
    await([&] {return test.worker.state().dropped == 6;});
    test.submit(initial(),3000000000); test.play();
    await([&] {return test.worker.state().accepted == 4;});
    auto replacement = initial(); replacement[7] ^= 1; test.submit(replacement,4000000000); const auto changed = test.play();
    assert(changed.url != second.url);
    await([&] {return test.worker.state().accepted == 5;});
    test.inspect([&](Trace& trace) {assert(trace.samples.back().pts == 0);});
    auto configOnly = config; configOnly[7] ^= 2;
    test.submit(configOnly,4500000000);
    await([&] {const auto state = test.worker.state(); return state.phase == "waiting" && state.released;});
    test.submit(idr,4500000000); test.play();
    await([&] {return test.worker.state().accepted == 6;});
    test.worker.end(); test.worker.end();
    await([&] {return test.worker.state().phase == "ended";});
    assert(test.worker.state().released && test.worker.state().output.complete);
    test.submit(idr); await([&] {return test.worker.state().dropped == 7;});
    test.worker.stop(); test.worker.stop(); assert(test.worker.healthy());
  }
  {
    Test test;
    test.inspect([](Trace& trace) {trace.holdStart = true;});
    test.submit(initial()); await([&] {return test.inspect([](Trace& trace) {return trace.enteredStart;});});
    test.worker.format(640,360); // Cancels the old startup, without publishing its URL.
    test.inspect([](Trace& trace) {trace.holdStart = false; trace.changed.notify_all();});
    test.submit(initial(),2000000000); const auto current = test.play();
    assert(current.generation == test.worker.state().generation && current.url.ends_with("2.mp4"));
    await([&] {return test.worker.state().accepted == 1;});
    assert(test.worker.healthy());
  }
  {
    Test test;
    test.inspect([](Trace& trace) {trace.holdAppend = true;});
    test.submit(initial()); await([&] {return test.inspect([](Trace& trace) {return trace.enteredAppend;});});
    const auto before = std::chrono::steady_clock::now(); test.worker.stop();
    assert(std::chrono::steady_clock::now()-before < std::chrono::seconds(1));
    assert(test.worker.state().released && test.worker.state().queuedBytes == 0 && test.worker.healthy());
    AirPlayVideoCommand command; assert(test.worker.poll(command) && command.kind == AirPlayVideoCommand::Kind::Stop);
  }
  for (bool byteLimit : {true,false}) {
    Test test;
    test.inspect([](Trace& trace) {trace.holdAppend = true;});
    test.submit(initial()); await([&] {return test.inspect([](Trace& trace) {return trace.enteredAppend;});});
    std::vector<uint8_t> bytes = byteLimit ? std::vector<uint8_t>(2*1024*1024,42) : delta;
    for (int i = 0; i < 260 && test.worker.healthy(); ++i) {
      test.submit(bytes,2000000000+uint64_t(i)*1000000);
      assert(test.worker.state().queuedBytes <= 8*1024*1024);
    }
    await([&] {return !test.worker.healthy() && test.worker.state().released;});
    assert(test.worker.state().error == "videoQueueOverflow");
    test.worker.stop(); assert(test.worker.state().queuedBytes == 0);
  }
  {
    Test test; test.submit(initial()); test.play();
    await([&] {return test.worker.state().accepted == 1;});
    test.inspect([](Trace& trace) {trace.state.error = "clientClosed";});
    await([&] {return test.worker.state().error == "clientClosed" && test.worker.state().released;});
  }
  {
    Test test; test.submit(initial()); test.play();
    test.inspect([](Trace& trace) {trace.failRelease = true;});
    test.worker.stop(); assert(!test.worker.state().released && !test.worker.healthy());
    bool refused = false; try {test.worker.start();} catch (...) {refused = true;} assert(refused);
  }
  for (int invalid = 0; invalid < 6; ++invalid) {
    Test test;
    if (invalid == 0) {const float width = std::numeric_limits<float>::quiet_NaN(), height = 180; forwardAirPlayVideoSize(test.worker,&width,&height);}
    if (invalid == 1) test.worker.sample(idr.data(),idr.size(),true,1000000000);
    if (invalid == 2) test.worker.sample(nullptr,1,false,1000000000);
    if (invalid == 3) test.submit({0,0,1,0xe5});
    if (invalid == 4) test.submit(std::vector<uint8_t>(2*1024*1024+1));
    if (invalid == 5) test.submit(initial(),0);
    await([&] {return !test.worker.healthy() && test.worker.state().released;});
  }
  {
    Test test; test.worker.end(); await([&] {return test.worker.state().phase == "ended";});
    assert(test.worker.state().released);
  }
}
