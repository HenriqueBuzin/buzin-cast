#pragma once
#include "AirPlayAudioWorker.h"
extern "C" {
#include "stream.h"
}

// Shared by the receiver and the real RTP probe; compressed samples stay native.
inline void forwardAirPlayAudio(AirPlayAudioWorker& worker, const audio_decode_struct* frame) noexcept {
  if (!frame) return;
  worker.sample(frame->data, frame->data_len > 0 ? static_cast<size_t>(frame->data_len) : 0,
    frame->ct, frame->ntp_time_local, frame->rtp_time);
}
