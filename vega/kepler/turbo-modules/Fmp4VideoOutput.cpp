#include "Fmp4VideoOutput.h"
#include "H264Fmp4Muxer.h"
#include <mutex>
#include <stdexcept>

struct Fmp4VideoOutput::Impl {
  struct Run {
    LocalVideoStream stream;
    // Declared after stream: muxer is destroyed first, including on exceptions.
    std::unique_ptr<H264Fmp4Muxer> mux;
  };
  mutable std::mutex mutex;
  std::shared_ptr<Run> run;
  std::shared_ptr<Run> current() const {
    std::lock_guard lock(mutex);
    return run;
  }
  std::shared_ptr<Run> active() const {
    auto value = current();
    if (!value || !value->mux) throw std::runtime_error("Native video output is closed");
    return value;
  }
};
Fmp4VideoOutput::Fmp4VideoOutput() : impl(std::make_unique<Impl>()) {}
Fmp4VideoOutput::~Fmp4VideoOutput() {stop();}
std::string Fmp4VideoOutput::start(int width, int height, const std::vector<uint8_t>& parameters,
                                 int aacSamplesPerFrame) {
  stop();
  auto next = std::make_shared<Impl::Run>();
  std::string url;
  {
    // Publish only after start has reset the stream's cancellation predicate.
    // A concurrent cancel then cannot be overwritten by that reset.
    std::lock_guard lock(impl->mutex);
    url = next->stream.start();
    impl->run = next;
  }
  try {
    next->mux = std::make_unique<H264Fmp4Muxer>(width,height,parameters,
      [run = next.get()](const uint8_t* data, size_t size) {run->stream.write(data,size);}, aacSamplesPerFrame);
    return url;
  } catch (...) {stop(); throw;}
}
void Fmp4VideoOutput::appendAac(const std::vector<uint8_t>& sample, int64_t ptsUs) {
  impl->active()->mux->appendAac(sample, ptsUs);
}
void Fmp4VideoOutput::append(const std::vector<uint8_t>& sample, int64_t pts, int64_t dts, int64_t duration) {
  impl->active()->mux->append(sample,pts,dts,duration);
}
void Fmp4VideoOutput::finish() {
  const auto run = impl->active();
  run->mux->finish();
  run->stream.finish();
}
void Fmp4VideoOutput::waitFinished(std::chrono::milliseconds timeout) {
  impl->active()->stream.waitFinished(timeout);
}
void Fmp4VideoOutput::cancel() noexcept {
  if (const auto run = impl->current()) run->stream.cancel();
}
void Fmp4VideoOutput::stop() noexcept {
  if (const auto run = impl->current()) {
    run->stream.cancel();
    // Cancellation wakes any output callback before the owner destroys its mux.
    run->mux.reset();
    run->stream.stop();
  }
}
LocalVideoStreamState Fmp4VideoOutput::state() const {
  const auto run = impl->current();
  return run ? run->stream.state() : LocalVideoStreamState{};
}
