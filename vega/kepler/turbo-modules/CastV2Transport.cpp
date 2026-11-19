#include "CastV2Transport.h"
#include "ConnectionApproval.h"
#include "MdnsDiscovery.h"
#include "ReceiverSettings.h"

#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstdio>
#include <deque>
#include <fcntl.h>
#include <mutex>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdexcept>
#include <utility>
#include <vector>
#include <thread>
#include <unistd.h>

namespace {
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
constexpr size_t MaxFrame = 65536;
constexpr size_t MaxQueue = 8;
constexpr size_t MaxEncoded = 4 * ((MaxFrame + 2) / 3);

struct Socket {
  int fd;
  ~Socket() { if (fd >= 0) close(fd); }
};
using Context = std::unique_ptr<SSL_CTX, decltype(&SSL_CTX_free)>;

void nonblocking(int fd) {
  const int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0)
    throw std::runtime_error("Could not configure Cast V2 socket");
}

void createIdentity(const std::string& path) {
  std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)> key(
      EVP_PKEY_Q_keygen(nullptr, nullptr, "RSA", size_t{2048}), EVP_PKEY_free);
  std::unique_ptr<X509, decltype(&X509_free)> cert(X509_new(), X509_free);
  unsigned char serial[16];
  if (!key || !cert || RAND_bytes(serial, sizeof(serial)) != 1)
    throw std::runtime_error("Could not generate Cast V2 identity");
  serial[0] = (serial[0] & 0x7f) | 1;
  std::unique_ptr<BIGNUM, decltype(&BN_free)> number(BN_bin2bn(serial, sizeof(serial), nullptr), BN_free);
  auto* name = X509_get_subject_name(cert.get());
  const auto* commonName = reinterpret_cast<const unsigned char*>("Buzin Cast Experimental");
  if (!number || !BN_to_ASN1_INTEGER(number.get(), X509_get_serialNumber(cert.get())) ||
      !X509_set_version(cert.get(), 2) ||
      !X509_gmtime_adj(X509_getm_notBefore(cert.get()), -86400) ||
      !X509_gmtime_adj(X509_getm_notAfter(cert.get()), 315360000) ||
      !name || !X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, commonName, -1, -1, 0) ||
      !X509_set_issuer_name(cert.get(), name) || !X509_set_pubkey(cert.get(), key.get()) ||
      !X509_sign(cert.get(), key.get(), EVP_sha256()))
    throw std::runtime_error("Could not prepare Cast V2 certificate");

  std::string temp = path + ".XXXXXX";
  const int fd = mkstemp(temp.data());
  if (fd < 0) throw std::runtime_error("Could not create local Cast V2 key file");
  FILE* out = fdopen(fd, "wb");
  bool saved = false;
  if (out) {
    saved = PEM_write_X509(out, cert.get()) == 1 &&
        PEM_write_PrivateKey(out, key.get(), nullptr, nullptr, 0, nullptr, nullptr) == 1;
    if (fclose(out) != 0) saved = false;
  } else close(fd);
  if (!saved || std::rename(temp.c_str(), path.c_str()) != 0) {
    std::remove(temp.c_str());
    throw std::runtime_error("Could not save Cast V2 certificate and key");
  }
}

Context makeContext(const std::string& path, std::string& id) {
  if (access(path.c_str(), F_OK) != 0) {
    if (errno != ENOENT) throw std::runtime_error("Could not read Cast V2 identity");
    createIdentity(path);
  }
  Context context(SSL_CTX_new(TLS_server_method()), SSL_CTX_free);
  if (!context || !SSL_CTX_set_min_proto_version(context.get(), TLS1_2_VERSION) ||
      SSL_CTX_use_certificate_chain_file(context.get(), path.c_str()) != 1 ||
      SSL_CTX_use_PrivateKey_file(context.get(), path.c_str(), SSL_FILETYPE_PEM) != 1 ||
      SSL_CTX_check_private_key(context.get()) != 1)
    throw std::runtime_error("Could not load Cast V2 TLS identity");
  SSL_CTX_set_options(context.get(), SSL_OP_NO_RENEGOTIATION);
  unsigned char hash[EVP_MAX_MD_SIZE];
  unsigned size = 0;
  if (!X509_digest(SSL_CTX_get0_certificate(context.get()), EVP_sha256(), hash, &size) || size < 16)
    throw std::runtime_error("Could not derive Cast V2 discovery identity");
  constexpr char hex[] = "0123456789abcdef";
  for (size_t i = 0; i < 16; ++i) { id += hex[hash[i] >> 4]; id += hex[hash[i] & 15]; }
  return context;
}

