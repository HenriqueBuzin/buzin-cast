#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
extern "C" {
#include "mirror_buffer.h"
#include "crypto.h"
}
namespace {
constexpr uint8_t Key[16] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
std::vector<uint8_t> encrypt(const std::vector<uint8_t>& input, uint64_t stream) {
  uint8_t key[64], iv[64];
  const auto keyLabel = "AirPlayStreamKey"+std::to_string(stream), ivLabel = "AirPlayStreamIV"+std::to_string(stream);
  std::unique_ptr<sha_ctx_t,decltype(&sha_destroy)> hash(sha_init(),sha_destroy);
  sha_update(hash.get(),reinterpret_cast<const uint8_t*>(keyLabel.data()),keyLabel.size());
  sha_update(hash.get(),Key,sizeof(Key)); sha_final(hash.get(),key,nullptr);
  sha_reset(hash.get()); sha_update(hash.get(),reinterpret_cast<const uint8_t*>(ivLabel.data()),ivLabel.size());
  sha_update(hash.get(),Key,sizeof(Key)); sha_final(hash.get(),iv,nullptr);
  std::unique_ptr<aes_ctx_t,decltype(&aes_ctr_destroy)> cipher(aes_ctr_init(key,iv),aes_ctr_destroy);
  std::vector<uint8_t> output(input.size());
  aes_ctr_encrypt(cipher.get(),input.data(),output.data(),input.size()); return output;
}
void partition(mirror_buffer_t* receiver, uint64_t stream, size_t firstChunk, bool inplace) {
  mirror_buffer_init_aes(receiver,&stream);
  std::vector<uint8_t> plain(4097);
  for (size_t i=0; i<plain.size(); ++i) plain[i] = static_cast<uint8_t>((i*73+19)%256);
  const auto encrypted = encrypt(plain,stream);
  size_t offset = 0, chunk = firstChunk;
  while (offset < encrypted.size()) {
    const auto size = std::min(chunk,encrypted.size()-offset);
    // Exact-size allocations expose residual-block reads past short packets.
    std::vector<uint8_t> input(encrypted.begin()+offset,encrypted.begin()+offset+size), output(size);
    auto* target = inplace ? input.data() : output.data();
    mirror_buffer_decrypt(receiver,input.data(),target,size);
    assert(!std::memcmp(target,plain.data()+offset,size));
    // Empty fragments must not consume keystream left from a partial block.
    mirror_buffer_decrypt(receiver,nullptr,nullptr,0);
    offset += size; chunk = chunk%31+1;
  }
}
}
int main() {
  std::unique_ptr<mirror_buffer_t,decltype(&mirror_buffer_destroy)> receiver(mirror_buffer_init(nullptr,Key),mirror_buffer_destroy);
  assert(receiver);
  for (bool inplace : {false,true}) for (size_t chunk=1; chunk<=33; ++chunk) {
    // Re-key after a partial final block, on the same native object.
    partition(receiver.get(),0x0102030405060708+chunk,chunk,inplace);
  }
  std::puts("Mirror AES-CTR: 66 partition/re-key cases passed");
}
