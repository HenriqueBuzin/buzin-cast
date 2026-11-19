#include "AirPlaySession.h"
#include "AirPlayAudioWorker.h"
#include "AirPlayAudioCallbacks.h"
#include "AirPlayVideoCallbacks.h"
#include "MdnsDiscovery.h"
#include "ReceiverSettings.h"
#include <arpa/inet.h>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <stdexcept>
extern "C" {
#include "raop.h"
#include "android_dnssd_shim.h"
}

struct AirPlaySession::Impl {
  std::mutex mutex;
  std::deque<AirPlayEvent> events;
  size_t queuedBytes = 0;
  playback_info_t playback{};
  double requestedVolumeDb = 0;
  bool volumePending = false;
  std::atomic<bool> failed{false};
  bool loopbackOnly = true;
  AirPlayAudioWorker* audio = nullptr;
  AirPlayVideoWorker* video = nullptr;
  raop_t* receiver = nullptr;
  dnssd_t* discovery = nullptr;
  MdnsDiscovery mdns;

  void push(AirPlayEvent event) noexcept {
    try {
      std::lock_guard lock(mutex);
      if (events.size() >= 128 || event.bytes.size() + queuedBytes > 8 * 1024 * 1024) {
        failed = true;
        return;
      }
      const auto bytes = event.bytes.size();
      events.push_back(std::move(event));
      queuedBytes += bytes;
    } catch (...) {failed = true;}
  }
  void simple(AirPlayEvent::Kind kind, double value = 0) noexcept {
    if (!std::isfinite(value)) {failed = true; return;}
    AirPlayEvent event; event.kind = kind; event.value = value; push(std::move(event));
  }
  void setVolume(double value) {
    // A burst from the sender replaces the pending gain without occupying
    // the playback command queue. Ignore invalid updates to keep playing.
    if (!std::isfinite(value) || (value != -144 && (value < -30 || value > 0))) return;
    std::lock_guard lock(mutex);
    requestedVolumeDb = value;
    volumePending = true;
  }
  void sample(AirPlayEvent::Kind kind, const unsigned char* data, int length,
              int codec, uint64_t local, uint64_t remote) noexcept {
    if (!data || length <= 0 || length > 2 * 1024 * 1024) {failed = true; return;}
    try {
      AirPlayEvent event; event.kind = kind; event.codec = codec;
      event.localTime = local; event.remoteTime = remote;
      event.bytes.assign(data, data + length); push(std::move(event));
    } catch (...) {failed = true;}
  }
  static Impl& self(void* cls) {return *static_cast<Impl*>(cls);}
};

