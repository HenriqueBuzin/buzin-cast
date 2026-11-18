#include "MsseAudioPlayer.h"
#include <apmf/array.h>
#include <apmf/ptr.h>
#include <deque>
#include <apmf/apmf_base.h>
#include <apmf/process.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IW3CMedia.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IHtmlMediaElementInitStatusListener.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IHtmlMediaElementDeInitStatusListener.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IMediaSampleSourceTrack3.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IHtmlMediaElement.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IMediaError.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/MediaErrorCode.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IConfigBuilder.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IConfig.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/PlayerMode.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IAudioParams.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/AudioStreamFormat.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IMediaSampleSource.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IMediaSample.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/IMediaSampleSourceQueueReturn.h>
#include <apmf/iface/com/amazon/kepler/w3cmedia/QueueMediaSampleStatus.h>
#include <apmf/iface/com/amazon/apmf/ISubscription.h>
#include <chrono>
#include <cmath>
#include <limits>
#include <condition_variable>
#include <mutex>
#include <stdexcept>

namespace media = apmf::iface::com::amazon::kepler::w3cmedia;
namespace {
struct Signal {
  std::mutex mutex;
  std::condition_variable changed;
  bool done = false;
  void notify() {std::lock_guard lock(mutex); done = true; changed.notify_all();}
  bool wait() {
    std::unique_lock lock(mutex);
    return changed.wait_for(lock, std::chrono::seconds(5), [&] {return done;});
  }
};
struct Initialized : apmf::ApmfBase<Initialized, media::IHtmlMediaElementInitStatusListener> {
  explicit Initialized(std::shared_ptr<Signal> value) : signal(std::move(value)) {}
  std::shared_ptr<Signal> signal;
  void onInitialized() {signal->notify();}
};
struct Released : apmf::ApmfBase<Released, media::IHtmlMediaElementDeInitStatusListener> {
  explicit Released(std::shared_ptr<Signal> value) : signal(std::move(value)) {}
  std::shared_ptr<Signal> signal;
  void onDeinitialized() {signal->notify();}
};
}
struct MsseAudioPlayer::Impl {
  apmf::Ptr<media::IW3CMedia> module;
  apmf::Ptr<media::IHtmlMediaElement> element;
  apmf::Ptr<media::IMediaSampleSource> source;
  apmf::Ptr<media::IMediaSampleSourceTrack3> track;
  struct Retained {
    apmf::Array<uint8_t> bytes;
    apmf::Ptr<media::IMediaSample> sample;
    int64_t endMs;
  };
  std::deque<Retained> retained;
  size_t retainedBytes = 0;
  int64_t previousTimestamp = -1;
  bool ended = false;
  bool releaseFailed = false;
  bool playingStarted = false;
  void check() {
    if (!element || !track) throw std::runtime_error("MSSE audio is not initialized");
    if (auto error = element->getError()) {
      const auto code = static_cast<int32_t>(error->getCode());
      if (code != 0) {
        const auto message = error->getMessage();
        throw std::runtime_error("MSSE native playback error " + std::to_string(code) + ": " + std::string(message.data(), message.size()));
      }
    }
  }
};
MsseAudioPlayer::MsseAudioPlayer() : impl(std::make_unique<Impl>()) {}
MsseAudioPlayer::~MsseAudioPlayer() {try {stop();} catch (...) {}}
void MsseAudioPlayer::startAac(int rate, int channels, const std::vector<uint8_t>& config) {
  if (rate < 8000 || rate > 96000 || channels < 1 || channels > 8 || config.empty() || config.size() > 64)
    throw std::invalid_argument("Invalid AAC stream parameters");
  stop();
  try {
    impl->module = apmf::GetProcessObject()->getComponent("/com.amazon.kepler.w3cmedia")
      .QueryInterface<media::IW3CMedia>();
    auto configuration = impl->module->makeConfigBuilder()->playerMode(media::PlayerMode::MSSE)->build();
    impl->element = impl->module->makeHtmlMediaElement(configuration);
    auto signal = std::make_shared<Signal>();
    auto listener = apmf::Ptr<Initialized>::Make(signal);
    auto subscription = impl->element->initializeAsync(listener);
    const bool ready = signal->wait();
    subscription->unsubscribe();
    if (!ready) throw std::runtime_error("MSSE initialization timed out");
    impl->element->setVolumeLevel(0.15);
    auto params = impl->module->makeAudioParams("audio/aac");
    params->disableEncryption();
    params->setAudioStreamFormat(media::AudioStreamFormat::RAW_AAC);
    params->setAudioChannelCount(channels);
    params->setAudioSampleRate(rate);
    params->setCodecSpecificConfigData(apmf::Array<uint8_t>(config.begin(), config.end()));
    impl->track = impl->module->makeMediaSampleSourceTrackForAudio(params)
      .QueryInterface<media::IMediaSampleSourceTrack3>();
    impl->track->start();
    impl->source = impl->module->makeMediaSampleSource(impl->element);
    impl->source->addTrack(impl->track);
    impl->element->setMediaSampleSource(impl->source);
    impl->previousTimestamp = -1; impl->ended = false; impl->playingStarted = false;
  } catch (...) {try {stop();} catch (...) {} throw;}
}
bool MsseAudioPlayer::append(const std::vector<uint8_t>& bytes, int64_t timestamp, int64_t duration) {
  impl->check();
  if (impl->ended || bytes.empty() || bytes.size() > 2 * 1024 * 1024 || timestamp < 0 ||
      timestamp < impl->previousTimestamp || duration <= 0 || duration > 1000 ||
      timestamp > std::numeric_limits<int64_t>::max() - duration - 1000)
    throw std::invalid_argument("Invalid MSSE audio access unit");
  const double now = impl->element->getCurrentTimeSecs() * 1000;
  while (!impl->retained.empty() && impl->retained.front().endMs + 1000 < now) {
    impl->retainedBytes -= impl->retained.front().bytes.size();
    impl->retained.pop_front();
  }
  if (impl->retainedBytes + bytes.size() > 8 * 1024 * 1024) return false;
  apmf::Array<uint8_t> owned(bytes.begin(), bytes.end());
  auto sample = impl->module->makeMediaSample(owned);
  sample->setPresentationTimestamp(timestamp);
  sample->setDecodingTimestamp(timestamp);
  sample->setDuration(duration);
  sample->setSyncFrame();
  auto result = impl->track->queueMediaSampleAsync(sample);
  const bool accepted = result->queueMediaSampleStatus() == media::QueueMediaSampleStatus::QUEUED;
  if (accepted) {
    if (!impl->playingStarted) {impl->element->play(); impl->playingStarted = true;}
    impl->previousTimestamp = timestamp;
    impl->retainedBytes += bytes.size();
    impl->retained.push_back({std::move(owned), sample, timestamp + duration});
  }
  return accepted;
}
double MsseAudioPlayer::position() {impl->check(); return impl->element->getCurrentTimeSecs();}
void MsseAudioPlayer::setVolume(double volume) {
  impl->check();
  if (!std::isfinite(volume) || volume < 0 || volume > 1) throw std::invalid_argument("Invalid audio gain");
  impl->element->setVolumeLevel(volume);
}
void MsseAudioPlayer::end() {impl->check(); if (!impl->ended) {impl->track->end(); impl->ended = true;}}
void MsseAudioPlayer::stop() {
  auto element = impl->element;
  impl->element = nullptr; impl->track = nullptr; impl->source = nullptr; impl->module = nullptr;
  impl->previousTimestamp = -1; impl->ended = false; impl->playingStarted = false;
  if (!element) {
    if (impl->releaseFailed) throw std::runtime_error("Previous MSSE release was not confirmed");
    return;
  }
  impl->releaseFailed = true;
  auto signal = std::make_shared<Signal>();
  auto listener = apmf::Ptr<Released>::Make(signal);
  auto subscription = element->deinitializeAsync(listener);
  const bool released = signal->wait();
  subscription->unsubscribe();
  if (!released) throw std::runtime_error("MSSE deinitialization timed out");
  impl->releaseFailed = false;
  impl->retained.clear(); impl->retainedBytes = 0;
}