std::string encode(const std::string& bytes) {
  std::string result(4 * ((bytes.size() + 2) / 3) + 1, '\0');
  const int size = EVP_EncodeBlock(reinterpret_cast<unsigned char*>(result.data()),
      reinterpret_cast<const unsigned char*>(bytes.data()), static_cast<int>(bytes.size()));
  if (size <= 0) throw std::runtime_error("Could not encode Cast frame");
  result.resize(static_cast<size_t>(size));
  return result;
}

std::string framed(const std::string& payload) {
  if (payload.empty() || payload.size() > MaxEncoded || payload.size() % 4 ||
      payload.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=") != payload.npos)
    throw std::invalid_argument("Invalid Cast frame");
  std::string result(4 + 3 * (payload.size() / 4), '\0');
  int size = EVP_DecodeBlock(reinterpret_cast<unsigned char*>(result.data() + 4),
      reinterpret_cast<const unsigned char*>(payload.data()), static_cast<int>(payload.size()));
  if (payload.ends_with("==")) size -= 2;
  else if (payload.ends_with('=')) --size;
  if (size <= 0 || size > static_cast<int>(MaxFrame)) throw std::invalid_argument("Invalid Cast frame size");
  result.resize(4 + static_cast<size_t>(size));
  for (int i = 0; i < 4; ++i) result[i] = static_cast<char>((static_cast<unsigned>(size) >> (24 - i * 8)) & 0xff);
  return result;
}

bool lanPeer(uint32_t address) {
  const auto ip = ntohl(address);
  return (ip >> 24) == 127 || (ip >> 24) == 10 || (ip >> 20) == 0xac1 || (ip >> 16) == 0xc0a8;
}
}

struct CastV2Transport::Impl {
  std::atomic<bool> active{false};
  std::mutex mutex;
  std::thread worker;
  int listener = -1;
  int client = -1;
  uint64_t sequence = 0;
  uint64_t closed = 0;
  std::deque<std::string> incoming;
  std::deque<std::string> outgoing;
  Context context{nullptr, SSL_CTX_free};
  ConnectionApproval approval;
  MdnsDiscovery discovery;

  bool ready(int fd, short events, Clock::time_point deadline) const {
    while (active && Clock::now() < deadline) {
      pollfd item{fd, events, 0};
      const int result = ::poll(&item, 1, 50);
      if (result > 0) return (item.revents & events) != 0;
      if (result < 0 && errno != EINTR) break;
    }
    return false;
  }

  bool retry(SSL* ssl, int result, Clock::time_point deadline) const {
    const int error = SSL_get_error(ssl, result);
    if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) return false;
    return ready(SSL_get_fd(ssl), error == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT, deadline);
  }

  bool transfer(SSL* ssl, char* bytes, size_t size, bool write, Clock::time_point deadline) const {
    while (size && active && Clock::now() < deadline) {
      const auto amount = static_cast<int>(size);
      const auto result = write ? SSL_write(ssl, bytes, amount) : SSL_read(ssl, bytes, amount);
      if (result <= 0) {
        if (!retry(ssl, result, deadline)) return false;
        continue;
      }
      bytes += result;
      size -= static_cast<size_t>(result);
    }
    return size == 0;
  }

  void serve(int fd) {
    std::unique_ptr<SSL, decltype(&SSL_free)> ssl(SSL_new(context.get()), SSL_free);
    if (!ssl || SSL_set_fd(ssl.get(), fd) != 1) return;
    const auto handshakeEnd = Clock::now() + 5s;
    while (true) {
      const auto result = SSL_accept(ssl.get());
      if (result == 1) break;
      if (!retry(ssl.get(), result, handshakeEnd)) return;
    }
    {
      std::lock_guard lock(mutex);
      client = fd;
      ++sequence;
    }
    auto window = Clock::now();
    int count = 0;
    while (active) {
      std::string output;
      {
        std::lock_guard lock(mutex);
        if (!outgoing.empty()) { output = std::move(outgoing.front()); outgoing.pop_front(); }
      }
      if (!output.empty() && !transfer(ssl.get(), output.data(), output.size(), true, Clock::now() + 5s)) return;
      if (!SSL_pending(ssl.get())) {
        pollfd item{fd, POLLIN, 0};
        const auto result = ::poll(&item, 1, 50);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0 || (item.revents & (POLLHUP | POLLERR | POLLNVAL))) return;
        if (!(item.revents & POLLIN)) continue;
      }
      const auto end = Clock::now() + 5s;
      char header[4];
      if (!transfer(ssl.get(), header, sizeof(header), false, end)) return;
      size_t size = 0;
      for (const unsigned char byte : header) size = (size << 8) | byte;
      if (!size || size > MaxFrame) return;
      if (Clock::now() - window >= 1s) { window = Clock::now(); count = 0; }
      if (++count > 40) return;
      std::string body(size, '\0');
      if (!transfer(ssl.get(), body.data(), body.size(), false, end)) return;
      auto encoded = encode(body);
      std::lock_guard lock(mutex);
      if (incoming.size() >= MaxQueue) return;
      incoming.push_back("{\"client\":" + std::to_string(sequence) + ",\"frame\":\"" + encoded + "\"}");
    }
  }

  void run() noexcept {
    sigset_t blocked;
    sigemptyset(&blocked);
    sigaddset(&blocked, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &blocked, nullptr);
    try {
      while (active) {
        if (!ready(listener, POLLIN, Clock::now() + 150ms)) continue;
        sockaddr_in peer{};
        socklen_t size = sizeof(peer);
        Socket fd{::accept(listener, reinterpret_cast<sockaddr*>(&peer), &size)};
        if (fd.fd < 0 || !lanPeer(peer.sin_addr.s_addr)) continue;
        nonblocking(fd.fd);
        if (approval.authorize(fd.fd, peer.sin_addr.s_addr, true)) serve(fd.fd);
        std::lock_guard lock(mutex);
        if (client == fd.fd) { closed = sequence; client = -1; }
        incoming.clear();
        outgoing.clear();
      }
    } catch (...) { active = false; }
    std::lock_guard lock(mutex);
    client = -1;
  }
};

