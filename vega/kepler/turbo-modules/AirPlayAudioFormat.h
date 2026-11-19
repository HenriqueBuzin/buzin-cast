#pragma once
#include <cstdint>
#include <vector>

// All supported AirPlay profiles are stereo 44.1 kHz. Their distinct
// frame lengths identify the format after the transport codec is checked.
inline constexpr int airPlayAudioCodec(int samples) noexcept {
  switch (samples) {
    case 352: return 2;
    case 960: case 1024: return 4;
    case 480: case 512: return 8;
    default: return 0;
  }
}

inline std::vector<uint8_t> airPlayAacConfig(int samples) {
  switch (airPlayAudioCodec(samples)) {
    case 4: return {0x12, static_cast<uint8_t>(samples == 960 ? 0x14 : 0x10)};
    case 8: return {0xf8, 0xe8, static_cast<uint8_t>(samples == 480 ? 0x50 : 0x40), 0x00};
    default: return {};
  }
}

inline constexpr uint8_t AirPlayAlacCookie[] = {
  0,0,0,36,'a','l','a','c',0,0,0,0,0,0,1,96,0,16,40,10,14,2,0,255,0,0,0,0,0,0,0,0,0,0,172,68
};
