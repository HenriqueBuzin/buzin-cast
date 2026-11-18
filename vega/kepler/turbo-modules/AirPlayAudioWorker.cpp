#include "AirPlayAudioWorker.h"
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <thread>

struct AirPlayAudioWorker::Impl {
  enum class Kind {Format, Sample, Flush, Volume};
  struct Event {
    Event(Kind value = Kind::Flush) : kind(value) {}
    Kind kind;
    uint64_t generation = 0;
    int codec = 0, spf = 0;
    uint64_t ntp = 0;
    uint32_t rtp = 0;
    double volume = 0;
    std::vector<uint8_t> bytes;
  };
  std::function<std::unique_ptr<AudioSampleSink>()> factory;
  mutable std::mutex mutex;
  std::condition_variable changed;
  std::deque<Event> queue;
  std::thread worker;
  AirPlayAudioState observation;
  uint64_t generation = 0;
  double desiredVolumeDb = 0;
  bool closing = true;

  void error(const char* reason) {
    observation.error = reason; observation.phase = "error";
    queue.clear(); observation.queuedBytes = 0; changed.notify_all();
  }
  void post(Event event, bool reset = false) noexcept {
    try {
      std::lock_guard lock(mutex);
      if (closing || !observation.error.empty()) return;
      if (reset) {++generation; queue.clear(); observation.queuedBytes = 0;}
      if (event.kind == Kind::Volume) desiredVolumeDb = event.volume;
      event.generation = generation;
      if (queue.size() >= 256 || observation.queuedBytes + event.bytes.size() > 8 * 1024 * 1024) {
        error("queueOverflow"); return;
      }
      observation.queuedBytes += event.bytes.size();
      queue.push_back(std::move(event)); changed.notify_one();
    } catch (...) {try {std::lock_guard lock(mutex); error("queueFailure");} catch (...) {}}
  }
  bool cancelled(uint64_t epoch) {
    std::lock_guard lock(mutex);
    return closing || !observation.error.empty() || generation != epoch;
  }
  void run() noexcept {
    std::unique_ptr<AudioSampleSink> sink;
    bool prepared = false;
    int spf = 0, codec = 0;
    uint64_t firstNtp = 0, ticks = 0, frame = 0;
    uint32_t lastRtp = 0;
    int64_t lastPts = -1;
    bool haveEpoch = false;
    double gain = 1;
    try {
      sink = factory();
      if (!sink) throw std::runtime_error("Missing native sink");
      auto applyVolume = [&](bool active) {
        double db;
        {std::lock_guard lock(mutex); db = desiredVolumeDb; observation.volumeDb = db;}
        gain = db == -144 ? 0 : std::pow(10.0, db / 20.0);
        if (active) sink->setVolume(gain);
      };
      for (;;) {
        Event event{};
        bool available = false;
        {
          std::unique_lock lock(mutex);
          changed.wait_for(lock, std::chrono::milliseconds(25), [&] {return closing || !queue.empty() || !observation.error.empty();});
          if (closing || !observation.error.empty()) break;
          if (!queue.empty()) {
            event = std::move(queue.front()); queue.pop_front();
            observation.queuedBytes -= event.bytes.size(); available = true;
          }
        }
        if (available && (event.kind == Kind::Format || event.kind == Kind::Flush)) {
          sink->stop(); prepared = false; haveEpoch = false; frame = ticks = 0; lastPts = -1;
          if (event.kind == Kind::Format) {
            if (!((event.codec == 4 && (event.spf == 960 || event.spf == 1024)) ||
                  (event.codec == 8 && (event.spf == 480 || event.spf == 512)) ||
                  (event.codec == 2 && event.spf == 352))) {
              std::lock_guard lock(mutex); error("unsupportedAudioFormat"); break;
            }
            spf = event.spf; codec = event.codec;
          }
          applyVolume(false);
          std::lock_guard lock(mutex);
          observation.seconds = 0; observation.phase = "waiting"; observation.released = true;
          if (event.kind == Kind::Flush) ++observation.flushes;
        } else if (available && event.kind == Kind::Volume) {
          applyVolume(prepared);
        } else if (available && event.kind == Kind::Sample) {
          if (cancelled(event.generation)) continue;
          if (!spf || event.codec != codec) {
            std::lock_guard lock(mutex); error("audioBeforeFormat"); break;
          }
          if (!prepared) {
            {std::lock_guard lock(mutex); observation.phase = "preparing"; observation.released = false;}
            const std::vector<uint8_t> config = codec == 8
              ? std::vector<uint8_t>{0xf8, 0xe8, static_cast<uint8_t>(spf == 480 ? 0x50 : 0x40), 0x00}
              : std::vector<uint8_t>{0x12, static_cast<uint8_t>(spf == 960 ? 0x14 : 0x10)};
            if (codec == 2) sink->startAlac();
            else sink->startAac(44100, 2, config);
            applyVolume(true); prepared = true;
          }
          if (cancelled(event.generation)) continue;
          if (!haveEpoch) {firstNtp = event.ntp; lastRtp = event.rtp; ticks = 0; haveEpoch = true;}
          const auto delta = static_cast<int32_t>(event.rtp - lastRtp);
          if (delta < 0 || (firstNtp && event.ntp && event.ntp < firstNtp)) {
            std::lock_guard lock(mutex); ++observation.dropped; continue;
          }
          const auto nextTicks = ticks + static_cast<uint32_t>(delta);
          const auto pts = static_cast<int64_t>(firstNtp && event.ntp
            ? (event.ntp - firstNtp) / 1000000 : nextTicks * 1000 / 44100);
          if (pts < lastPts) {std::lock_guard lock(mutex); ++observation.dropped; continue;}
          const auto duration = static_cast<int64_t>(((frame + 1) * spf * 1000 / 44100) - (frame * spf * 1000 / 44100));
          const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
          bool accepted = false;
          while (!cancelled(event.generation)) {
            if (sink->append(event.bytes, pts, duration)) {accepted = true; break;}
            if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Native audio queue timeout");
            std::unique_lock lock(mutex); changed.wait_for(lock, std::chrono::milliseconds(10));
          }
          if (accepted) {
            ticks = nextTicks; lastRtp = event.rtp; lastPts = pts; ++frame;
            std::lock_guard lock(mutex); ++observation.accepted;
          }
        }
        if (prepared) {
          const double seconds = sink->position();
          if (!std::isfinite(seconds) || seconds < 0) throw std::runtime_error("Invalid native audio clock");
          std::lock_guard lock(mutex);
          observation.seconds = seconds;
          if (seconds > 0) observation.phase = "playing";
        }
      }
    } catch (...) {std::lock_guard lock(mutex); error("nativeAudioFailure");}
    bool released = true;
    try {if (sink) sink->stop();} catch (...) {released = false;}
    std::lock_guard lock(mutex);
    observation.released = released;
    if (!released) error("nativeReleaseFailure");
    if (observation.error.empty()) observation.phase = "stopped";
    queue.clear(); observation.queuedBytes = 0;
  }
};
AirPlayAudioWorker::AirPlayAudioWorker(std::function<std::unique_ptr<AudioSampleSink>()> factory)
  : impl(std::make_unique<Impl>()) {impl->factory = std::move(factory);}
