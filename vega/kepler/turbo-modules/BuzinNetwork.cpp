#include "BuzinNetwork.h"
#include "AirPlayAudioPlayer.h"
#include "Fmp4VideoOutput.h"
#include "NetworkProbe.h"
#include "ReceiverSettings.h"
#include "ProtocolProbe.h"
#include <cmath>
#include <cstdio>
#include <sstream>
#include <locale>
#include <limits>
#include <fstream>
#include <iomanip>
#include <regex>
#include <stdexcept>
#include <random>

namespace BuzinNetworkTurboModule {
BuzinNetwork::BuzinNetwork() : audio([] {return std::make_unique<AirPlayAudioPlayer>();}),
  video([] {return std::make_unique<Fmp4VideoOutput>();}) {}
BuzinNetwork::~BuzinNetwork() noexcept = default;
std::string BuzinNetwork::getReceiverName() { return readReceiverName("/data/receiver-name"); }
void BuzinNetwork::setReceiverName(std::string name) { writeReceiverName("/data/receiver-name", name); }
double BuzinNetwork::startAirPlayUrl() {return startAirPlayReceiver(false);}
double BuzinNetwork::startAirPlayAudio() {return startAirPlayReceiver(true);}
double BuzinNetwork::startAirPlayVideo() {return startAirPlayReceiver(false,true);}
std::string BuzinNetwork::getAirPlayVideoState() {
  const auto state = video.state();
  std::ostringstream output; output.imbue(std::locale::classic());
  output << "{\"phase\":\"" << state.phase << "\",\"generation\":" << state.generation
    << ",\"accepted\":" << state.accepted << ",\"dropped\":" << state.dropped << ",\"flushes\":" << state.flushes
    << ",\"audioAccepted\":" << state.audioAccepted << ",\"audioDropped\":" << state.audioDropped
    << ",\"volumeDb\":" << airplay.volumeDb()
    << ",\"advertising\":" << (airplay.advertising() ? "true" : "false")
    << ",\"queuedBytes\":" << state.queuedBytes << ",\"released\":" << (state.released ? "true" : "false")
    << ",\"error\":\"" << state.error << "\"}";
  const auto json = output.str();
  std::ofstream report("/data/airplay-video-state.json.tmp",std::ios::trunc);
  report << json; report.close();
  if (!report || std::rename("/data/airplay-video-state.json.tmp","/data/airplay-video-state.json"))
    throw std::runtime_error("Could not record native video observation");
  return json;
}
void BuzinNetwork::stopAirPlayAudio() {stopAirPlayUrl(); getAirPlayAudioState();}
std::string BuzinNetwork::getAirPlayAudioState() {
  const auto state = audio.state();
  std::ostringstream output; output.imbue(std::locale::classic());
  output << "{\"phase\":\"" << (airplay.healthy() ? state.phase : "error")
    << "\",\"seconds\":" << state.seconds << ",\"accepted\":" << state.accepted
    << ",\"volumeDb\":" << state.volumeDb << ",\"dropped\":" << state.dropped << ",\"flushes\":" << state.flushes
    << ",\"released\":" << (state.released ? "true" : "false") << "}";
  // Drain non-media transport notifications in this audio-only mode.
  AirPlayEvent ignored;
  for (int count = 0; count < 16 && airplay.poll(ignored); ++count) {}
  const auto json = output.str();
  std::ofstream report("/data/airplay-audio-state.json", std::ios::trunc);
  report << json; report.close();
  if (!report) throw std::runtime_error("Could not record native audio observation");
  return json;
}
double BuzinNetwork::startAirPlayReceiver(bool audioMode, bool videoMode) {
  std::string identity;
  std::ifstream saved("/data/airplay-identity");
  if (saved) std::getline(saved, identity);
  if (identity.empty()) {
    std::random_device random;
    unsigned char bytes[6];
    for (auto& byte : bytes) byte = static_cast<unsigned char>(random());
    bytes[0] = (bytes[0] | 2) & 0xfe;
    char encoded[18];
    std::snprintf(encoded, sizeof(encoded), "%02x:%02x:%02x:%02x:%02x:%02x", bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
    identity = encoded;
    std::ofstream output("/data/airplay-identity.tmp", std::ios::trunc);
    output << identity; output.close();
    if (!output || std::rename("/data/airplay-identity.tmp", "/data/airplay-identity") != 0)
      throw std::runtime_error("Could not save local AirPlay identity");
  }
  const auto port = airplay.start("/data/airplay.key", getReceiverName(), identity, false,
    audioMode ? &audio : nullptr,videoMode ? &video : nullptr);
  videoModeActive = videoMode;
  std::ofstream report(videoMode ? "/data/airplay-video-port.json" : audioMode ? "/data/airplay-audio-port.json" : "/data/airplay-port.json", std::ios::trunc);
  report << "{\"port\":" << port << "}"; report.close();
  if (!report) {airplay.stop(); throw std::runtime_error("Could not record AirPlay listener state");}
  return port;
}
void BuzinNetwork::stopAirPlayUrl() {
  airplay.stop();
  std::remove("/data/airplay-port.json");
  std::remove("/data/airplay-audio-port.json");
  std::remove("/data/airplay-video-port.json");
  if (videoModeActive) {videoModeActive = false; getAirPlayVideoState();}
}
void BuzinNetwork::updateAirPlayState(double position, double duration, double rate, bool ready) {
  airplay.updatePlayback(position, duration, rate, ready);
}
std::string BuzinNetwork::pollAirPlayCommand() {
  if (videoModeActive) getAirPlayVideoState();
  if (!airplay.healthy()) throw std::runtime_error("AirPlay callback queue failed");
  AirPlayEvent event;
  for (int n = 0; n < 16 && airplay.poll(event); ++n) {
    const char* kind = nullptr;
    switch (event.kind) {
      case AirPlayEvent::Kind::PlayUrl: kind = "play"; break;
      case AirPlayEvent::Kind::MirrorUrl: kind = "mirror"; break;
      case AirPlayEvent::Kind::Seek: kind = "seek"; break;
      case AirPlayEvent::Kind::Rate: kind = "rate"; break;
      case AirPlayEvent::Kind::Volume: kind = "volume"; break;
      case AirPlayEvent::Kind::Pin: kind = "pin"; break;
      case AirPlayEvent::Kind::Stop: case AirPlayEvent::Kind::Reset: kind = "stop"; break;
      case AirPlayEvent::Kind::Audio: case AirPlayEvent::Kind::Video:
        throw std::runtime_error("AirPlay mirroring output is not available");
      default: continue;
    }
    std::string quoted = "\"";
    constexpr char hex[] = "0123456789abcdef";
    for (unsigned char c : event.url) {
      if (c == '"' || c == '\\') {quoted += '\\'; quoted += c;}
      else if (c < 32) {quoted += "\\u00"; quoted += hex[c >> 4]; quoted += hex[c & 15];}
      else quoted += c;
    }
    quoted += '"';
    std::ostringstream output; output.imbue(std::locale::classic());
    output << "{\"kind\":\"" << kind << "\",\"value\":" << event.value << ",\"url\":" << quoted << "}";
    return output.str();
  }
  return "";
}
double BuzinNetwork::startFCast() {
  const auto listeningPort = fcast.start();
  try {
    discovery.startFCast(static_cast<uint16_t>(listeningPort), getReceiverName());
    return listeningPort;
  } catch (...) {
    fcast.stop();
    throw;
  }
}
void BuzinNetwork::stopFCast() {discovery.stop(); fcast.stop();}
std::string BuzinNetwork::pollFCastCommand() {return fcast.pollCommand();}
bool BuzinNetwork::isFCastClient(double client) {return fcast.isClient(client);}
void BuzinNetwork::disconnectFCastClient(double client) {fcast.disconnect(client);}
void BuzinNetwork::fcastError(double client, std::string message) {fcast.error(client, message);}
void BuzinNetwork::updateFCastState(std::string playback, std::string volume) {fcast.update(playback, volume);}
std::string BuzinNetwork::startDlna() {
  const auto name = getReceiverName();
  const std::string path = "/data/dlna-uuid";
  std::ifstream input(path);
  std::string uuid;
  if (input) std::getline(input, uuid);
  static const std::regex identity("uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  if (!std::regex_match(uuid, identity)) {
    std::random_device random;
    unsigned char bytes[16];
    for (auto& byte : bytes) byte = static_cast<unsigned char>(random());
    bytes[6] = (bytes[6] & 15) | 0x40;
    bytes[8] = (bytes[8] & 63) | 0x80;
    std::ostringstream generated;
    generated << "uuid:" << std::hex << std::setfill('0');
    for (size_t i = 0; i < sizeof(bytes); ++i) {
      if (i == 4 || i == 6 || i == 8 || i == 10) generated << '-';
      generated << std::setw(2) << static_cast<unsigned>(bytes[i]);
    }
    uuid = generated.str();
    const auto temporary = path + ".tmp";
    std::ofstream output(temporary, std::ios::trunc);
    output << uuid;
    output.close();
    if (!output || std::rename(temporary.c_str(), path.c_str()) != 0) {
      std::remove(temporary.c_str());
      throw std::runtime_error("Could not save DLNA identity");
    }
  }
  const auto port = dlna.start(uuid);
  try {
    std::ofstream report("/data/dlna-port.json", std::ios::trunc);
    report << "{\"port\":" << port << '}';
    report.close();
    if (!report) throw std::runtime_error("Could not record DLNA listener state");
    std::ostringstream info;
    info << "{\"port\":" << port << ",\"uuid\":" << std::quoted(uuid) << ",\"name\":" << std::quoted(name) << '}';
    return info.str();
  } catch (...) {
    stopDlna();
    throw;
  }
}
void BuzinNetwork::stopDlna() {
  dlna.stop();
  std::remove("/data/dlna-port.json");
}
std::string BuzinNetwork::pollDlnaRequest() {return dlna.poll();}
void BuzinNetwork::respondDlna(double requestId, double status, std::string body) {
  dlna.respond(requestId, status, std::move(body));
}
std::string BuzinNetwork::getReport() {
  auto report = runNetworkProbe();
  report.pop_back();
  report += "," + runAirPlayCoreProbe().substr(1);
  // Only boolean diagnostic outcomes; no addresses or user content are saved.
  std::ofstream output("/data/network-probe.json", std::ios::trunc);
  output << report << '\n';
  output.close();
  if (!output) throw std::runtime_error("Could not save network diagnostic");
  return report;
}
std::string BuzinNetwork::recordMediaCapabilities(double h264, double hevc, double aac) {
  // Counts come from the public system media descriptor; persist no content.
  for (const double count : {h264, hevc, aac}) {
    if (!std::isfinite(count) || count < 0 || std::floor(count) != count ||
        count > std::numeric_limits<int>::max()) {
      throw std::invalid_argument("Invalid media capability count");
    }
  }
  const auto report = "{\"h264Decoders\":" + std::to_string(static_cast<int>(h264)) +
      ",\"hevcDecoders\":" + std::to_string(static_cast<int>(hevc)) +
      ",\"aacDecoders\":" + std::to_string(static_cast<int>(aac)) + "}";
  std::ofstream output("/data/media-capabilities.json", std::ios::trunc);
  output << report << '\n';
  output.close();
  if (!output) throw std::runtime_error("Could not save media diagnostic");
  return report;
}
void BuzinNetwork::recordVideoPlaybackQuality(double total, double dropped, bool attached) {
  const bool available = total != -1 || dropped != -1;
  if (available && (!std::isfinite(total) || !std::isfinite(dropped) || total < 0 || total > 4294967295.0 ||
      dropped < 0 || dropped > total || total != std::floor(total) || dropped != std::floor(dropped)))
    throw std::invalid_argument("Invalid video quality observation");
  std::ofstream output("/data/video-quality.json.tmp",std::ios::trunc);
  output.imbue(std::locale::classic());
  output << "{\"available\":" << (available ? "true" : "false") << ",\"attached\":" << (attached ? "true" : "false")
    << ",\"total\":" << total << ",\"dropped\":" << dropped << "}\n";
  output.close();
  if (!output || std::rename("/data/video-quality.json.tmp","/data/video-quality.json"))
    throw std::runtime_error("Could not save video quality observation");
}
void BuzinNetwork::recordPlaybackState(std::string state, double seconds) {
  if (state != "loading" && state != "playing" && state != "paused" &&
      state != "ended" && state != "error" && state != "released") {
    throw std::invalid_argument("Invalid playback state");
  }
  if (!std::isfinite(seconds) || seconds < 0) {
    throw std::invalid_argument("Invalid playback observation");
  }
  std::ofstream output("/data/playback-state.json.tmp", std::ios::trunc);
  output.imbue(std::locale::classic());
  output << "{\"state\":\"" << state << "\",\"seconds\":" << seconds
         << "}\n";
  output.close();
  if (!output || std::rename("/data/playback-state.json.tmp", "/data/playback-state.json") != 0) {
    throw std::runtime_error("Could not save playback observation");
  }
}

}
