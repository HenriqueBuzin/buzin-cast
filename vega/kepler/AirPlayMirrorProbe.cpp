#include "AirPlayMirrorProbe.h"
#include "turbo-modules/AirPlayVideoCallbacks.h"
#include "turbo-modules/H264AnnexB.h"
#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <thread>
extern "C" {
#include "raop_rtp_mirror.h"
#include "crypto.h"
#include "byteutils.h"
}

namespace {
constexpr uint8_t FixtureKey[16] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
constexpr uint64_t StreamId = 0x0102030405060708;
void little(uint8_t* bytes, uint64_t value, size_t size) {for (size_t i=0; i<size; ++i) bytes[i] = static_cast<uint8_t>(value>>(8*i));}
void big32(std::vector<uint8_t>& bytes, uint32_t value) {for (int i=3; i>=0; --i) bytes.push_back(static_cast<uint8_t>(value>>(8*i)));}
uint64_t fixedNtp(uint64_t ns) {return ((ns/1000000000)<<32) | (((ns%1000000000)<<32)/1000000000);}
void appendNal(std::vector<uint8_t>& bytes, const BuzinH264::Nal& nal) {
  bytes.insert(bytes.end(),{0,0,0,1}); bytes.insert(bytes.end(),nal.bytes,nal.bytes+nal.size);
}
}
struct AirPlayMirrorProbe::Impl {
  AirPlayVideoWorker& worker;
  const std::vector<AirPlayVideoFixtureFrame>& frames;
  std::vector<std::vector<uint8_t>> expected, wire;
  std::vector<bool> configurationFrame;
  std::vector<uint8_t> avcc;
  const int width, height;
  std::unique_ptr<logger_t,decltype(&logger_destroy)> logger{nullptr,logger_destroy};
  std::unique_ptr<raop_ntp_t,decltype(&raop_ntp_destroy)> ntp{nullptr,raop_ntp_destroy};
  std::unique_ptr<raop_rtp_mirror_t,decltype(&raop_rtp_mirror_destroy)> mirror{nullptr,raop_rtp_mirror_destroy};
  std::unique_ptr<aes_ctx_t,decltype(&aes_ctr_destroy)> cipher{nullptr,aes_ctr_destroy};
  int sender = -1, timer = -1;
  uint16_t dataPort = 0, ntpPort = 0;
  uint64_t ntpBase = 0;
  size_t nextFrame = 0;
  bool released = false;
  std::atomic<bool> closing{false}, mismatch{false}, running{false};
  std::atomic<uint64_t> received{0}, timed{0}, fragmentedWrites{0};
  std::thread timing;
  Impl(AirPlayVideoWorker& output, int w, int h, const std::vector<AirPlayVideoFixtureFrame>& input)
    : worker(output), frames(input), width(w), height(h) {}
  ~Impl() {
    closing = true;
    mirror.reset(); ntp.reset();
    if (timing.joinable()) timing.join();
    if (sender >= 0) close(sender);
    if (timer >= 0) close(timer);
  }
  void prepare(const std::vector<uint8_t>& configuration) {
    if (frames.empty() || frames.size() > 600 || configuration.size() > 65536 ||
        width < 16 || width > 3840 || height < 16 || height > 2160) throw std::invalid_argument("Invalid mirror fixture");
    std::vector<uint8_t> sps, pps, prefix;
    for (const auto& nal : BuzinH264::split(configuration)) {
      auto* parameter = nal.type == 7 ? &sps : nal.type == 8 ? &pps : nullptr;
      if (!parameter || !parameter->empty() || nal.size > 32767) throw std::invalid_argument("Invalid mirror fixture parameters");
      parameter->assign(nal.bytes,nal.bytes+nal.size); appendNal(prefix,nal);
    }
    if (sps.size() < 4 || pps.empty()) throw std::invalid_argument("Missing mirror fixture parameters");
    avcc = {1,sps[1],sps[2],sps[3],0xff,0xe1,static_cast<uint8_t>(sps.size()>>8),static_cast<uint8_t>(sps.size())};
    avcc.insert(avcc.end(),sps.begin(),sps.end()); avcc.push_back(1);
    avcc.push_back(static_cast<uint8_t>(pps.size()>>8)); avcc.push_back(static_cast<uint8_t>(pps.size()));
    avcc.insert(avcc.end(),pps.begin(),pps.end());
    size_t total = 0;
    uint32_t previous = 0;
    for (size_t i=0; i<frames.size(); ++i) {
      const auto& frame = frames[i]; total += frame.data.size();
      if (total > 16*1024*1024 || frame.pts > 60000 || (i && frame.pts <= previous)) throw std::invalid_argument("Unbounded mirror fixture timeline");
      previous = frame.pts;
      std::vector<uint8_t> packet, canonical;
      bool parameters = i == 0, idr = false;
      for (const auto& nal : BuzinH264::split(frame.data)) {
        if (nal.type == 7 || nal.type == 8) {
          const auto& reference = nal.type == 7 ? sps : pps;
          if (nal.size != reference.size() || std::memcmp(nal.bytes,reference.data(),nal.size)) throw std::invalid_argument("Unexpected mirror fixture format change");
          parameters = true; continue;
        }
        idr = idr || nal.type == 5;
        big32(packet,nal.size); packet.insert(packet.end(),nal.bytes,nal.bytes+nal.size); appendNal(canonical,nal);
      }
      if (packet.size() < 16 || packet.size() > 2*1024*1024 || (parameters && !idr)) throw std::invalid_argument("Expected bounded mirror IDR configuration");
      if (parameters) canonical.insert(canonical.begin(),prefix.begin(),prefix.end());
      expected.push_back(std::move(canonical)); wire.push_back(std::move(packet)); configurationFrame.push_back(parameters);
    }
  }
  void initialize() {
    timer = socket(AF_INET,SOCK_DGRAM,0);
    if (timer < 0) throw std::runtime_error("Could not open mirror timing fixture socket");
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    socklen_t length = sizeof(address);
    timeval timeout{0,100000};
    if (bind(timer,reinterpret_cast<sockaddr*>(&address),sizeof(address)) || getsockname(timer,reinterpret_cast<sockaddr*>(&address),&length) ||
        setsockopt(timer,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))) throw std::runtime_error("Could not configure mirror timing fixture");
    logger.reset(logger_init());
    if (!logger) throw std::runtime_error("Could not create mirror fixture logger");
    logger_set_callback(logger.get(),[](void*,int,const char*) {},nullptr); logger_set_level(logger.get(),0);
    raop_callbacks_t callbacks{}; callbacks.cls = this;
    callbacks.video_process = [](void* cls,raop_ntp_t*,video_decode_struct* frame) {
      auto& self = *static_cast<Impl*>(cls);
      const auto index = self.received.load();
      if (!frame || index >= self.expected.size() || frame->is_h265 || !frame->data || frame->data_len <= 0 ||
          static_cast<size_t>(frame->data_len) != self.expected[index].size() ||
          std::memcmp(frame->data,self.expected[index].data(),self.expected[index].size())) {self.mismatch = true; return;}
      const auto expectedTime = raop_ntp_timestamp_to_nano_seconds(fixedNtp(self.ntpBase+uint64_t(self.frames[index].pts)*1000000),false);
      if (!frame->ntp_time_local || frame->ntp_time_remote != expectedTime) {self.mismatch = true; return;}
      forwardAirPlayVideo(self.worker,frame); ++self.timed; ++self.received;
    };
    callbacks.video_report_size = [](void* cls,float*,float*,float* width,float* height) {
      forwardAirPlayVideoSize(static_cast<Impl*>(cls)->worker,width,height);
    };
    callbacks.video_set_codec = [](void*,video_codec_t codec) {return codec == VIDEO_CODEC_H264 ? 0 : -1;};
    callbacks.video_pause = [](void*) {};
    callbacks.video_resume = [](void*) {};
    callbacks.video_reset = [](void* cls,reset_type_t) {static_cast<Impl*>(cls)->worker.flush();};
    callbacks.mirror_video_running = [](void* cls,bool value) {static_cast<Impl*>(cls)->running = value;};
    timing_protocol_t protocol = NTP;
    ntp.reset(raop_ntp_init(logger.get(),&callbacks,"127.0.0.1",4,ntohs(address.sin_port),&protocol));
    if (!ntp) throw std::runtime_error("Could not create mirror NTP session");
    timing = std::thread([this] {
      while (!closing) {
        uint8_t request[128]{}; sockaddr_in peer{}; socklen_t length = sizeof(peer);
        const auto count = recvfrom(timer,request,sizeof(request),0,reinterpret_cast<sockaddr*>(&peer),&length);
        if (count != 32 || request[1] != 0xd2 || (ntohl(peer.sin_addr.s_addr)>>24) != 127) continue;
        uint8_t response[32] = {0x80,0xd3,0,7}; std::memcpy(response+8,request+24,8);
        byteutils_put_ntp_timestamp(response,16,raop_ntp_get_local_time()); byteutils_put_ntp_timestamp(response,24,raop_ntp_get_local_time());
        sendto(timer,response,sizeof(response),0,reinterpret_cast<sockaddr*>(&peer),length);
      }
    });
    raop_ntp_start(ntp.get(),&ntpPort);
    const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(5);
    while (!(ntpBase = raop_ntp_get_remote_time(ntp.get()))) {
      if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Mirror NTP fixture did not synchronize");
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    // Independent sender derivation, using public constants only.
    uint8_t key[64], iv[64];
    const auto keyLabel = "AirPlayStreamKey"+std::to_string(StreamId), ivLabel = "AirPlayStreamIV"+std::to_string(StreamId);
    std::unique_ptr<sha_ctx_t,decltype(&sha_destroy)> hash(sha_init(),sha_destroy);
    sha_update(hash.get(),reinterpret_cast<const uint8_t*>(keyLabel.data()),keyLabel.size()); sha_update(hash.get(),FixtureKey,sizeof(FixtureKey)); sha_final(hash.get(),key,nullptr);
    sha_reset(hash.get()); sha_update(hash.get(),reinterpret_cast<const uint8_t*>(ivLabel.data()),ivLabel.size()); sha_update(hash.get(),FixtureKey,sizeof(FixtureKey)); sha_final(hash.get(),iv,nullptr);
    cipher.reset(aes_ctr_init(key,iv));
    mirror.reset(raop_rtp_mirror_init(logger.get(),&callbacks,ntp.get(),"127.0.0.1",4,FixtureKey));
    if (!cipher || !mirror) throw std::runtime_error("Could not create TCP/AES mirror fixture");
    uint64_t streamId = StreamId; raop_rtp_mirror_init_aes(mirror.get(),&streamId); raop_rtp_mirror_start(mirror.get(),&dataPort,0);
    if (!dataPort || !running) throw std::runtime_error("Native mirror listener did not start");
    sender = socket(AF_INET,SOCK_STREAM,0);
    if (sender < 0) throw std::runtime_error("Could not open mirror sender");
    address.sin_port = htons(dataPort);
    if (connect(sender,reinterpret_cast<sockaddr*>(&address),sizeof(address))) throw std::runtime_error("Could not connect mirror fixture");
    const auto flags = fcntl(sender,F_GETFL,0);
    if (flags < 0 || fcntl(sender,F_SETFL,flags|O_NONBLOCK)) throw std::runtime_error("Could not bound mirror send wait");
#ifdef SO_NOSIGPIPE
    const int one = 1;
    if (setsockopt(sender,SOL_SOCKET,SO_NOSIGPIPE,&one,sizeof(one))) throw std::runtime_error("Could not suppress mirror signal");
#endif
  }
  void transmit(const std::vector<uint8_t>& packet) {
    size_t offset = 0;
    const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(3);
    // Split every header and payload across writes, exercising TCP reassembly.
    constexpr size_t pieces[] = {1,3,17,127,4093};
    size_t piece = 0;
    while (offset < packet.size()) {
      if (mismatch || closing || !running || !worker.healthy()) throw std::runtime_error("Mirror fixture receiver stopped");
      if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Mirror fixture send timeout");
      pollfd item{sender,POLLOUT,0};
      if (poll(&item,1,25) <= 0) continue;
      const auto size = std::min(pieces[piece++%5],packet.size()-offset);
#ifdef MSG_NOSIGNAL
      const auto count = ::send(sender,packet.data()+offset,size,MSG_NOSIGNAL);
#else
      const auto count = ::send(sender,packet.data()+offset,size,0);
#endif
      if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
      if (count <= 0) throw std::runtime_error("Could not transmit mirror fixture");
      offset += count; ++fragmentedWrites;
    }
  }
  std::vector<uint8_t> header(size_t size, uint8_t type, uint64_t ntpTime) {
    std::vector<uint8_t> bytes(128); little(bytes.data(),size,4); bytes[4] = type; little(bytes.data()+8,fixedNtp(ntpTime),8);
    return bytes;
  }
};
AirPlayMirrorProbe::AirPlayMirrorProbe(AirPlayVideoWorker& worker, int width, int height,
    const std::vector<uint8_t>& configuration, const std::vector<AirPlayVideoFixtureFrame>& frames)
  : impl(std::make_unique<Impl>(worker,width,height,frames)) {impl->prepare(configuration); impl->initialize();}
