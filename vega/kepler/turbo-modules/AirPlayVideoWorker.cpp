#include "AirPlayVideoWorker.h"
#include "H264AnnexB.h"
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <limits>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <thread>

struct AirPlayVideoWorker::Impl {
  enum class Kind {Sample, Reset, End};
  struct Event {
    Kind kind = Kind::Reset;
    uint64_t generation = 0, ntp = 0;
    std::vector<uint8_t> bytes;
    bool clearParameters = false;
  };
  std::function<std::unique_ptr<VideoSampleSink>()> factory;
  mutable std::mutex mutex;
  std::condition_variable changed;
  std::deque<Event> queue;
  std::optional<AirPlayVideoCommand> command;
  std::shared_ptr<VideoSampleSink> active;
  std::thread worker;
  AirPlayVideoState observation;
  bool closing = true, processing = false, inputEnded = false;
  size_t inFlightBytes = 0;

  // Called with mutex held; cancel never joins the media worker. Keep this
  // ordering so a late cancellation cannot stop the next generation's output.
  void cancelLocked() noexcept {if (active) active->cancel();}
  void clearLocked() {queue.clear(); observation.queuedBytes = inFlightBytes;}
  void stopCommandLocked() {command = AirPlayVideoCommand{AirPlayVideoCommand::Kind::Stop,observation.generation,{}};}
  void errorLocked(const char* reason) {
    if (observation.error.empty()) observation.error = reason;
    observation.phase = "error"; clearLocked(); cancelLocked(); stopCommandLocked(); changed.notify_all();
  }
  bool cancelled(uint64_t generation) const {
    std::lock_guard lock(mutex);
    return closing || !observation.error.empty() || generation != observation.generation;
  }
  void resetLocked(bool clearParameters) {
    ++observation.generation; ++observation.flushes; inputEnded = false;
    clearLocked(); cancelLocked(); stopCommandLocked();
    queue.push_back({Kind::Reset,observation.generation,0,{},clearParameters}); changed.notify_all();
  }
  void run() noexcept {
    std::shared_ptr<VideoSampleSink> sink;
    uint64_t outputGeneration = 0, epoch = 0;
    int64_t lastPts = -1;
    bool prepared = false, draining = false;
    std::vector<uint8_t> sps, pps;
    auto connectionDeadline = std::chrono::steady_clock::now();
    auto release = [&] {
      if (sink) {
        sink->stop();
        const auto state = sink->state();
        std::lock_guard lock(mutex); observation.output = state; observation.released = state.released;
        if (!state.released) throw std::runtime_error("Video output release was not confirmed");
      }
      prepared = draining = false; epoch = 0; lastPts = -1;
    };
    try {
      sink = factory();
      if (!sink) throw std::runtime_error("Missing native video output");
      {std::lock_guard lock(mutex); active = sink;}
      for (;;) {
        Event event;
        bool haveEvent = false;
        {
          std::unique_lock lock(mutex);
          changed.wait_for(lock,std::chrono::milliseconds(25),[&] {return closing || !observation.error.empty() || !queue.empty();});
          if (closing || !observation.error.empty()) break;
          if (!queue.empty()) {
            event = std::move(queue.front()); queue.pop_front();
            processing = haveEvent = true; inFlightBytes = event.bytes.size();
          }
        }
        if (haveEvent) {
          try {
            if (!cancelled(event.generation)) {
              if (event.kind == Kind::Reset) {
                release();
                if (event.clearParameters) {sps.clear(); pps.clear();}
                std::lock_guard lock(mutex);
                if (event.generation == observation.generation && !closing) observation.phase = "waiting";
              } else if (event.kind == Kind::End) {
                if (prepared) {sink->finish(); draining = true;}
                else {std::lock_guard lock(mutex); observation.phase = "ended";}
              } else {
                const auto units = BuzinH264::split(event.bytes);
                bool idr = false, vcl = false, changedParameters = false;
                auto nextSps = sps, nextPps = pps;
                for (const auto& nal : units) {
                  if (nal.type == 7 || nal.type == 8) {
                    if (nal.size > 65536) throw std::invalid_argument("Unbounded H.264 parameters");
                    auto& next = nal.type == 7 ? nextSps : nextPps;
                    const std::vector<uint8_t> value(nal.bytes,nal.bytes+nal.size);
                    if (!next.empty() && next != value) changedParameters = true;
                    next = value;
                  }
                  idr = idr || nal.type == 5; vcl = vcl || nal.type == 1 || nal.type == 5;
                }
                if (nextSps.size()+nextPps.size()+8 > 65536) throw std::invalid_argument("Unbounded H.264 configuration");
                if (changedParameters && prepared) {
                  release();
                  std::lock_guard lock(mutex);
                  if (event.generation == observation.generation) stopCommandLocked();
                }
                sps = std::move(nextSps); pps = std::move(nextPps);
                if (vcl) {
                  int width, height;
                  {std::lock_guard lock(mutex); width = observation.width; height = observation.height;}
                  if (!prepared && (!idr || sps.empty() || pps.empty() || !width || !height)) {
                    std::lock_guard lock(mutex); ++observation.dropped;
                  } else {
                    if (!event.ntp) throw std::invalid_argument("Missing local video timestamp");
                    if (!prepared) {
                      {std::lock_guard lock(mutex); observation.phase = "preparing"; observation.released = false;}
                      std::vector<uint8_t> parameters{0,0,0,1};
                      parameters.insert(parameters.end(),sps.begin(),sps.end());
                      parameters.insert(parameters.end(),{0,0,0,1}); parameters.insert(parameters.end(),pps.begin(),pps.end());
                      const auto url = sink->start(width,height,parameters);
                      prepared = true; draining = false; epoch = event.ntp; lastPts = -1;
                      outputGeneration = event.generation;
                      connectionDeadline = std::chrono::steady_clock::now()+std::chrono::seconds(15);
                      std::lock_guard lock(mutex);
                      if (closing || event.generation != observation.generation || !observation.error.empty()) sink->cancel();
                      else command = AirPlayVideoCommand{AirPlayVideoCommand::Kind::Play,event.generation,url};
                    }
                    if (!cancelled(event.generation)) {
                      const auto delta = event.ntp >= epoch ? (event.ntp-epoch)/1000 : std::numeric_limits<uint64_t>::max();
                      if (delta > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) || static_cast<int64_t>(delta) <= lastPts) {
                        std::lock_guard lock(mutex); ++observation.dropped;
                      } else {
                        const auto pts = static_cast<int64_t>(delta);
                        // UxPlay supplies PTS, not decode timestamps/durations.
                        // Low-delay frames use DTS=PTS and observed cadence; the
                        // initial frame uses 30 Hz until a second PTS arrives.
                        const auto duration = lastPts < 0 ? int64_t(33333) : std::min<int64_t>(pts-lastPts,1000000);
                        sink->append(event.bytes,pts,pts,duration); lastPts = pts;
                        std::lock_guard lock(mutex); ++observation.accepted;
                      }
                    }
                  }
                }
              }
            }
          } catch (...) {
            if (!cancelled(event.generation)) {
              const auto outputError = sink->state().error;
              std::lock_guard lock(mutex); errorLocked(outputError.empty() ? "nativeVideoFailure" : outputError.c_str());
            }
          }
          // Free the completed callback copy before making its budget available
          // to the next transport callback, including while output state waits.
          std::vector<uint8_t>().swap(event.bytes);
          {std::lock_guard lock(mutex); observation.queuedBytes -= inFlightBytes; inFlightBytes = 0; processing = false;}
        }
        if (prepared && !cancelled(outputGeneration)) {
          const auto state = sink->state();
          {
            std::lock_guard lock(mutex); observation.output = state; observation.released = state.released;
            if (outputGeneration == observation.generation) {
              if (!state.error.empty()) errorLocked(state.error.c_str());
              else if (!state.connected && std::chrono::steady_clock::now() >= connectionDeadline) errorLocked("videoConsumerTimeout");
              else observation.phase = draining ? "draining" : state.connected ? "streaming" : "preparing";
            }
          }
          if (draining && state.complete) {
            release();
            std::lock_guard lock(mutex);
            if (outputGeneration == observation.generation && observation.error.empty()) observation.phase = "ended";
          }
        }
      }
    } catch (...) {std::lock_guard lock(mutex); errorLocked("nativeVideoFailure");}
    try {release();}
    catch (...) {std::lock_guard lock(mutex); observation.released = false; errorLocked("nativeReleaseFailure");}
    std::lock_guard lock(mutex);
    active.reset(); inFlightBytes = 0; processing = false; clearLocked();
    if (observation.error.empty()) observation.phase = "stopped";
  }
};