AirPlaySession::AirPlaySession() : impl(std::make_unique<Impl>()) {}
AirPlaySession::~AirPlaySession() {stop();}
uint16_t AirPlaySession::start(const std::string& keyPath, const std::string& name,
                             const std::string& identity, bool loopbackOnly, AirPlayAudioWorker* audio, AirPlayVideoWorker* video) {
  if (impl->receiver) throw std::runtime_error("AirPlay session already running");
  validateReceiverName(name);
  unsigned int octets[6]; char trailing;
  if (std::sscanf(identity.c_str(), "%2x:%2x:%2x:%2x:%2x:%2x%c", &octets[0], &octets[1], &octets[2], &octets[3], &octets[4], &octets[5], &trailing) != 6)
    throw std::invalid_argument("Invalid local AirPlay identity");
  char address[6];
  for (int i = 0; i < 6; ++i) {
    if (octets[i] > 255) throw std::invalid_argument("Invalid local AirPlay identity");
    address[i] = static_cast<char>(octets[i]);
  }
  impl->failed = false;
  impl->loopbackOnly = loopbackOnly;
  impl->audio = audio;
  impl->video = video;
  raop_callbacks_t callbacks{};
  callbacks.cls = impl.get();
  callbacks.mirror_end_on_eof = video != nullptr;
  callbacks.conn_admit = [](void* cls, const char* peer) {
    in_addr ip{};
    if (!peer || inet_pton(AF_INET, peer, &ip) != 1) return 0;
    const auto value = ntohl(ip.s_addr);
    if (Impl::self(cls).loopbackOnly) return (value >> 24) == 127 ? 1 : 0;
    return ((value >> 24) == 127 || (value >> 24) == 10 || (value >> 20) == 0xac1 || (value >> 16) == 0xc0a8) ? 1 : 0;
  };
  callbacks.audio_process = [](void* cls, raop_ntp_t*, audio_decode_struct* data) {
    auto& state = Impl::self(cls);
    if (data && state.audio) forwardAirPlayAudio(*state.audio, data);
    else if (data && state.video) forwardAirPlayMirrorAudio(*state.video, data);
    else if (data) state.sample(AirPlayEvent::Kind::Audio, data->data, data->data_len, data->ct, data->ntp_time_local, data->ntp_time_remote);
  };
  callbacks.video_process = [](void* cls, raop_ntp_t*, video_decode_struct* data) {
    auto& state = Impl::self(cls);
    if (state.video) {forwardAirPlayVideo(*state.video,data); return;}
    if (state.audio) {state.failed = true; return;}
    if (data) state.sample(AirPlayEvent::Kind::Video, data->data, data->data_len, data->is_h265 ? 265 : 264, data->ntp_time_local, data->ntp_time_remote);
  };
  callbacks.video_set_codec = [](void* cls, video_codec_t codec) {
    const auto& state = Impl::self(cls);
    return (state.video || !state.audio) && codec == VIDEO_CODEC_H264 ? 0 : -1;
  };
  callbacks.video_report_size = [](void* cls, float*, float*, float* width, float* height) {
    if (auto* video = Impl::self(cls).video) forwardAirPlayVideoSize(*video,width,height);
  };
  callbacks.video_pause = [](void* cls) {Impl::self(cls).simple(AirPlayEvent::Kind::Rate, 0);};
  callbacks.video_resume = [](void* cls) {Impl::self(cls).simple(AirPlayEvent::Kind::Rate, 1);};
  callbacks.video_flush = [](void* cls) {
    auto& state = Impl::self(cls);
    // UxPlay emits this notification on every control socket close, even
    // for rejected or auxiliary requests with no mirror transport.
    if (!state.video) state.simple(AirPlayEvent::Kind::FlushVideo);
  };
  callbacks.mirror_video_running = [](void* cls, bool running) {
    auto& state = Impl::self(cls);
    if (state.video && !running) state.video->disconnect();
  };
  callbacks.audio_flush = [](void* cls) {
    auto& state = Impl::self(cls);
    if (state.audio) state.audio->flush();
    else if (state.video) state.video->flush();
    else state.simple(AirPlayEvent::Kind::FlushAudio);
  };
  callbacks.audio_get_format = [](void* cls, unsigned char* codec, unsigned short* spf, bool*, bool*, uint64_t*) {
    auto& state = Impl::self(cls);
    if (state.audio) state.audio->format(codec ? *codec : 0, spf ? *spf : 0);
    else if (state.video) state.video->audioFormat(codec ? *codec : 0, spf ? *spf : 0);
  };
  callbacks.audio_set_volume = [](void* cls, float value) {
    auto& state = Impl::self(cls);
    if (state.audio) state.audio->volume(value);
    else state.setVolume(value);
  };
  callbacks.video_reset = [](void* cls, reset_type_t reason) {
    auto& state = Impl::self(cls);
    // In mirror mode the actual media thread reports its end above.
    if (!state.video) state.simple(AirPlayEvent::Kind::Reset, reason);
  };
  callbacks.conn_feedback = [](void* cls) {Impl::self(cls).simple(AirPlayEvent::Kind::Feedback);};
  callbacks.audio_set_client_volume = [](void* cls) {
    auto& state = Impl::self(cls);
    if (state.audio) return state.audio->state().volumeDb;
    std::lock_guard lock(state.mutex);
    return state.requestedVolumeDb;
  };
  callbacks.on_video_play = [](void* cls, const char* url, float position) {
    auto& state = Impl::self(cls);
    if (state.audio || state.video) {state.failed = true; return;}
    if (!url || strnlen(url, 8193) > 8192 || !std::isfinite(position) || position < 0) {state.failed = true; return;}
    try {AirPlayEvent event; event.kind = AirPlayEvent::Kind::PlayUrl;
      event.url = url; event.value = position; state.push(std::move(event));
    } catch (...) {state.failed = true;}
  };
  callbacks.on_video_scrub = [](void* cls, float position) {Impl::self(cls).simple(AirPlayEvent::Kind::Seek, position);};
  callbacks.on_video_rate = [](void* cls, float rate) {Impl::self(cls).simple(AirPlayEvent::Kind::Rate, rate);};
  callbacks.on_video_stop = [](void* cls) {
    auto& state = Impl::self(cls);
    if (state.video) state.video->flush(); else state.simple(AirPlayEvent::Kind::Stop);
  };
  callbacks.on_video_wait_ready = [](void* cls) {Impl::self(cls).simple(AirPlayEvent::Kind::Ready);};
  callbacks.on_video_playlist_remove = [](void*) {return 0.0f;};
  callbacks.on_video_acquire_playback_info = [](void* cls, playback_info_t* info) {
    if (info) {auto& state = Impl::self(cls); std::lock_guard lock(state.mutex); *info = state.playback;}
  };
  try {
    // Startup of either worker is covered by the same rollback as transport.
    if (audio) audio->start();
    if (video) video->start(true);
    impl->receiver = raop_init(&callbacks);
    if (!impl->receiver || raop_init2(impl->receiver, 1, identity.c_str(), keyPath.c_str()) != 0)
      throw std::runtime_error("Could not initialize AirPlay session");
    raop_set_log_level(impl->receiver, 0);
    int error = 0;
    impl->discovery = dnssd_init(name.c_str(), static_cast<int>(name.size()), address, sizeof(address), &error, 0);
    if (!impl->discovery) throw std::runtime_error("Could not initialize AirPlay identity");
    const bool advertise = video && !loopbackOnly;
    for (int bit = 0; bit < 64; ++bit) dnssd_set_airplay_features(impl->discovery, bit, 0);
    if (advertise) {
      // H.264 mirroring, audio, FairPlay, and legacy pairing.
      for (int bit : {7, 9, 12, 14, 18, 19, 20, 21, 22, 27, 30})
        dnssd_set_airplay_features(impl->discovery, bit, 1);
      android_dnssd_set_compressed_audio_only(impl->discovery);
    }
    raop_set_dnssd(impl->receiver, impl->discovery);
    raop_set_plist(impl->receiver, "hls", audio || video ? 0 : 1);
    unsigned short port = 0;
    if (dnssd_register_airplay(impl->discovery, port) != 0 ||
        dnssd_register_raop(impl->discovery, port) != 0)
      throw std::runtime_error("Could not prepare AirPlay identity records");
    if (raop_start_httpd(impl->receiver, &port) < 0 || !port)
      throw std::runtime_error("Could not start AirPlay transport");
    raop_set_port(impl->receiver, port);
    if (advertise) {
      int airplayLength = 0;
      int raopLength = 0;
      const char* airplayTxt = dnssd_get_airplay_txt(impl->discovery, &airplayLength);
      const char* raopTxt = dnssd_get_raop_txt(impl->discovery, &raopLength);
      const std::string raopName = android_dnssd_get_raop_servname(impl->discovery);
      std::string host = "buzincast-";
      for (const char c : identity) if (c != ':') host += c;
      try {
        impl->mdns.start(host + ".local", {
          {name, "_airplay._tcp.local", port, {airplayTxt, airplayTxt + airplayLength}},
          {raopName, "_raop._tcp.local", port, {raopTxt, raopTxt + raopLength}},
        });
      } catch (const std::runtime_error& error) {
        std::fprintf(stderr, "AirPlay discovery unavailable: %s\n", error.what());
      }
    }
    return port;
  } catch (...) {stop(); throw;}
}
void AirPlaySession::stop() noexcept {
  impl->mdns.stop();
  if (impl->receiver) {raop_destroy(impl->receiver); impl->receiver = nullptr;}
  if (impl->discovery) {dnssd_destroy(impl->discovery); impl->discovery = nullptr;}
  if (impl->audio) {impl->audio->stop(); impl->audio = nullptr;}
  if (impl->video) {impl->video->stop(); impl->video = nullptr;}
  std::lock_guard lock(impl->mutex);
  impl->events.clear(); impl->queuedBytes = 0; impl->playback = {};
  impl->requestedVolumeDb = 0; impl->volumePending = false;
}
bool AirPlaySession::poll(AirPlayEvent& event) {
  if (impl->video) {
    AirPlayVideoCommand command;
    if (impl->video->poll(command)) {
      event = {}; event.kind = command.kind == AirPlayVideoCommand::Kind::Play ? AirPlayEvent::Kind::MirrorUrl : AirPlayEvent::Kind::Stop;
      event.url = std::move(command.url); return true;
    }
  }
  std::lock_guard lock(impl->mutex);
  if (impl->volumePending) {
    impl->volumePending = false;
    event = {};
    event.kind = AirPlayEvent::Kind::Volume;
    event.value = impl->requestedVolumeDb == -144 ? 0 : std::pow(10.0, impl->requestedVolumeDb / 20.0);
    return true;
  }
  if (impl->events.empty()) return false;
  event = std::move(impl->events.front()); impl->events.pop_front();
  impl->queuedBytes -= event.bytes.size(); return true;
}
double AirPlaySession::volumeDb() const {
  std::lock_guard lock(impl->mutex);
  return impl->requestedVolumeDb;
}
bool AirPlaySession::healthy() const {
  std::lock_guard lock(impl->mutex);
  return !impl->failed && (!impl->audio || impl->audio->healthy()) && (!impl->video || impl->video->healthy());
}

bool AirPlaySession::advertising() const noexcept { return impl->mdns.running(); }

void AirPlaySession::updatePlayback(double position, double duration, double rate, bool ready) {
  if (!std::isfinite(position) || !std::isfinite(duration) || !std::isfinite(rate) ||
      position < 0 || duration < 0 || rate < 0 || rate > 4)
    throw std::invalid_argument("Invalid AirPlay playback observation");
  std::lock_guard lock(impl->mutex);
  impl->playback.position = position;
  impl->playback.duration = duration;
  impl->playback.seek_duration = duration;
  impl->playback.rate = static_cast<float>(rate);
  impl->playback.ready_to_play = ready;
  impl->playback.playback_buffer_empty = !ready;
  impl->playback.playback_likely_to_keep_up = ready;
}