AirPlayAudioWorker::~AirPlayAudioWorker() {stop();}
void AirPlayAudioWorker::start() {
  stop();
  std::lock_guard lock(impl->mutex);
  if (!impl->observation.released) throw std::runtime_error("Previous native audio release was not confirmed");
  impl->observation = {}; impl->observation.phase = "waiting";
  impl->closing = false; impl->desiredVolumeDb = 0; ++impl->generation;
  impl->worker = std::thread([this] {impl->run();});
}
void AirPlayAudioWorker::stop() noexcept {
  {std::lock_guard lock(impl->mutex); impl->closing = true; ++impl->generation; impl->changed.notify_all();}
  if (impl->worker.joinable()) impl->worker.join();
}
void AirPlayAudioWorker::format(int codec, int spf) noexcept {
  Impl::Event event{Impl::Kind::Format}; event.codec = codec; event.spf = spf; impl->post(std::move(event), true);
}
void AirPlayAudioWorker::sample(const uint8_t* bytes, size_t size, int codec, uint64_t ntp, uint32_t rtp) noexcept {
  if (!bytes || size == 0 || size > 2 * 1024 * 1024) {
    std::lock_guard lock(impl->mutex); impl->error("invalidAudioSample"); return;
  }
  try {
    Impl::Event event{Impl::Kind::Sample}; event.codec = codec; event.ntp = ntp; event.rtp = rtp;
    event.bytes.assign(bytes, bytes + size); impl->post(std::move(event));
  } catch (...) {std::lock_guard lock(impl->mutex); impl->error("queueFailure");}
}
void AirPlayAudioWorker::flush() noexcept {impl->post({Impl::Kind::Flush}, true);}
void AirPlayAudioWorker::volume(double db) noexcept {
  if (!std::isfinite(db) || (db != -144 && (db < -30 || db > 0))) {
    std::lock_guard lock(impl->mutex); impl->error("invalidAudioVolume"); return;
  }
  Impl::Event event{Impl::Kind::Volume}; event.volume = db;
  impl->post(std::move(event));
}
AirPlayAudioState AirPlayAudioWorker::state() const {std::lock_guard lock(impl->mutex); return impl->observation;}
bool AirPlayAudioWorker::healthy() const {return state().error.empty();}