AirPlayVideoWorker::AirPlayVideoWorker(std::function<std::unique_ptr<VideoSampleSink>()> factory)
  : impl(std::make_unique<Impl>()) {impl->factory = std::move(factory);}
AirPlayVideoWorker::~AirPlayVideoWorker() {stop();}
void AirPlayVideoWorker::start() {
  stop();
  std::lock_guard lock(impl->mutex);
  if (!impl->observation.released) throw std::runtime_error("Previous native video release was not confirmed");
  const auto generation = impl->observation.generation+1;
  impl->observation = {}; impl->observation.generation = generation; impl->observation.phase = "waiting";
  impl->command.reset(); impl->inputEnded = false; impl->closing = false;
  try {impl->worker = std::thread([this] {impl->run();});}
  catch (...) {impl->closing = true; impl->observation.phase = "error"; impl->observation.error = "videoWorkerStartFailure"; throw;}
}
void AirPlayVideoWorker::stop() noexcept {
  {
    std::lock_guard lock(impl->mutex); impl->closing = true; ++impl->observation.generation;
    impl->clearLocked(); impl->cancelLocked(); impl->stopCommandLocked(); impl->changed.notify_all();
  }
  if (impl->worker.joinable()) impl->worker.join();
}
void AirPlayVideoWorker::format(int width, int height) noexcept {
  try {
    std::lock_guard lock(impl->mutex);
    if (impl->closing || !impl->observation.error.empty()) return;
    if (width < 16 || width > 3840 || height < 16 || height > 2160) {impl->errorLocked("invalidVideoSize"); return;}
    if (width == impl->observation.width && height == impl->observation.height) return;
    impl->observation.width = width; impl->observation.height = height; impl->resetLocked(true);
  } catch (...) {std::lock_guard lock(impl->mutex); impl->errorLocked("videoQueueFailure");}
}
void AirPlayVideoWorker::sample(const uint8_t* bytes, size_t size, bool h265, uint64_t ntp) noexcept {
  try {
    std::lock_guard lock(impl->mutex);
    if (impl->closing || !impl->observation.error.empty()) return;
    if (h265) {impl->errorLocked("unsupportedVideoCodec"); return;}
    if (!bytes || !size || size > 2*1024*1024) {impl->errorLocked("invalidVideoSample"); return;}
    if (impl->inputEnded) {++impl->observation.dropped; return;}
    if (impl->queue.size()+(impl->processing ? 1 : 0) >= 256 || impl->observation.queuedBytes+size > 8*1024*1024) {
      impl->errorLocked("videoQueueOverflow"); return;
    }
    Impl::Event event{Impl::Kind::Sample,impl->observation.generation,ntp,{}};
    event.bytes.assign(bytes,bytes+size); impl->queue.push_back(std::move(event));
    impl->observation.queuedBytes += size; impl->changed.notify_all();
  } catch (...) {std::lock_guard lock(impl->mutex); impl->errorLocked("videoQueueFailure");}
}
void AirPlayVideoWorker::flush() noexcept {
  try {std::lock_guard lock(impl->mutex); if (!impl->closing && impl->observation.error.empty()) impl->resetLocked(false);}
  catch (...) {std::lock_guard lock(impl->mutex); impl->errorLocked("videoQueueFailure");}
}
void AirPlayVideoWorker::end() noexcept {
  try {
    std::lock_guard lock(impl->mutex);
    if (impl->closing || impl->inputEnded || !impl->observation.error.empty()) return;
    if (impl->queue.size()+(impl->processing ? 1 : 0) >= 256) {impl->errorLocked("videoQueueOverflow"); return;}
    impl->queue.push_back({Impl::Kind::End,impl->observation.generation,0,{}});
    impl->inputEnded = true; impl->changed.notify_all();
  } catch (...) {std::lock_guard lock(impl->mutex); impl->errorLocked("videoQueueFailure");}
}
bool AirPlayVideoWorker::poll(AirPlayVideoCommand& command) {
  std::lock_guard lock(impl->mutex);
  if (!impl->command) return false;
  command = std::move(*impl->command); impl->command.reset(); return true;
}
AirPlayVideoState AirPlayVideoWorker::state() const {std::lock_guard lock(impl->mutex); return impl->observation;}
bool AirPlayVideoWorker::healthy() const {std::lock_guard lock(impl->mutex); return impl->observation.error.empty();}
