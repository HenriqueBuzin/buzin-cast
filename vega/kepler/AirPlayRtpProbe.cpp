#include "AirPlayRtpProbe.h"
#include "turbo-modules/AirPlayAudioCallbacks.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <stdexcept>
#include <thread>
extern "C" {
#include "raop_rtp.h"
#include "crypto.h"
#include "byteutils.h"
}

namespace {
void put16(uint8_t* bytes, uint16_t value) {bytes[0] = value >> 8; bytes[1] = value & 255;}
void put32(uint8_t* bytes, uint32_t value) {for (int i=0; i<4; ++i) bytes[i] = (value >> (24-i*8)) & 255;}
int datagram(uint16_t* port) {
  const int fd = socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0) throw std::runtime_error("Could not open loopback UDP socket");
  sockaddr_in local{}; local.sin_family = AF_INET; local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  socklen_t length = sizeof(local);
  if (bind(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) ||
      getsockname(fd, reinterpret_cast<sockaddr*>(&local), &length)) {
    close(fd); throw std::runtime_error("Could not bind loopback UDP socket");
  }
  timeval timeout{0, 100000};
  if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))) {
    close(fd); throw std::runtime_error("Could not bound UDP receive wait");
  }
  *port = ntohs(local.sin_port); return fd;
}
void transmit(int fd, uint16_t port, const std::vector<uint8_t>& packet) {
  sockaddr_in target{}; target.sin_family = AF_INET; target.sin_addr.s_addr = htonl(INADDR_LOOPBACK); target.sin_port = htons(port);
  if (sendto(fd, packet.data(), packet.size(), 0, reinterpret_cast<sockaddr*>(&target), sizeof(target)) != static_cast<ssize_t>(packet.size()))
    throw std::runtime_error("Could not send loopback RTP fixture");
}
}
struct AirPlayRtpProbe::Impl {
  AirPlayAudioWorker& worker;
  const std::vector<std::vector<uint8_t>>& fixture;
  std::unique_ptr<logger_t, decltype(&logger_destroy)> logger{nullptr, logger_destroy};
  std::unique_ptr<raop_ntp_t, decltype(&raop_ntp_destroy)> ntp{nullptr, raop_ntp_destroy};
  std::unique_ptr<raop_rtp_t, decltype(&raop_rtp_destroy)> rtp{nullptr, raop_rtp_destroy};
  std::unique_ptr<aes_ctx_t, decltype(&aes_cbc_destroy)> cipher{nullptr, aes_cbc_destroy};
  int sender = -1, timer = -1;
  uint16_t controlPort = 0, dataPort = 0, ntpPort = 0, sequence = 65530;
  const int codec, spf;
  bool socketsReleased = false;
  static constexpr uint32_t baseRtp = 0xfffff000;
  std::atomic<bool> closing{false}, mismatch{false};
  std::atomic<uint64_t> received{0}, timed{0}, duplicateCount{0};
  std::thread timing;
  Impl(AirPlayAudioWorker& output, const std::vector<std::vector<uint8_t>>& input, int ct, int frameSamples)
    : worker(output), fixture(input), codec(ct), spf(frameSamples) {}
  ~Impl() {
    rtp.reset(); ntp.reset(); closing = true;
    if (timing.joinable()) timing.join();
    if (sender >= 0) close(sender);
    if (timer >= 0) close(timer);
  }
  void initialize() {
    uint16_t timerPort = 0, ignored = 0;
    timer = datagram(&timerPort); sender = datagram(&ignored);
    logger.reset(logger_init());
    if (!logger) throw std::runtime_error("Could not create native probe logger");
    logger_set_level(logger.get(), 0);
    raop_callbacks_t callbacks{}; callbacks.cls = this;
    callbacks.audio_process = [](void* cls, raop_ntp_t*, audio_decode_struct* frame) {
      auto& self = *static_cast<Impl*>(cls);
      if (!frame) {self.mismatch = true; return;}
      const uint32_t delta = frame->rtp_time - baseRtp;
      const size_t index = delta / self.spf;
      if (delta % self.spf || index >= self.fixture.size() || frame->data_len < 0 ||
          static_cast<size_t>(frame->data_len) != self.fixture[index].size() ||
          std::memcmp(frame->data, self.fixture[index].data(), self.fixture[index].size())) {
        self.mismatch = true; return;
      }
      ++self.received;
      if (frame->ntp_time_local && frame->ntp_time_remote) ++self.timed;
      forwardAirPlayAudio(self.worker, frame);
    };
    callbacks.audio_flush = [](void* cls) {static_cast<Impl*>(cls)->worker.flush();};
    callbacks.audio_set_volume = [](void* cls, float db) {static_cast<Impl*>(cls)->worker.volume(db);};
    timing_protocol_t protocol = NTP;
    ntp.reset(raop_ntp_init(logger.get(), &callbacks, "127.0.0.1", 4, timerPort, &protocol));
    if (!ntp) throw std::runtime_error("Could not create native NTP session");
    // Public deterministic test material, never sender credentials or keys.
    const uint8_t key[16] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
    const uint8_t iv[16] = {16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31};
    cipher.reset(aes_cbc_init(key, iv, AES_ENCRYPT));
    rtp.reset(raop_rtp_init(logger.get(), &callbacks, ntp.get(), "127.0.0.1", 4, key, iv));
    if (!rtp || !cipher) throw std::runtime_error("Could not initialize RTP/AES probe");
    timing = std::thread([this] {
      while (!closing) {
        uint8_t request[128]{}; sockaddr_in peer{}; socklen_t length = sizeof(peer);
        const auto receivedBytes = recvfrom(timer, request, sizeof(request), 0, reinterpret_cast<sockaddr*>(&peer), &length);
        if (receivedBytes != 32 || request[1] != 0xd2) continue;
        uint8_t response[32] = {0x80,0xd3,0,7};
        std::memcpy(response+8, request+24, 8);
        byteutils_put_ntp_timestamp(response,16,raop_ntp_get_local_time());
        byteutils_put_ntp_timestamp(response,24,raop_ntp_get_local_time());
        sendto(timer, response, sizeof(response), 0, reinterpret_cast<sockaddr*>(&peer), length);
      }
    });
    raop_ntp_start(ntp.get(), &ntpPort);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!raop_ntp_get_remote_time(ntp.get())) {
      if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Native NTP synchronization timed out");
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    unsigned short remoteControl = 0;
    unsigned char ct = static_cast<unsigned char>(codec); unsigned int rate = 44100;
    raop_rtp_start_audio(rtp.get(), &remoteControl, &controlPort, &dataPort, &ct, &rate);
    if (!controlPort || !dataPort || !raop_rtp_is_running(rtp.get())) throw std::runtime_error("Native RTP sockets did not start");
    worker.format(codec,spf);
  }
};
AirPlayRtpProbe::AirPlayRtpProbe(AirPlayAudioWorker& worker, const std::vector<std::vector<uint8_t>>& fixture, int codec, int spf)
  : impl(std::make_unique<Impl>(worker, fixture, codec, spf)) {
  if (!((codec == 4 && spf == 1024) || (codec == 8 && (spf == 480 || spf == 512))))
    throw std::invalid_argument("Unsupported RTP fixture format");
  impl->initialize();
}
AirPlayRtpProbe::~AirPlayRtpProbe() = default;
void AirPlayRtpProbe::beginEpoch() {
  std::vector<uint8_t> sync(20); sync[0]=0x90; sync[1]=0xd4; put16(sync.data()+2,4);
  put32(sync.data()+4,Impl::baseRtp);
  byteutils_put_ntp_timestamp(sync.data(),8,raop_ntp_get_remote_time(impl->ntp.get()));
  put32(sync.data()+16,Impl::baseRtp+impl->spf);
  transmit(impl->sender,impl->controlPort,sync);
}
void AirPlayRtpProbe::send(size_t index, bool duplicate) {
  if (index >= impl->fixture.size() || impl->mismatch) throw std::runtime_error("RTP decrypted fixture mismatch");
  const auto& frame = impl->fixture[index];
  if (frame.empty() || frame.size()+12 > RAOP_PACKET_LEN) throw std::runtime_error("Invalid RTP fixture access unit");
  std::vector<uint8_t> packet(frame.size()+12); packet[0]=0x80; packet[1]=0x60;
  put16(packet.data()+2,impl->sequence++); put32(packet.data()+4,Impl::baseRtp+static_cast<uint32_t>(index*impl->spf));
  const auto blocks = frame.size()/16*16;
  aes_cbc_encrypt(impl->cipher.get(),frame.data(),packet.data()+12,static_cast<int>(blocks));
  aes_cbc_reset(impl->cipher.get());
  std::memcpy(packet.data()+12+blocks,frame.data()+blocks,frame.size()-blocks);
  transmit(impl->sender,impl->dataPort,packet);
  if (duplicate) {transmit(impl->sender,impl->dataPort,packet); ++impl->duplicateCount;}
}
void AirPlayRtpProbe::flush() {raop_rtp_flush(impl->rtp.get(),-1);}
void AirPlayRtpProbe::setVolume(float db) {raop_rtp_set_volume(impl->rtp.get(),db);}
void AirPlayRtpProbe::stop() {
  if (impl->socketsReleased) return;
  impl->rtp.reset(); impl->ntp.reset(); impl->closing = true;
  if (impl->timing.joinable()) impl->timing.join();
  // Rebinding without reuse flags proves the native receiver's sockets closed.
  for (const auto port : {impl->controlPort, impl->dataPort, impl->ntpPort}) {
    const int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) throw std::runtime_error("Could not verify UDP socket release");
    sockaddr_in target{}; target.sin_family = AF_INET;
    target.sin_addr.s_addr = htonl(INADDR_LOOPBACK); target.sin_port = htons(port);
    const int result = bind(fd, reinterpret_cast<sockaddr*>(&target), sizeof(target));
    close(fd);
    if (result) throw std::runtime_error("Native RTP/NTP socket remained bound");
  }
  impl->socketsReleased = true;
  if (impl->mismatch) throw std::runtime_error("RTP decrypted fixture mismatch");
}
uint64_t AirPlayRtpProbe::received() const {return impl->received;}
uint64_t AirPlayRtpProbe::timed() const {return impl->timed;}
uint64_t AirPlayRtpProbe::duplicates() const {return impl->duplicateCount;}

bool AirPlayRtpProbe::socketsReleased() const {return impl->socketsReleased;}
