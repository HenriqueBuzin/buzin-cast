#pragma once
#include "AudioSampleSink.h"
#include "MsseAudioPlayer.h"
#include "AlacAudioPlayer.h"

class AirPlayAudioPlayer : public AudioSampleSink {
public:
  void startAac(int rate, int channels, const std::vector<uint8_t>& config) override {
    stop(); alac = false; aac.startAac(rate,channels,config);
  }
  void startAlac() override {stop(); alac = true; pcm.start();}
  bool append(const std::vector<uint8_t>& data, int64_t timestamp, int64_t duration) override {
    return alac ? pcm.append(data,timestamp,duration) : aac.append(data,timestamp,duration);
  }
  double position() override {return alac ? pcm.position() : aac.position();}
  void setVolume(double volume) override {if (alac) pcm.setVolume(volume); else aac.setVolume(volume);}
  void stop() override {aac.stop(); pcm.stop();}
private:
  MsseAudioPlayer aac;
  AlacAudioPlayer pcm;
  bool alac = false;
};
