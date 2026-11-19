#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <memory>
#include <sys/socket.h>
#include <netinet/in.h>
#include <thread>
#include <unistd.h>
extern "C" {
#include "crypto.h"
#include "logger.h"
#include "raop_ntp.h"
#include "raop_rtp_mirror.h"
}
namespace {
template<class Check> void await(Check check) {
  const auto limit=std::chrono::steady_clock::now()+std::chrono::seconds(4);
  while(!check()) {assert(std::chrono::steady_clock::now()<limit);std::this_thread::sleep_for(std::chrono::milliseconds(2));}
}
int descriptors() {
  DIR* directory=opendir("/proc/self/fd");assert(directory);int count=0;
  while(auto* item=readdir(directory))if(std::strcmp(item->d_name,".") && std::strcmp(item->d_name,".."))++count;
  closedir(directory);return count;
}
void sendAll(int fd,const void* bytes,size_t size) {
  const auto* input=static_cast<const unsigned char*>(bytes);
  while(size) {const auto sent=send(fd,input,size,MSG_NOSIGNAL);assert(sent>0);input+=sent;size-=sent;}
}
std::array<unsigned char,128> header(unsigned length,unsigned char kind) {
  std::array<unsigned char,128> bytes{};
  for(int i=0;i<4;++i)bytes[i]=static_cast<unsigned char>(length>>(8*i));
  bytes[4]=kind;return bytes;
}
struct Fixture {
  std::atomic<bool> running{false};
  std::atomic<int> eof{0},packets{0},resets{0};
  std::unique_ptr<logger_t,decltype(&logger_destroy)> logger{logger_init(),logger_destroy};
  std::unique_ptr<raop_ntp_t,decltype(&raop_ntp_destroy)> ntp{nullptr,raop_ntp_destroy};
  std::unique_ptr<raop_rtp_mirror_t,decltype(&raop_rtp_mirror_destroy)> mirror{nullptr,raop_rtp_mirror_destroy};
  unsigned short port=0;
  explicit Fixture(bool endOnEof) {
    assert(logger);logger_set_level(logger.get(),LOGGER_DEBUG);
    logger_set_callback(logger.get(),[](void* pointer,int,const char* message) {
      auto& self=*static_cast<Fixture*>(pointer);
      if(std::strstr(message,"recv returned 0"))++self.eof;
      if(std::strstr(message,"Received unexpected TCP packet") && std::strstr(message,"size 1, 7e 00"))++self.packets;
    },this);
    raop_callbacks_t callbacks{};callbacks.cls=this;callbacks.mirror_end_on_eof=endOnEof;
    callbacks.mirror_video_running=[](void* pointer,bool value) {static_cast<Fixture*>(pointer)->running=value;};
    callbacks.video_set_codec=[](void*,video_codec_t) {return -1;};
    callbacks.video_reset=[](void* pointer,reset_type_t) {++static_cast<Fixture*>(pointer)->resets;};
    timing_protocol_t protocol=TP_NONE;
    ntp.reset(raop_ntp_init(logger.get(),&callbacks,"127.0.0.1",4,0,&protocol));assert(ntp);
    const unsigned char key[16]={};
    mirror.reset(raop_rtp_mirror_init(logger.get(),&callbacks,ntp.get(),"127.0.0.1",4,key));assert(mirror);
    uint64_t stream=1234;raop_rtp_mirror_init_aes(mirror.get(),&stream);
  }
  void start() {port=0;raop_rtp_mirror_start(mirror.get(),&port,0);assert(port);await([&] {return running.load();});}
  int connectPeer() {
    int peer=socket(AF_INET,SOCK_STREAM,0);assert(peer>=0);
    sockaddr_in address{};address.sin_family=AF_INET;address.sin_port=htons(port);address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    assert(connect(peer,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0);return peer;
  }
  void stop() {
    const auto previous=port;raop_rtp_mirror_stop(mirror.get());assert(!running);
    raop_rtp_mirror_stop(mirror.get()); // owner stop is idempotent
    int probe=socket(AF_INET,SOCK_STREAM,0);assert(probe>=0);int reuse=1;
    assert(setsockopt(probe,SOL_SOCKET,SO_REUSEADDR,&reuse,sizeof(reuse))==0);
    sockaddr_in address{};address.sin_family=AF_INET;address.sin_port=htons(previous);address.sin_addr.s_addr=htonl(INADDR_ANY);
    assert(bind(probe,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0);close(probe);
  }
  void completePacket(int peer) {
    auto bytes=header(1,0x7e);const unsigned char payload=0x5a;const auto before=packets.load();
    sendAll(peer,bytes.data(),bytes.size());sendAll(peer,&payload,1);
    await([&] {return packets.load()==before+1;});
  }
};
void reconnect() {
  Fixture receiver(false);receiver.start();int peer=receiver.connectPeer();
  auto incomplete=header(3,0x7f);sendAll(peer,incomplete.data(),7);close(peer);
  await([&] {return receiver.eof.load()==1;});assert(receiver.running);
  peer=receiver.connectPeer();receiver.completePacket(peer);close(peer);
  await([&] {return receiver.eof.load()==2;});assert(receiver.running);receiver.stop();
  receiver.start();peer=receiver.connectPeer();receiver.completePacket(peer);close(peer);
  await([&] {return receiver.eof.load()==3;});receiver.stop();
}
void endedThread() {
  Fixture receiver(true);receiver.start();int peer=receiver.connectPeer();
  auto bytes=header(4096,0x7e);sendAll(peer,bytes.data(),bytes.size());sendAll(peer,bytes.data(),9);close(peer);
  await([&] {return !receiver.running.load();});receiver.stop();
  receiver.start();peer=receiver.connectPeer();receiver.completePacket(peer);close(peer);
  await([&] {return !receiver.running.load();});receiver.stop();
}
void unsupported() {
  Fixture receiver(false);receiver.start();int peer=receiver.connectPeer();
  auto bytes=header(0,1);sendAll(peer,bytes.data(),bytes.size());
  await([&] {return receiver.resets.load()==1;});assert(!receiver.running);receiver.stop();close(peer);
  receiver.start();peer=receiver.connectPeer();receiver.completePacket(peer);close(peer);
  await([&] {return receiver.eof.load()==1;});receiver.stop();
}
}
int main() {
  // Initialize the crypto provider before measuring owned socket descriptors.
  auto* digest=sha_init();assert(digest);sha_destroy(digest);
  const int baseline=descriptors();
  for(int i=0;i<4;++i)for(auto scenario:{reconnect,endedThread,unsupported}) {
    scenario();assert(descriptors()==baseline);
  }
  std::puts("Mirror TCP lifecycle: 12 scenarios passed");
}
