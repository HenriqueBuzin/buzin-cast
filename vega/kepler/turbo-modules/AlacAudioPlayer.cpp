#include "AlacAudioPlayer.h"
#include "AlacDecoder.h"
#include <apmf/apmf_base.h>
#include <apmf/errors.h>
#include <apmf/process.h>
#include <apmf/ptr.h>
#include <apmf/array_view.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioStream.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioStreamPlayback.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioStreamPlaybackBuilder.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioFocus.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioFocusAttributes.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioFocusAttributesBuilder.h>
#include <apmf/iface/com/amazon/kepler/audio/IAudioFocusSession.h>
#include <apmf/iface/com/amazon/kepler/audio/IFocusChangedListener.h>
#include <apmf/iface/com/amazon/kepler/audio/AudioUsage.h>
#include <apmf/iface/com/amazon/kepler/audio/SampleFormat.h>
#include <apmf/iface/com/amazon/kepler/audio/ChannelMask.h>
#include <apmf/iface/com/amazon/kepler/audio/AudioContentType.h>
#include <apmf/iface/com/amazon/kepler/audio/AudioStreamTag.h>
#include <apmf/iface/com/amazon/kepler/audio/SampleLayout.h>
#include <apmf/iface/com/amazon/apmf/ISubscription.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <mutex>
#include <stdexcept>
namespace audio = apmf::iface::com::amazon::kepler::audio;
namespace {
struct FocusState {
  std::mutex mutex;
  std::condition_variable changed;
  bool granted = false, terminal = false;
  void update(bool ready, bool stop = false) {
    std::lock_guard lock(mutex); terminal = terminal || stop; granted = ready && !terminal; changed.notify_all();
  }
};
struct FocusListener : apmf::ApmfBase<FocusListener,audio::IFocusChangedListener> {
  std::shared_ptr<FocusState> state;
  explicit FocusListener(std::shared_ptr<FocusState> value) : state(std::move(value)) {}
  void onFocusGranted() {state->update(true);}
  void onFocusDucked() {state->update(true);}
  void onFocusPaused() {state->update(false);}
  void onFocusStopped() {state->update(false,true);}
  void onFocusReleased() {state->update(false,true);}
  void onFocusMuted() {state->update(true);}
};
}
struct AlacAudioPlayer::Impl {
  std::unique_ptr<AlacDecoder> decoder;
  apmf::Ptr<audio::IAudioStreamPlayback> playback;
  apmf::Ptr<audio::IAudioFocusSession> focus;
  apmf::Ptr<apmf::iface::com::amazon::apmf::ISubscription> subscription;
  std::shared_ptr<FocusState> focusState;
  int64_t previousTimestamp = -1;
  size_t blockBytes = 0;
  std::vector<uint8_t> pending;
  bool releaseFailed = false;
};
AlacAudioPlayer::AlacAudioPlayer() : impl(std::make_unique<Impl>()) {}
AlacAudioPlayer::~AlacAudioPlayer() {try {stop();} catch (...) {}}
void AlacAudioPlayer::start() {
  stop();
  const char* stage = "decoder";
  try {
    impl->decoder = std::make_unique<AlacDecoder>();
    auto process = apmf::GetProcessObject();
    stage = "audio stream component";
    auto stream = process->getComponent("/com.amazon.kepler.audiostream").QueryInterface<audio::IAudioStream>();
    stage = "audio focus component";
    auto focus = process->getComponent("/com.amazon.kepler.audiofocus").QueryInterface<audio::IAudioFocus>();
    impl->focusState = std::make_shared<FocusState>();
    stage = "focus session";
    impl->focus = focus->makeAudioFocusSession();
    stage = "focus listener";
    impl->subscription = impl->focus->addFocusChangedListener(apmf::Ptr<FocusListener>::Make(impl->focusState));
    auto attributes = focus->makeAudioFocusAttributesBuilder()->usage(audio::AudioUsage::MEDIA)->build();
    stage = "request audio focus";
    impl->focus->requestAudioFocus(attributes);
    {std::unique_lock lock(impl->focusState->mutex);
      if (!impl->focusState->changed.wait_for(lock,std::chrono::seconds(5),[&] {return impl->focusState->granted || impl->focusState->terminal;}) || !impl->focusState->granted)
        throw std::runtime_error("ALAC audio focus was not granted");
    }
    audio::AudioConfig config{.sampleRate = 44100, .sampleFormat = audio::SampleFormat::PCM_16_BIT,
      .channelMask = audio::ChannelMask::MASK_STEREO, .sampleLayout = audio::SampleLayout::INTERLEAVED};
    audio::AudioAttributes params{.contentType = audio::AudioContentType::MUSIC, .usage = audio::AudioUsage::MEDIA,
      .flags = {}, .tag = audio::AudioStreamTag::MUSIC};
    int32_t frames = 0, count = 0;
    stage = "minimum PCM buffers";
    stream->queryMinimumBufferInfo(config,params,&frames,&count);
    if (frames <= 0 || count <= 0 || int64_t(frames)*count > 44100)
      throw std::runtime_error("Unbounded native PCM buffer configuration");
    stage = "build PCM stream";
    impl->blockBytes = static_cast<size_t>(frames)*4;
    impl->playback = stream->makeAudioPlaybackBuilder()->audioConfig(config)->audioAttributes(params)
      ->framesPerBuffer(frames)->bufferCount(count)->audioFocusSessionId(impl->focus->getSessionId())->build();
    stage = "PCM initialization check";
    if (!impl->playback->initCheck()) throw std::runtime_error("Native PCM stream initialization failed");
    stage = "start PCM stream";
    impl->playback->setVolume(0); impl->playback->start(); impl->previousTimestamp = -1;
  } catch (const apmf::BaseError& error) {
    try {stop();} catch (...) {}
    throw std::runtime_error(std::string("ALAC ")+stage+": "+error.what());
  } catch (...) {try {stop();} catch (...) {} throw;}
}
bool AlacAudioPlayer::append(const std::vector<uint8_t>& packet, int64_t timestamp, int64_t duration) {
  if (!impl->playback || timestamp < 0 || timestamp < impl->previousTimestamp || duration < 7 || duration > 8)
    throw std::invalid_argument("Invalid ALAC stream sample");
  {std::lock_guard lock(impl->focusState->mutex);
    if (impl->focusState->terminal) throw std::runtime_error("ALAC audio focus stopped");
    if (!impl->focusState->granted) return false;
  }
  auto pcm = impl->decoder->decode(packet);
  impl->pending.insert(impl->pending.end(),pcm.begin(),pcm.end());
  // AudioStream requires writes at least one configured buffer in size. RAOP's
  // 352-frame units are smaller, so retain only the bounded partial next block.
  while (impl->pending.size() >= impl->blockBytes) {
    const auto count = impl->playback->write(apmf::ArrayView<uint8_t>(impl->pending.data(),impl->blockBytes));
    if (count != static_cast<int32_t>(impl->blockBytes)) throw std::runtime_error("Partial native PCM block write");
    impl->pending.erase(impl->pending.begin(),impl->pending.begin()+count);
  }
  impl->previousTimestamp = timestamp; return true;
}
double AlacAudioPlayer::position() {
  if (!impl->playback) throw std::runtime_error("PCM stream is not initialized");
  const auto frames = impl->playback->getPresentedFrameCount();
  if (frames < 0) throw std::runtime_error("Invalid PCM playback clock");
  return frames/44100.0;
}
void AlacAudioPlayer::setVolume(double volume) {
  if (!impl->playback || !std::isfinite(volume) || volume < 0 || volume > 1) throw std::invalid_argument("Invalid PCM volume");
  impl->playback->setVolume(static_cast<int32_t>(std::lround(volume*100)));
}
void AlacAudioPlayer::stop() {
  if (impl->releaseFailed) throw std::runtime_error("Previous PCM release was not confirmed");
  bool failed = false;
  if (impl->playback) {
    try {impl->playback->pause(); impl->playback->flush(); impl->playback->stop();} catch (...) {failed = true;}
    impl->playback = nullptr;
  }
  if (impl->focus) {try {impl->focus->releaseAudioFocus();} catch (...) {failed = true;} impl->focus = nullptr;}
  if (impl->subscription) {try {impl->subscription->unsubscribe();} catch (...) {failed = true;} impl->subscription = nullptr;}
  impl->focusState.reset(); impl->decoder.reset(); impl->previousTimestamp = -1;
  impl->pending.clear(); impl->blockBytes = 0;
  impl->releaseFailed = failed;
  if (failed) throw std::runtime_error("Native PCM release failed");
}
