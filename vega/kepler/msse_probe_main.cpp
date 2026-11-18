#include "turbo-modules/MsseAudioPlayer.h"
#include "turbo-modules/AirPlayAudioWorker.h"
#include "AirPlayRtpProbe.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <apmf/errors.h>

int main(int argc, char** argv) {
  try {
    if (argc < 2) throw std::runtime_error("Expected an AAC fixture");
    int eldSpf = 0;
    bool rtpMode = false, workerMode = false;
    for (int i = 2; i < argc; ++i) {
      const std::string option(argv[i]);
      if (option == "--eld-480") eldSpf = 480;
      else if (option == "--eld-512") eldSpf = 512;
      else if (option == "--rtp") rtpMode = workerMode = true;
      else if (option == "--worker") workerMode = true;
      else throw std::runtime_error("Unknown audio probe option");
    }
    const int frameSamples = eldSpf ? eldSpf : 1024;
    const int codec = eldSpf ? 8 : 4;
    std::ifstream input(argv[1], std::ios::binary);
    if (!input) throw std::runtime_error("Fixture not found");
    std::vector<std::vector<uint8_t>> samples;
    std::vector<uint8_t> config;
    int rate = 0, channels = 0;
    constexpr int rates[] = {96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
    if (eldSpf) {
      rate = 44100; channels = 2;
      config = {0xf8, 0xe8, static_cast<uint8_t>(eldSpf == 480 ? 0x50 : 0x40), 0x00};
      for (;;) {
        uint8_t h[4]; input.read(reinterpret_cast<char*>(h), 4);
        if (input.gcount() == 0 && input.eof()) break;
        if (input.gcount() != 4 || samples.size() >= 1000) throw std::runtime_error("Invalid bounded ELD fixture");
        const uint32_t length = uint32_t(h[0]) | (uint32_t(h[1]) << 8) | (uint32_t(h[2]) << 16) | (uint32_t(h[3]) << 24);
        if (!length || length > 65536) throw std::runtime_error("Invalid ELD access unit size");
        std::vector<uint8_t> data(length);
        input.read(reinterpret_cast<char*>(data.data()), data.size());
        if (input.gcount() != static_cast<std::streamsize>(data.size())) throw std::runtime_error("Truncated ELD frame");
        samples.push_back(std::move(data));
      }
    } else for (;;) {
      uint8_t h[7]; input.read(reinterpret_cast<char*>(h), 7);
      if (input.gcount() == 0 && input.eof()) break;
      if (input.gcount() != 7 || h[0] != 255 || (h[1] & 0xf7) != 0xf1 || (h[2] >> 6) != 1 ||
          (h[6] & 3) != 0 || samples.size() >= 1000) throw std::runtime_error("Invalid bounded AAC-LC fixture");
      const int frequency = (h[2] >> 2) & 15;
      const int count = ((h[2] & 1) << 2) | (h[3] >> 6);
      if (frequency >= 13 || count < 1 || count > 7) throw std::runtime_error("Invalid ADTS configuration");
      std::vector<uint8_t> asc{static_cast<uint8_t>((2 << 3) | (frequency >> 1)),
        static_cast<uint8_t>(((frequency & 1) << 7) | (count << 3))};
      if (!config.empty() && asc != config) throw std::runtime_error("Fixture changed AAC format");
      config = asc; rate = rates[frequency]; channels = count;
      const int length = ((h[3] & 3) << 11) | (h[4] << 3) | (h[5] >> 5);
      if (length <= 7) throw std::runtime_error("Empty ADTS access unit");
      std::vector<uint8_t> data(length - 7);
      input.read(reinterpret_cast<char*>(data.data()), data.size());
      if (input.gcount() != static_cast<std::streamsize>(data.size())) throw std::runtime_error("Truncated ADTS frame");
      samples.push_back(std::move(data));
    }
    if (samples.empty()) throw std::runtime_error("Empty fixture");
    if (workerMode) {
      if (rate != 44100 || channels != 2) throw std::runtime_error("Worker fixture requires 44.1 kHz stereo");
      AirPlayAudioWorker worker([] {return std::make_unique<MsseAudioPlayer>();});
      for (int cycle = 0; cycle < 2; ++cycle) {
        worker.start();
        std::unique_ptr<AirPlayRtpProbe> transport;
        if (rtpMode) {
          transport = std::make_unique<AirPlayRtpProbe>(worker, samples, codec, frameSamples);
          transport->setVolume(-18); transport->beginEpoch();
          const auto ready = std::chrono::steady_clock::now() + std::chrono::seconds(3);
          while (worker.state().volumeDb != -18) {
            if (std::chrono::steady_clock::now() >= ready) throw std::runtime_error("RTP volume callback did not arrive");
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
          }
        } else {worker.format(codec,frameSamples); worker.volume(-18);}
        auto feed = [&](size_t count, uint64_t epoch) {
          const auto start = std::chrono::steady_clock::now();
          for (size_t i = 0; i < count; ++i) {
            std::this_thread::sleep_until(start + std::chrono::nanoseconds(i * static_cast<uint64_t>(frameSamples) * 1000000000ULL / rate));
            const auto ntp = epoch + i * static_cast<uint64_t>(frameSamples) * 1000000000ULL / rate;
            if (transport) transport->send(i, i % 97 == 0);
            else worker.sample(samples[i].data(), samples[i].size(), codec, ntp,
              static_cast<uint32_t>(0xfffff000ULL + i * frameSamples));
            if (!worker.healthy()) throw std::runtime_error(worker.state().error);
          }
        };
        if (cycle == 1) {
          feed(12,1000000000000ULL);
          const auto drained = std::chrono::steady_clock::now() + std::chrono::seconds(5);
          while (worker.state().accepted != 12) {
            if (!worker.healthy() || std::chrono::steady_clock::now() >= drained) throw std::runtime_error("Initial audio did not drain before flush");
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
          }
          if (transport) transport->flush(); else worker.flush();
          const auto flushed = std::chrono::steady_clock::now() + std::chrono::seconds(5);
          while (worker.state().flushes != 1) {
            if (!worker.healthy() || std::chrono::steady_clock::now() >= flushed) throw std::runtime_error("Flush did not finish");
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
          }
        }
        if (transport && cycle == 1) transport->beginEpoch();
        const auto acceptedBefore = worker.state().accepted;
        feed(samples.size(),2000000000000ULL);
        const double target = samples.size() * static_cast<double>(frameSamples) / rate - 0.1;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (worker.state().seconds < target || worker.state().accepted != acceptedBefore + samples.size()) {
          if (!worker.healthy() || std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Worker clock did not advance");
          std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        if (transport) {
          transport->stop(); transport->stop();
          if (transport->received() != acceptedBefore + samples.size() || transport->timed() != transport->received())
            throw std::runtime_error("RTP/NTP sample count did not match the fixture");
        }
        worker.stop(); worker.stop();
        const auto state = worker.state();
        if (!state.released || state.phase != "stopped" || !state.error.empty()) throw std::runtime_error("Worker release failed");
        std::cout << "{\"cycle\":" << cycle+1 << ",\"frames\":" << state.accepted
          << ",\"seconds\":" << state.seconds << ",\"flushes\":" << state.flushes
          << ",\"rtpSocketsReleased\":" << (transport && transport->socketsReleased() ? "true" : "false")
          << ",\"rtpFrames\":" << (transport ? transport->received() : 0)
          << ",\"ntpFrames\":" << (transport ? transport->timed() : 0)
          << ",\"duplicatesSent\":" << (transport ? transport->duplicates() : 0)
          << ",\"released\":true}" << std::endl;
      }
      return 0;
    }
    MsseAudioPlayer player;
    for (int cycle = 0; cycle < 2; ++cycle) {
      player.startAac(rate, channels, config);
      const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
      for (size_t i = 0; i < samples.size(); ++i) {
        const int64_t pts = static_cast<int64_t>(i) * frameSamples * 1000 / rate;
        const int64_t next = static_cast<int64_t>(i + 1) * frameSamples * 1000 / rate;
        while (!player.append(samples[i], pts, next - pts)) {
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Native queue timed out");
          std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
      }
      player.end();
      const double target = samples.size() * static_cast<double>(frameSamples) / rate - 0.1;
      double seconds = 0;
      while ((seconds = player.position()) < target) {
        if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Native audio clock did not advance");
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
      }
      player.stop(); player.stop();
      std::cout << "{\"cycle\":" << cycle + 1 << ",\"frames\":" << samples.size()
        << ",\"seconds\":" << seconds << ",\"released\":true}" << std::endl;
    }
    return 0;
  } catch (const std::exception& error) {std::cerr << error.what() << std::endl;}
    catch (const apmf::BaseError& error) {std::cerr << error.what() << std::endl;}
    catch (...) {std::cerr << "Unknown native MSSE failure" << std::endl;}
  return 1;
}
