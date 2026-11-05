#include "ProtocolProbe.h"
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
  return std::string("{\"rtspParser\":") + (rtsp ? "true" : "false") +
    ",\"malformedRtspRejected\":" + (malformed ? "true" : "false") +
    ",\"aesCtrVector\":" + (aes ? "true" : "false") + "}";
}
