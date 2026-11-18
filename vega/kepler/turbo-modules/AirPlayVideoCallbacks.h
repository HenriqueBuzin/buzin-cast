#pragma once
#include "AirPlayVideoWorker.h"
#include <cmath>
extern "C" {
#include "stream.h"
}

// Both the real UxPlay receiver and the fixture probe use this native boundary.
inline void forwardAirPlayVideo(AirPlayVideoWorker& worker, const video_decode_struct* frame) noexcept {
  if (!frame) return;
  worker.sample(frame->data,frame->data_len > 0 ? static_cast<size_t>(frame->data_len) : 0,
    frame->is_h265,frame->ntp_time_local);
}
inline void forwardAirPlayVideoSize(AirPlayVideoWorker& worker, const float* width, const float* height) noexcept {
  // UxPlay's size callback reports integer dimensions encoded as floats.
  if (!width || !height || !std::isfinite(*width) || !std::isfinite(*height) ||
      *width < 16 || *width > 3840 || *height < 16 || *height > 2160 ||
      std::floor(*width) != *width || std::floor(*height) != *height) {
    worker.format(0,0); return;
  }
  worker.format(static_cast<int>(*width),static_cast<int>(*height));
}
