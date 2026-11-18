#include "../kepler/turbo-modules/AirPlayAudioWorker.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <mutex>
#include <thread>

struct Trace {
  std::mutex mutex;
  int starts = 0, stops = 0, attempts = 0;
  double seconds = 0, gain = 0;
  std::vector<int64_t> pts;
  std::vector<std::vector<uint8_t>> configs;
  std::atomic<bool> blocked{false};
  std::atomic<bool> releaseFails{false};
};
struct Sink : AudioSampleSink {
  std::shared_ptr<Trace> trace;
  bool active = false;
  explicit Sink(std::shared_ptr<Trace> value) : trace(std::move(value)) {}
  void startAac(int rate, int channels, const std::vector<uint8_t>& config) override {
    assert(rate == 44100 && channels == 2);
    assert(config == std::vector<uint8_t>({0x12, 0x10}) || config == std::vector<uint8_t>({0x12, 0x14}) ||
      config == std::vector<uint8_t>({0xf8, 0xe8, 0x50, 0}) || config == std::vector<uint8_t>({0xf8, 0xe8, 0x40, 0}));
    std::lock_guard lock(trace->mutex); ++trace->starts; trace->configs.push_back(config); trace->seconds = 0; active = true;
  }
  bool append(const std::vector<uint8_t>& bytes, int64_t pts, int64_t duration) override {
    assert(active && !bytes.empty() && duration >= 10 && duration <= 24);
    std::lock_guard lock(trace->mutex); ++trace->attempts;
    if (trace->blocked) return false;
    trace->pts.push_back(pts); trace->seconds = (pts + duration) / 1000.0; return true;
  }
  double position() override {std::lock_guard lock(trace->mutex); return trace->seconds;}
  void setVolume(double value) override {std::lock_guard lock(trace->mutex); trace->gain = value;}
  void stop() override {
    std::lock_guard lock(trace->mutex);
    if (active && trace->releaseFails) throw std::runtime_error("release failed");
    if (active) ++trace->stops; active = false;
  }
};
template<class Predicate> void waitFor(Predicate predicate) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
  while (!predicate()) {
    assert(std::chrono::steady_clock::now() < deadline);
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
}
int main() {
  auto trace = std::make_shared<Trace>();
  AirPlayAudioWorker worker([=] {return std::make_unique<Sink>(trace);});
  const uint8_t data[] = {1,2,3};
  worker.start(); worker.format(4,1024); worker.volume(-18);
  worker.sample(data,3,4,1000000000000ULL,0xfffffe00);
  worker.sample(data,3,4,1000023219954ULL,0x200);
  waitFor([&] {return worker.state().accepted == 2;});
  {std::lock_guard lock(trace->mutex);
    assert(trace->pts == std::vector<int64_t>({0,23}));
    assert(std::abs(trace->gain - std::pow(10., -18./20.)) < 0.0001);
  }
  // A backward packet is not allowed to rewind the active native timeline.
  worker.sample(data,3,4,999000000000ULL,0x100);
  waitFor([&] {return worker.state().dropped == 1;});
  trace->blocked = true;
  worker.sample(data,3,4,1000046439909ULL,0x600);
  waitFor([&] {std::lock_guard lock(trace->mutex); return trace->attempts > 3;});
  worker.flush();
  waitFor([&] {return worker.state().flushes == 1;});
  trace->blocked = false;
  worker.sample(data,3,4,2000000000000ULL,100);
  waitFor([&] {return worker.state().accepted == 3;});
  worker.stop(); worker.stop();
  assert(worker.state().released && worker.state().phase == "stopped");
  {std::lock_guard lock(trace->mutex); assert(trace->starts == 2 && trace->stops == 2 && trace->pts.back() == 0);}
  worker.start(); worker.format(2,352);
  waitFor([&] {return !worker.healthy();});
  assert(worker.state().error == "unsupportedAudioFormat"); worker.stop();
  // The negotiated frame size selects the real AAC configuration and RTP clock.
  for (const auto [codec, spf] : {std::pair{4,960}, std::pair{8,480}, std::pair{8,512}}) {
    worker.start(); worker.format(codec,spf);
    size_t before;
    {std::lock_guard lock(trace->mutex); before = trace->pts.size();}
    for (int i = 0; i < 3; ++i)
      worker.sample(data,3,codec,0,static_cast<uint32_t>(0xffffff00ULL+i*spf));
    waitFor([&] {return worker.state().accepted == 3;});
    {std::lock_guard lock(trace->mutex);
      const std::vector<int64_t> expected = spf == 960 ? std::vector<int64_t>{0,21,43}
        : spf == 480 ? std::vector<int64_t>{0,10,21} : std::vector<int64_t>{0,11,23};
      assert(std::vector<int64_t>(trace->pts.begin()+before, trace->pts.end()) == expected);
      const std::vector<uint8_t> config = spf == 960 ? std::vector<uint8_t>{0x12,0x14}
        : spf == 480 ? std::vector<uint8_t>{0xf8,0xe8,0x50,0} : std::vector<uint8_t>{0xf8,0xe8,0x40,0};
      assert(trace->configs.back() == config);
    }
    worker.stop(); assert(worker.state().released);
  }
  // A new codec releases the previous sink, retains mute and starts a fresh epoch.
  worker.start(); worker.format(4,1024); worker.volume(-144);
  worker.sample(data,3,4,1,0);
  waitFor([&] {return worker.state().accepted == 1;});
  worker.format(8,480); worker.sample(data,3,8,2000000000000ULL,100);
  waitFor([&] {return worker.state().accepted == 2;});
  {std::lock_guard lock(trace->mutex); assert(trace->gain == 0 && trace->pts.back() == 0);}
  worker.flush(); waitFor([&] {return worker.state().flushes == 1;});
  worker.sample(data,3,8,3000000000000ULL,1000);
  waitFor([&] {return worker.state().accepted == 3;});
  worker.stop(); assert(worker.state().released);
  // A packet from another codec must never enter the configured decoder.
  worker.start(); worker.format(8,512); worker.sample(data,3,4,1,0);
  waitFor([&] {return !worker.healthy();});
  assert(worker.state().accepted == 0); worker.stop();
  worker.start(); worker.format(8,960);
  waitFor([&] {return !worker.healthy();});
  assert(worker.state().error == "unsupportedAudioFormat"); worker.stop();
  // Missing NTP observations use an unwrapped RTP sample clock.
  worker.start(); worker.format(4,1024);
  worker.sample(data,3,4,0,0xfffffe00); worker.sample(data,3,4,0,0x200);
  waitFor([&] {return worker.state().accepted == 2;});
  worker.volume(-144);
  waitFor([&] {std::lock_guard lock(trace->mutex); return trace->gain == 0;});
  worker.stop();
  worker.start(); worker.format(4,1024); worker.sample(nullptr,0,4,0,0);
  assert(!worker.healthy()); worker.stop();
  worker.start(); worker.format(4,1024); worker.volume(1);
  assert(!worker.healthy()); worker.stop();
  // Mute received before a format change survives queue invalidation.
  worker.start(); worker.volume(-144); worker.format(4,1024);
  worker.sample(data,3,4,1,0);
  waitFor([&] {return worker.state().accepted == 1;});
  {std::lock_guard lock(trace->mutex); assert(trace->gain == 0);}
  worker.stop();
  // Backpressure cannot grow the queue indefinitely or hang shutdown.
  worker.start(); worker.format(4,1024); trace->blocked = true;
  worker.sample(data,3,4,1,0);
  waitFor([&] {return worker.state().phase == "preparing";});
  for (int i=0; i<300; ++i) worker.sample(data,3,4,1ULL+i*23219954ULL,i*1024);
  assert(worker.state().error == "queueOverflow"); worker.stop();
  trace->blocked = false;
  worker.start(); worker.format(4,1024); worker.sample(data,3,4,1,0);
  waitFor([&] {return worker.state().accepted == 1;});
  trace->releaseFails = true; worker.stop();
  assert(!worker.state().released && worker.state().error == "nativeReleaseFailure");
  bool refused = false;
  try {worker.start();} catch (const std::runtime_error&) {refused = true;}
  assert(refused);
}
