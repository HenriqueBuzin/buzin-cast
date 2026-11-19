#include "RelaySender.h"
#include "AacPcmDecoder.h"
#include "AlacDecoder.h"
#include "AirPlayAudioFormat.h"
#include "BuzinRelayWire.h"
#include "H264AnnexB.h"
#include "LocalTransfer.h"

#include <condition_variable>
#include <deque>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <utility>

namespace {
using BuzinRelay::Packet;
using BuzinRelay::Clock;
using namespace std::chrono_literals;
constexpr size_t MaxQueueBytes = 8 * 1024 * 1024;
constexpr size_t MaxQueueSamples = 240;
constexpr uint64_t BaseTimestamp = 1000000000;

std::string pairingCode(const std::string& input) {
  std::string code;
  if (input.size() > 40) throw std::invalid_argument("Relay code is too long");
  for (auto character : input) {
    if (character == '-' || character == ' ') continue;
    if (character >= 'a' && character <= 'z') character = static_cast<char>(character - 'a' + 'A');
    if (!((character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9')))
      throw std::invalid_argument("Relay code contains unsupported characters");
    code += character;
  }
  if (code.size() != 12) throw std::invalid_argument("Relay requires a 12-character code");
  return code;
}

uint64_t timestamp(int64_t ptsUs) {
  constexpr auto latest = (std::numeric_limits<int64_t>::max() - BaseTimestamp) / 1000;
  if (ptsUs < 0 || static_cast<uint64_t>(ptsUs) > latest) throw std::invalid_argument("Invalid mirror time");
  return BaseTimestamp + static_cast<uint64_t>(ptsUs) * 1000;
}

class AudioDecoder {
public:
  BuzinRelay::Bytes decode(int frameSamples, const BuzinRelay::Bytes& data) {
    if (airPlayAudioCodec(frameSamples) == 2) {
      if (!alac) alac = std::make_unique<AlacDecoder>();
      return alac->decode(data);
    }
    if (previousAac != frameSamples) {
      aac = std::make_unique<AacPcmDecoder>(frameSamples);
      previousAac = frameSamples;
    }
    return aac->decode(data);
  }
private:
  std::unique_ptr<AlacDecoder> alac;
  std::unique_ptr<AacPcmDecoder> aac;
  int previousAac = 0;
};
}

struct RelaySender::Impl {
  enum class Phase {Idle, Connecting, Ready, Sending, Ending, Ended, Error};
  struct Item {Packet packet; int audioSamples = 0;};
  std::atomic<Phase> phase{Phase::Idle};
  std::atomic<bool> active{false};
  std::mutex mutex;
  std::condition_variable changed;
  std::deque<Item> queue;
  size_t queuedBytes = 0;
  bool primed = false, finish = false;
  int64_t firstVideoPts = 0;
  std::thread worker;

  void clear() {
    queue.clear();
    queuedBytes = 0;
  }

  void failLocked() {
    active = false;
    phase = Phase::Error;
    clear();
    changed.notify_all();
  }

  void enqueue(Item item) {
    const auto bytes = item.packet.data.size();
    if (bytes > MaxQueueBytes - queuedBytes || queue.size() >= MaxQueueSamples) {
      failLocked();
      return;
    }
    queue.push_back(std::move(item));
    queuedBytes += bytes;
    changed.notify_one();
  }

  void requestEnd() {
    finish = true;
    phase = Phase::Ending;
    changed.notify_one();
  }

  bool canQueue() const {
    const auto state = phase.load();
    return active && (state == Phase::Ready || state == Phase::Sending);
  }

  void run(const std::string& host, uint16_t port, const std::string& code) noexcept {
    try {
      const LocalTransfer::Socket socket{LocalTransfer::connect(active, host, port, Clock::now() + 5s)};
      BuzinRelay::Channel channel(socket.fd, active);
      channel.connect(code);
      AudioDecoder decoder;
      phase = Phase::Ready;
      while (active) {
        Item item{};
        bool packetReady = false, done = false;
        {
          std::unique_lock lock(mutex);
          changed.wait_for(lock, 1s, [&] {return !active || finish || !queue.empty();});
          if (!active) return;
          if (!queue.empty()) {
            item = std::move(queue.front());
            queue.pop_front();
            queuedBytes -= item.packet.data.size();
            packetReady = true;
          } else {
            done = finish;
          }
        }
        if (done) {
          channel.send({3}, Clock::now() + 5s);
          phase = Phase::Ended;
          break;
        }
        if (!packetReady) {
          channel.send({}, Clock::now() + 5s);
          continue;
        }
        if (item.audioSamples) {
          item.packet.data = decoder.decode(item.audioSamples, item.packet.data);
          if (item.packet.data.empty()) continue;
        }
        channel.send(BuzinRelay::encode(item.packet), Clock::now() + 5s);
      }
    } catch (...) {
      std::lock_guard lock(mutex);
      if (active) failLocked();
    }
    active = false;
  }
};

RelaySender::RelaySender() : impl(std::make_unique<Impl>()) {}
RelaySender::~RelaySender() {stop();}

void RelaySender::start(const std::string& host, uint16_t port, const std::string& input) {
  const auto code = pairingCode(input);
  if (!port || host.find('\0') != host.npos || !LocalTransfer::isLocalHost(host))
    throw std::invalid_argument("Enter a local IPv4 address and a valid port");
  stop();
  impl->active = true;
  impl->phase = Impl::Phase::Connecting;
  try {
    impl->worker = std::thread([this, host, port, code] {impl->run(host, port, code);});
  } catch (...) {
    impl->active = false;
    impl->phase = Impl::Phase::Error;
    throw;
  }
}

std::string RelaySender::state() const {
  switch (impl->phase.load()) {
    case Impl::Phase::Connecting: return "connecting";
    case Impl::Phase::Ready: return "waiting";
    case Impl::Phase::Sending: return "sending";
    case Impl::Phase::Ending: return "ending";
    case Impl::Phase::Ended: return "ended";
    case Impl::Phase::Error: return "error";
    default: return "idle";
  }
}

void RelaySender::video(int width, int height, const std::vector<uint8_t>& config,
                        const std::vector<uint8_t>& sample, int64_t ptsUs) noexcept {
  if (!impl->canQueue()) return;
  std::lock_guard lock(impl->mutex);
  if (!impl->canQueue()) return;
  try {
    BuzinRelay::Bytes data;
    if (!impl->primed) {
      bool idr = false, sps = false, pps = false;
      for (const auto& nal : BuzinH264::split(sample)) {
        idr |= nal.type == 5;
        sps |= nal.type == 7;
        pps |= nal.type == 8;
      }
      if (!idr) return;
      if (!sps || !pps) data = config;
      impl->firstVideoPts = ptsUs;
      impl->primed = true;
      impl->phase = Impl::Phase::Sending;
    }
    if (data.size() + sample.size() > 2 * 1024 * 1024) throw std::runtime_error("Relay video access unit too large");
    data.insert(data.end(), sample.begin(), sample.end());
    impl->enqueue({{Packet::Kind::Video, timestamp(ptsUs), width, height, false, std::move(data)}});
  } catch (...) {impl->failLocked();}
}

void RelaySender::audio(int samplesPerFrame, const std::vector<uint8_t>& sample, int64_t ptsUs) noexcept {
  if (!impl->canQueue()) return;
  std::lock_guard lock(impl->mutex);
  if (!impl->canQueue() || !impl->primed || ptsUs < impl->firstVideoPts) return;
  try {
    impl->enqueue({{Packet::Kind::Audio, timestamp(ptsUs), 0, 0, false, sample}, samplesPerFrame});
  } catch (...) {impl->failLocked();}
}

void RelaySender::endSource() noexcept {
  if (!impl->active) return;
  std::lock_guard lock(impl->mutex);
  if (!impl->active || !impl->primed || impl->finish) return;
  impl->requestEnd();
}

void RelaySender::finish() noexcept {
  {
    std::lock_guard lock(impl->mutex);
    if (impl->canQueue()) {
      impl->requestEnd();
      return;
    }
    if (impl->phase == Impl::Phase::Ending) return;
  }
  stop();
}

void RelaySender::stop() noexcept {
  {
    std::lock_guard lock(impl->mutex);
    impl->active = false;
    impl->clear();
    impl->finish = false;
    impl->primed = false;
    impl->changed.notify_all();
  }
  if (impl->worker.joinable()) impl->worker.join();
  impl->phase = Impl::Phase::Idle;
}
