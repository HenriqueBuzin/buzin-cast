#include "ProtocolProbe.h"
#include "AirPlaySession.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <memory>
extern "C" {
#include "http_request.h"
#include "crypto.h"
}

namespace {
using Request = std::unique_ptr<http_request_t, decltype(&http_request_destroy)>;
bool fragmentedRtsp() {
  Request request(http_request_init(), http_request_destroy);
  if (!request) return false;
  const char* parts[] = {"OPT", "IONS * RTSP/1.0\r\nCSeq:", " 7\r\nContent-Length: 0\r\n\r\n"};
  for (const auto* part : parts) {
    const auto length = static_cast<int>(std::strlen(part));
    if (http_request_add_data(request.get(), part, length) != 0 ||
        http_request_has_error(request.get())) return false;
  }
  const auto* method = http_request_get_method(request.get());
  const auto* sequence = http_request_get_header(request.get(), "CSeq");
  return http_request_is_complete(request.get()) && method && sequence &&
    std::strcmp(method, "OPTIONS") == 0 && std::strcmp(sequence, "7") == 0;
}
bool rejectsMalformedRtsp() {
  Request request(http_request_init(), http_request_destroy);
  if (!request) return false;
  const char invalid[] = "INVALID @@@\r\n\r\n";
  http_request_add_data(request.get(), invalid, sizeof(invalid) - 1);
  return http_request_has_error(request.get());
}
bool airplaySessionLifecycle() {
  try {
    AirPlaySession session;
    for (int cycle = 0; cycle < 2; ++cycle) {
      const auto port = session.start("/data/airplay-probe.key", "Buzin Cast probe", "02:00:00:00:00:17");
      auto request = [&](const std::string& payload) {
        const int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return false;
        struct Close {int fd; ~Close() {close(fd);}} guard{fd};
        timeval timeout{2, 0};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        sockaddr_in address{}; address.sin_family = AF_INET;
        address.sin_port = htons(port); address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) return false;
        size_t offset = 0;
        while (offset < payload.size()) {
          const auto sent = send(fd, payload.data() + offset, payload.size() - offset, MSG_NOSIGNAL);
          if (sent <= 0) return false;
          offset += static_cast<size_t>(sent);
        }
        std::string response;
        while (response.find("\r\n\r\n") == std::string::npos && response.size() < 8192) {
          char block[1024];
          const auto count = recv(fd, block, sizeof(block), 0);
          if (count <= 0) return false;
          response.append(block, static_cast<size_t>(count));
        }
        return response.find("200 OK\r\n") != std::string::npos;
      };
      if (!request("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n")) return false;
      const std::string body = "Content-Location: http://example.invalid/clip.mp4\r\nStart-Position: 1.25\r\n";
      if (!request("POST /play HTTP/1.1\r\nX-Apple-Session-ID: 00112233-4455-6677-8899-aabbccddeeff\r\nContent-Type: text/parameters\r\nContent-Length: " + std::to_string(body.size()) + "\r\n\r\n" + body)) return false;
      bool playSeen = false;
      AirPlayEvent event;
      while (session.poll(event)) {
        if (event.kind == AirPlayEvent::Kind::PlayUrl) {
          playSeen = event.url == "http://example.invalid/clip.mp4" && std::abs(event.value - 1.25) < 0.001;
        }
      }
      if (!playSeen || !session.healthy()) return false;
      session.stop(); session.stop();
      if (session.poll(event)) return false;
    }
    return true;
  } catch (...) {return false;}
}

bool aesCtrVector() {
  // NIST SP 800-38A, F.5.1, first AES-128 CTR block. This verifies
  // UxPlay's helper against a known value, not just its own round trip.
  const unsigned char key[16] = {0x2b,0x7e,0x15,0x16,0x28,0xae,0xd2,0xa6,0xab,0xf7,0x15,0x88,0x09,0xcf,0x4f,0x3c};
  const unsigned char iv[16] = {0xf0,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,0xf9,0xfa,0xfb,0xfc,0xfd,0xfe,0xff};
  const unsigned char plain[16] = {0x6b,0xc1,0xbe,0xe2,0x2e,0x40,0x9f,0x96,0xe9,0x3d,0x7e,0x11,0x73,0x93,0x17,0x2a};
  const unsigned char expected[16] = {0x87,0x4d,0x61,0x91,0xb6,0x20,0xe3,0x26,0x1b,0xef,0x68,0x64,0x99,0x0d,0xb6,0xce};
  std::unique_ptr<aes_ctx_t, decltype(&aes_ctr_destroy)> context(aes_ctr_init(key, iv), aes_ctr_destroy);
  if (!context) return false;
  unsigned char cipher[16]{};
  aes_ctr_encrypt(context.get(), plain, cipher, sizeof(plain));
  return std::memcmp(cipher, expected, sizeof(expected)) == 0;
}
}

std::string runAirPlayCoreProbe() {
  const bool rtsp = fragmentedRtsp(), malformed = rejectsMalformedRtsp(), aes = aesCtrVector();
  const bool session = airplaySessionLifecycle();
  return std::string("{\"rtspParser\":") + (rtsp ? "true" : "false") +
    ",\"malformedRtspRejected\":" + (malformed ? "true" : "false") +
    ",\"aesCtrVector\":" + (aes ? "true" : "false") +
    ",\"airplaySessionLifecycle\":" + (session ? "true" : "false") + "}";
}