AirPlayMirrorProbe::~AirPlayMirrorProbe() = default;
void AirPlayMirrorProbe::send(size_t index) {
  if (index != impl->nextFrame || index >= impl->frames.size() || impl->released) throw std::invalid_argument("Expected sequential mirror fixture frame");
  const auto timestamp = impl->ntpBase+uint64_t(impl->frames[index].pts)*1000000;
  if (impl->configurationFrame[index]) {
    auto packet = impl->header(impl->avcc.size(),1,timestamp); packet[6] = 0x16; packet[7] = 1;
    for (const auto offset : {16,40,56}) {
      const float width = impl->width, height = impl->height;
      std::memcpy(packet.data()+offset,&width,4); std::memcpy(packet.data()+offset+4,&height,4);
    }
    packet.insert(packet.end(),impl->avcc.begin(),impl->avcc.end()); impl->transmit(packet);
  }
  const auto& frame = impl->wire[index];
  auto packet = impl->header(frame.size(),0,timestamp); packet[5] = impl->frames[index].keyframe ? 0x10 : 0;
  std::vector<uint8_t> encrypted(frame.size()); aes_ctr_encrypt(impl->cipher.get(),frame.data(),encrypted.data(),frame.size());
  packet.insert(packet.end(),encrypted.begin(),encrypted.end()); impl->transmit(packet); ++impl->nextFrame;
}
void AirPlayMirrorProbe::stop() {
  if (impl->released) return;
  impl->closing = true;
  // Stop/join the native receiver while the sender is still open. The native
  // parser owns its accepted socket; the test never substitutes a fake close.
  impl->mirror.reset(); impl->ntp.reset();
  if (impl->timing.joinable()) impl->timing.join();
  if (impl->sender >= 0) {close(impl->sender); impl->sender = -1;}
  for (const auto stream : {true,false}) {
    const int fd = socket(AF_INET,stream ? SOCK_STREAM : SOCK_DGRAM,0);
    if (fd < 0) throw std::runtime_error("Could not verify mirror socket release");
    const int one = 1; if (stream) setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(stream ? impl->dataPort : impl->ntpPort);
    const auto status = bind(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address)); close(fd);
    if (status) throw std::runtime_error("Native mirror TCP/NTP socket remained bound");
  }
  impl->released = true;
  if (impl->mismatch) throw std::runtime_error("Decrypted mirror fixture or NTP mismatch");
}
bool AirPlayMirrorProbe::healthy() const {return !impl->mismatch && (impl->running || impl->released);}
bool AirPlayMirrorProbe::socketsReleased() const {return impl->released;}
uint64_t AirPlayMirrorProbe::received() const {return impl->received;}
uint64_t AirPlayMirrorProbe::timed() const {return impl->timed;}
uint64_t AirPlayMirrorProbe::fragmentedWrites() const {return impl->fragmentedWrites;}
