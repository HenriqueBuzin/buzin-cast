#include "RelayReceiver.h"
#include "BuzinRelayWire.h"
#include "Fmp4VideoOutput.h"
#include "LocalTransfer.h"
#include "PcmAacEncoder.h"

#include <algorithm>
#include <deque>
#include <openssl/rand.h>
#include <poll.h>
#include <stdexcept>

namespace {
using namespace std::chrono_literals;
using BuzinRelay::Clock;
using BuzinRelay::Packet;
constexpr uint64_t NsPerSecond = 1000000000;
constexpr uint64_t Epoch = 2 * NsPerSecond;
constexpr int Aac = 4;
constexpr int Rate = 44100;
constexpr size_t StereoFrameBytes = 4;

std::string pairingCode() {
  constexpr char alphabet[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  static_assert(sizeof(alphabet) == 33);
  unsigned char bytes[12];
  if (RAND_bytes(bytes, sizeof(bytes)) != 1) throw std::runtime_error("Cannot generate relay pairing code");
  std::string code;
  for (const auto byte : bytes) code += alphabet[byte % 32];
  return code;
}

class Feeder {
public:
  explicit Feeder(AirPlayVideoWorker& worker) : worker(worker) {}

  void handle(Packet packet) {
    if (packet.hevc) throw std::invalid_argument("Vega relay requires H.264");
    if (started) {
      feed(packet);
      return;
    }
    if (pending.empty()) warmup = Clock::now() + 400ms;
    if (!hasVideo && packet.kind == Packet::Kind::Audio) {
      while (!pending.empty() && (pending.size() >= 24 || bytes + packet.data.size() > 2 * 1024 * 1024 ||
             (packet.timestamp > pending.front().timestamp &&
              packet.timestamp - pending.front().timestamp > 400000000))) {
        bytes -= pending.front().data.size();
        pending.pop_front();
      }
    }
    bytes += packet.data.size();
    if (pending.size() >= 144 || bytes > 8 * 1024 * 1024)
      throw std::runtime_error("Relay media startup buffer exceeded");
    hasAudio |= packet.kind == Packet::Kind::Audio;
    hasVideo |= packet.kind == Packet::Kind::Video;
    pending.push_back(std::move(packet));
    tick(false);
  }

  void tick(bool final) {
    if (started || !hasVideo || (!final && !hasAudio && Clock::now() < warmup)) return;
    // The Kotlin sender may deliver PCM before the first video packet.
    origin = std::min_element(pending.begin(), pending.end(), [](const auto& a, const auto& b) {
      return a.timestamp < b.timestamp;
    })->timestamp;
    if (hasAudio) enableAudio();
    started = true;
    for (const auto& packet : pending) feed(packet);
    pending.clear();
  }

  void finish() {
    tick(true);
    if (encoder) encoder->finish();
    worker.end();
  }

private:
  AirPlayVideoWorker& worker;
  std::deque<Packet> pending;
  size_t bytes = 0;
  bool hasAudio = false, hasVideo = false, started = false;
  Clock::time_point warmup;
  uint64_t origin = 0;
  int64_t nextTicks = -1;
  std::unique_ptr<PcmAacEncoder> encoder;

  void enableAudio() {
    worker.audioFormat(Aac, PcmAacEncoder::FrameSamples);
    encoder = std::make_unique<PcmAacEncoder>([&](const auto& packet, int64_t ticks) {
      // Convert 44.1-kHz ticks to nanoseconds without multiplying the entire
      // live clock. The existing video worker keeps both tracks on this epoch.
      const auto ns = ticks / Rate * NsPerSecond +
          (ticks % Rate * NsPerSecond + Rate / 2) / Rate;
      worker.audioSample(packet.data(), packet.size(), Aac, ns);
    });
  }

  void feed(const Packet& packet) {
    if (packet.timestamp > origin && packet.timestamp - origin > 24 * 60 * 60 * NsPerSecond)
      throw std::invalid_argument("Relay timestamp outside the session");
    if (packet.timestamp < origin && origin - packet.timestamp >= Epoch) return;
    const auto time = packet.timestamp >= origin ? Epoch + (packet.timestamp - origin) :
        Epoch - (origin - packet.timestamp);
    if (packet.kind == Packet::Kind::Video) {
      worker.format(packet.width, packet.height);
      worker.sample(packet.data.data(), packet.data.size(), false, time);
      return;
    }
    // Late audio announces the track to the muxer, which restarts at the next IDR.
    if (!encoder) enableAudio();
    auto ticks = static_cast<int64_t>(time / NsPerSecond * Rate +
        (time % NsPerSecond * Rate + NsPerSecond / 2) / NsPerSecond);
    if (nextTicks >= 0 && (ticks == nextTicks + 1 || ticks == nextTicks - 1)) ticks = nextTicks;
    const auto samples = static_cast<int64_t>(packet.data.size() / StereoFrameBytes);
    const auto overlap = std::max<int64_t>(0, nextTicks - ticks);
    if (overlap >= samples) return;
    const auto skip = static_cast<size_t>(overlap) * StereoFrameBytes;
    encoder->append(packet.data.data() + skip, packet.data.size() - skip, ticks + overlap);
    nextTicks = ticks + samples;
  }
};
}

RelayReceiver::RelayReceiver() : video([] {return std::make_unique<Fmp4VideoOutput>();}) {}
RelayReceiver::~RelayReceiver() {stop();}

RelayReceiver::Endpoint RelayReceiver::start() {
  stop();
  Endpoint endpoint;
  endpoint.code = pairingCode();
  LocalTransfer::Socket listener{LocalTransfer::listen(endpoint.address)};
  video.start();
  active = true;
  phase = Phase::Waiting;
  try {
    worker = std::thread([this, fd = listener.fd, code = endpoint.code] {listen(fd, code);});
    listener.fd = -1;
  } catch (...) {
    stop();
    throw;
  }
  return endpoint;
}

void RelayReceiver::listen(int fd, const std::string& code) noexcept {
  const LocalTransfer::Socket listener{fd};
  try {
    const auto pairingEnd = Clock::now() + 5min;
    for (int attempts = 0; attempts < 8 && LocalTransfer::waitFor(active, fd, POLLIN, pairingEnd);) {
      const LocalTransfer::Socket peer{LocalTransfer::accept(fd)};
      if (peer.fd < 0) continue;
      ++attempts;
      BuzinRelay::Channel channel(peer.fd, active);
      try {channel.accept(code);} catch (...) {continue;}
      phase = Phase::Receiving;
      Feeder feeder(video);
      auto idleEnd = Clock::now() + 15s;
      while (active && Clock::now() < idleEnd && video.healthy()) {
        if (LocalTransfer::waitFor(active, peer.fd, POLLIN, std::min(idleEnd, Clock::now() + 75ms))) {
          auto payload = channel.receive(std::min(idleEnd, Clock::now() + 8s));
          idleEnd = Clock::now() + 15s;
          if (!payload.empty()) {
            auto packet = BuzinRelay::decode(std::move(payload));
            if (packet.kind == Packet::Kind::End) {
              feeder.finish();
              phase = Phase::Ending;
              return;
            }
            feeder.handle(std::move(packet));
          }
        }
        feeder.tick(false);
      }
      if (active) throw std::runtime_error("Relay media stopped");
      return;
    }
    if (active) phase = Phase::Expired;
  } catch (...) {
    if (active) {
      video.stop();
      phase = Phase::Error;
    }
  }
}

std::string RelayReceiver::state() const {
  switch (phase.load()) {
    case Phase::Waiting: return "waiting";
    case Phase::Receiving: return "receiving";
    case Phase::Ending: {
      const auto output = video.state();
      if (!output.error.empty()) return "error";
      return output.phase == "ended" ? "ended" : "ending";
    }
    case Phase::Error: return "error";
    case Phase::Expired: return "expired";
    default: return "idle";
  }
}

bool RelayReceiver::poll(AirPlayVideoCommand& command) {return video.poll(command);}

void RelayReceiver::stop() noexcept {
  active = false;
  if (worker.joinable()) worker.join();
  video.stop();
  phase = Phase::Idle;
}