CastV2Transport::CastV2Transport() : impl(std::make_unique<Impl>()) {}
CastV2Transport::~CastV2Transport() { stop(); }

uint16_t CastV2Transport::start(const std::string& name, const std::string& keyPath, bool requireApproval) {
  if (impl->worker.joinable()) throw std::runtime_error("Cast V2 is already running");
  validateReceiverName(name);
  std::string id;
  auto context = makeContext(keyPath, id);
  Socket listener{::socket(AF_INET, SOCK_STREAM, 0)};
  sockaddr_in address{};
  address.sin_family = AF_INET;
  socklen_t size = sizeof(address);
  if (listener.fd < 0 || ::bind(listener.fd, reinterpret_cast<sockaddr*>(&address), size) != 0 ||
      ::listen(listener.fd, 1) != 0 || getsockname(listener.fd, reinterpret_cast<sockaddr*>(&address), &size) != 0)
    throw std::runtime_error("Could not bind Cast V2 listener");
  nonblocking(listener.fd);
  const auto port = ntohs(address.sin_port);
  impl->context = std::move(context);
  impl->listener = std::exchange(listener.fd, -1);
  try {
    impl->approval.start(requireApproval);
    impl->active = true;
    impl->worker = std::thread([this] { impl->run(); });
    std::vector<uint8_t> txt;
    for (const auto& value : {"id=" + id, "fn=" + name, std::string("md=Buzin Cast Experimental"),
         std::string("ve=05"), std::string("ca=5"), std::string("st=0")}) {
      txt.push_back(static_cast<uint8_t>(value.size()));
      txt.insert(txt.end(), value.begin(), value.end());
    }
    impl->discovery.start("buzin-cast-" + id + ".local", {{"Buzin-" + id, "_googlecast._tcp.local", port, std::move(txt)}});
  } catch (...) { stop(); throw; }
  return port;
}

void CastV2Transport::stop() noexcept {
  impl->active = false;
  impl->approval.stop();
  impl->discovery.stop();
  if (impl->worker.joinable()) impl->worker.join();
  if (impl->listener >= 0) close(std::exchange(impl->listener, -1));
  impl->context.reset();
  std::lock_guard lock(impl->mutex);
  impl->incoming.clear();
  impl->outgoing.clear();
  impl->closed = 0;
}

std::string CastV2Transport::poll() {
  if (!impl->active) throw std::runtime_error("Cast V2 receiver stopped");
  if (auto event = impl->approval.poll(); !event.empty()) return event;
  std::lock_guard lock(impl->mutex);
  if (impl->closed) return "{\"closed\":" + std::to_string(std::exchange(impl->closed, 0)) + "}";
  if (impl->incoming.empty()) return {};
  auto value = std::move(impl->incoming.front());
  impl->incoming.pop_front();
  return value;
}
void CastV2Transport::answerApproval(double id, bool allow) { impl->approval.respond(id, allow); }

void CastV2Transport::send(double client, const std::string& payload) {
  std::lock_guard lock(impl->mutex);
  if (impl->client < 0 || client != static_cast<double>(impl->sequence)) return;
  if (impl->outgoing.size() >= MaxQueue) { ::shutdown(impl->client, SHUT_RDWR); return; }
  impl->outgoing.push_back(framed(payload));
}

void CastV2Transport::disconnect(double client) {
  std::lock_guard lock(impl->mutex);
  if (impl->client >= 0 && client == static_cast<double>(impl->sequence)) ::shutdown(impl->client, SHUT_RDWR);
}
