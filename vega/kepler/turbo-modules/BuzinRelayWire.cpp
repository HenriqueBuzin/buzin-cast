#include "BuzinRelayWire.h"
#include "LocalTransfer.h"

#include <algorithm>
#include <cerrno>
#include <limits>
#include <memory>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <poll.h>
#include <stdexcept>
#include <sys/socket.h>

namespace BuzinRelay {
namespace {
using namespace std::chrono_literals;
constexpr std::array<uint8_t, 8> Magic{'B','Z','C','A','S','T','0','2'};
const Bytes Hello{'h','e','l','l','o'}, Ready{'r','e','a','d','y'};
constexpr size_t HeaderSize = 24, TagSize = 16;
constexpr uint64_t MaxSequence = std::numeric_limits<int64_t>::max();

uint64_t get(const uint8_t* data, size_t size) {
  uint64_t result = 0;
  for (size_t index = 0; index < size; ++index) result = (result << 8) | data[index];
  return result;
}
void put(uint8_t* data, size_t size, uint64_t value) {
  while (size) {data[--size] = static_cast<uint8_t>(value); value >>= 8;}
}

Bytes crypt(bool encrypt, const std::array<uint8_t, 32>& key,
            const std::array<uint8_t, HeaderSize>& header, Bytes input) {
  std::unique_ptr<EVP_CIPHER_CTX, decltype(&EVP_CIPHER_CTX_free)> ctx(
      EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
  const auto* sequence = header.data() + 4;
  const auto* nonce = header.data() + 12;
  const auto length = static_cast<int>(encrypt ? input.size() : input.size() - TagSize);
  Bytes output(static_cast<size_t>(length) + TagSize);
  int aad = 0, written = 0, final = 0;
  if (!ctx || EVP_CipherInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, key.data(), nonce, encrypt) != 1 ||
      EVP_CipherUpdate(ctx.get(), nullptr, &aad, sequence, 8) != 1 ||
      (!encrypt && EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_SET_TAG, TagSize, input.data() + length) != 1) ||
      (length && EVP_CipherUpdate(ctx.get(), output.data(), &written, input.data(), length) != 1) ||
      EVP_CipherFinal_ex(ctx.get(), output.data() + written, &final) != 1 || written + final != length ||
      (encrypt && EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_GET_TAG, TagSize, output.data() + length) != 1)) {
    throw std::runtime_error("Relay authentication failed");
  }
  if (!encrypt) output.resize(static_cast<size_t>(length));
  return output;
}
}

Channel::Channel(int fd, const std::atomic<bool>& active) : fd(fd), active(active) {}
Channel::~Channel() {OPENSSL_cleanse(key.data(), key.size());}

void Channel::read(uint8_t* data, size_t size, Clock::time_point deadline) {
  while (size && LocalTransfer::waitFor(active, fd, POLLIN, deadline)) {
    const auto count = recv(fd, data, size, 0);
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) continue;
    if (count <= 0) break;
    data += count;
    size -= static_cast<size_t>(count);
  }
  if (size) throw std::runtime_error("Incomplete relay frame");
}

void Channel::write(const uint8_t* data, size_t size, Clock::time_point deadline) {
  if (!LocalTransfer::sendAll(active, fd, {reinterpret_cast<const char*>(data), size}, deadline))
    throw std::runtime_error("Relay frame could not be sent");
}

void Channel::derive(std::string_view code, const std::array<uint8_t, 16>& salt) {
  if (code.size() != 12 || PKCS5_PBKDF2_HMAC(code.data(), static_cast<int>(code.size()), salt.data(),
      salt.size(), 100000, EVP_sha1(), key.size(), key.data()) != 1) {
    throw std::runtime_error("Relay pairing key could not be derived");
  }
}

void Channel::accept(std::string_view code) {
  const auto end = Clock::now() + 8s;
  std::array<uint8_t, 8> magic{};
  read(magic.data(), magic.size(), end);
  if (magic != Magic) throw std::runtime_error("Unsupported relay version");
  std::array<uint8_t, 16> salt{};
  if (RAND_bytes(salt.data(), salt.size()) != 1) throw std::runtime_error("Random relay salt unavailable");
  write(salt.data(), salt.size(), end);
  derive(code, salt);
  if (receive(end) != Hello) throw std::runtime_error("Invalid relay handshake");
  send(Ready, end);
}

Bytes Channel::receive(Clock::time_point deadline) {
  std::array<uint8_t, HeaderSize> header{};
  read(header.data(), header.size(), deadline);
  const auto size = get(header.data(), 4);
  if (size < TagSize || size > MaxSample + 64 || rx > MaxSequence || get(header.data() + 4, 8) != rx)
    throw std::runtime_error("Invalid relay frame");
  Bytes encrypted(static_cast<size_t>(size));
  read(encrypted.data(), encrypted.size(), deadline);
  auto plain = crypt(false, key, header, std::move(encrypted));
  ++rx;
  return plain;
}

void Channel::send(const Bytes& plain, Clock::time_point deadline) {
  if (plain.size() > MaxSample + 32 || tx > MaxSequence)
    throw std::runtime_error("Invalid relay payload");
  std::array<uint8_t, HeaderSize> header{};
  put(header.data(), 4, plain.size() + TagSize);
  put(header.data() + 4, 8, tx);
  if (RAND_bytes(header.data() + 12, 12) != 1) throw std::runtime_error("Random relay nonce unavailable");
  const auto encrypted = crypt(true, key, header, plain);
  write(header.data(), header.size(), deadline);
  write(encrypted.data(), encrypted.size(), deadline);
  ++tx;
}

Packet decode(Bytes wire) {
  if (wire.size() == 1 && wire[0] == 3) return {Packet::Kind::End, 0, 0, 0, false, {}};
  if (wire.size() < 21 || (wire[0] != 1 && wire[0] != 2)) throw std::invalid_argument("Invalid relay packet");
  const auto timestamp = get(wire.data() + 1, 8);
  const auto a = get(wire.data() + 9, 4), b = get(wire.data() + 13, 4);
  const bool video = wire[0] == 1;
  const size_t prefix = video ? 22 : 21;
  if (!timestamp || timestamp > MaxSequence || wire.size() <= prefix ||
      get(wire.data() + prefix - 4, 4) != wire.size() - prefix)
    throw std::invalid_argument("Invalid relay sample timestamp or length");
  if (video) {
    if (a < 16 || a > 3840 || b < 16 || b > 2160 || wire[17] > 1 || wire.size() - prefix < 5 ||
        wire.size() - prefix > 2 * 1024 * 1024)
      throw std::invalid_argument("Invalid relay video format");
  } else if (a != 44100 || b != 2 || (wire.size() - prefix) % 4 || wire.size() - prefix > 128 * 1024) {
    throw std::invalid_argument("Invalid relay PCM format");
  }
  const bool hevc = video && wire[17] != 0;
  wire.erase(wire.begin(), wire.begin() + prefix);
  return {video ? Packet::Kind::Video : Packet::Kind::Audio, timestamp,
          video ? static_cast<int>(a) : 0, video ? static_cast<int>(b) : 0, hevc, std::move(wire)};
}
}
