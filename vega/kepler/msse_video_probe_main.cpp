#include "probes/MsseVideoPlayer.h"
#include "turbo-modules/H264Fmp4Muxer.h"
#include "turbo-modules/Fmp4VideoOutput.h"
#include "turbo-modules/AirPlayVideoCallbacks.h"
#include "AirPlayMirrorProbe.h"
#include <cstdio>
#include <apmf/errors.h>
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
// Observe the same worker and muxer as playback, with the bytes written to a
// fixture file for independent decoding/timestamp checks on the host.
class FileVideoOutput final : public VideoSampleSink {
public:
  std::string start(int width, int height, const std::vector<uint8_t>& config, int aacSamples) override {
    stop();
    file.clear();
    file.open("/data/buzin-video-remux.mp4",std::ios::binary);
    if (!file) throw std::runtime_error("Could not open A/V probe output");
    output = {};
    output.released = false;
    output.connected = true;
    mux = std::make_unique<H264Fmp4Muxer>(width,height,config,[this](const uint8_t* bytes,size_t size) {
      file.write(reinterpret_cast<const char*>(bytes),size);
      if (!file) throw std::runtime_error("Could not write A/V probe output");
    },aacSamples);
    return "file-probe-output";
  }
  void append(const std::vector<uint8_t>& sample, int64_t pts, int64_t dts, int64_t duration) override {
    mux->append(sample,pts,dts,duration);
  }
  void appendAac(const std::vector<uint8_t>& sample, int64_t pts) override {mux->appendAac(sample,pts);}
  void finish() override {
    mux->finish();
    file.close();
    if (!file) throw std::runtime_error("Could not close A/V probe output");
    output.complete = true;
  }
  void waitFinished(std::chrono::milliseconds) override {}
  void cancel() noexcept override {}
  void stop() noexcept override {
    mux.reset();
    if (file.is_open()) file.close();
    output.released = true;
  }
  LocalVideoStreamState state() const override {return output;}
private:
  std::ofstream file;
  std::unique_ptr<H264Fmp4Muxer> mux;
  LocalVideoStreamState output;
};
}

int main(int argc, char** argv) {
  try {
    if (argc != 2 && argc != 3) throw std::runtime_error("Expected our bounded video fixture");
    std::ifstream input(argv[1],std::ios::binary);
    auto number = [&]() -> uint32_t {
      uint8_t bytes[4]; input.read(reinterpret_cast<char*>(bytes),4);
      if (input.gcount() != 4) throw std::runtime_error("Truncated fixture integer");
      return uint32_t(bytes[0]) | (uint32_t(bytes[1])<<8) | (uint32_t(bytes[2])<<16) | (uint32_t(bytes[3])<<24);
    };
    auto payload = [&]() {
      const auto size = number();
      if (!size || size > 2*1024*1024) throw std::runtime_error("Invalid fixture sample size");
      std::vector<uint8_t> data(size); input.read(reinterpret_cast<char*>(data.data()),size);
      if (input.gcount() != static_cast<std::streamsize>(size)) throw std::runtime_error("Truncated video fixture");
      return data;
    };
    const auto width = number(), height = number();
    const auto config = payload();
    const auto count = number();
    if (!count || count > 600) throw std::runtime_error("Invalid video frame count");
    using Frame = AirPlayVideoFixtureFrame;
    std::vector<Frame> frames;
    size_t total = 0;
    for (uint32_t i = 0; i < count; ++i) {
      const auto pts = number(), duration = number(), key = number();
      auto data = payload(); total += data.size();
      if (key > 1 || total > 16*1024*1024) throw std::runtime_error("Unbounded video fixture");
      frames.push_back({pts,duration,key != 0,std::move(data)});
    }
    const bool avMux = argc == 3 && std::string(argv[2]) == "--av-mux";
    std::vector<std::vector<uint8_t>> audio;
    uint32_t audioOffsetUs = 0;
    if (avMux) {
      audioOffsetUs = number();
      const auto audioCount = number();
      if (audioOffsetUs > 1000000 || !audioCount || audioCount > 600) {
        throw std::runtime_error("Invalid AAC fixture timing");
      }
      audio.reserve(audioCount);
      for (uint32_t i = 0; i < audioCount; ++i) {
        auto data = payload();
        total += data.size();
        if (data.size() > 65536 || total > 16*1024*1024) {
          throw std::runtime_error("Unbounded AAC fixture");
        }
        audio.push_back(std::move(data));
      }
    }
    if (input.peek() != EOF) throw std::runtime_error("Unexpected fixture trailing data");
    if (argc == 3 && (std::string(argv[2]) == "--serve" || std::string(argv[2]) == "--mirror-serve")) {
      AirPlayVideoWorker worker([] {return std::make_unique<Fmp4VideoOutput>();});
      worker.start();
      const bool encryptedMirror = std::string(argv[2]) == "--mirror-serve";
      std::unique_ptr<AirPlayMirrorProbe> mirror;
      if (encryptedMirror) mirror = std::make_unique<AirPlayMirrorProbe>(worker,width,height,config,frames);
      const float reportedWidth = width, reportedHeight = height;
      if (!mirror) forwardAirPlayVideoSize(worker,&reportedWidth,&reportedHeight);
      auto submit = [&](size_t index) {
        if (mirror) {mirror->send(index); return;}
        const auto& frame = frames[index];
        video_decode_struct sample{};
        sample.data = const_cast<uint8_t*>(frame.data.data()); sample.data_len = frame.data.size();
        sample.ntp_time_local = uint64_t(1000000000)+uint64_t(frame.pts)*1000000;
        forwardAirPlayVideo(worker,&sample);
      };
      auto report = [&] {
        const auto workerState = worker.state();
        const auto& state = workerState.output;
        std::ofstream output("/data/local-video-state.json.tmp");
        output << "{\"framesFed\":" << workerState.accepted << ",\"sentBytes\":" << state.sentBytes
          << ",\"workerOwned\":true,\"callbackQueuedBytes\":" << workerState.queuedBytes
          << ",\"dropped\":" << workerState.dropped << ",\"generation\":" << workerState.generation
          << ",\"mirrorEncrypted\":" << (mirror ? "true" : "false")
          << ",\"mirrorReceived\":" << (mirror ? mirror->received() : 0)
          << ",\"mirrorTimed\":" << (mirror ? mirror->timed() : 0)
          << ",\"mirrorWrites\":" << (mirror ? mirror->fragmentedWrites() : 0)
          << ",\"mirrorReleased\":" << (mirror && mirror->socketsReleased() ? "true" : "false")
          << ",\"queuedBytes\":" << state.queuedBytes << ",\"connected\":" << (state.connected ? "true" : "false")
          << ",\"complete\":" << (state.complete ? "true" : "false") << ",\"released\":" << (workerState.released ? "true" : "false")
          << ",\"error\":\"" << workerState.error << "\"}" << std::endl;
        output.close();
        if (!output || std::rename("/data/local-video-state.json.tmp","/data/local-video-state.json"))
          throw std::runtime_error("Could not save native stream observation");
      };
      auto check = [&] {
        if (!worker.healthy()) throw std::runtime_error("Native video worker failed");
        if (mirror && !mirror->healthy()) throw std::runtime_error("Decrypted mirror transport or fixture mismatch");
      };
      try {
        submit(0);
        AirPlayVideoCommand command;
        auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(15);
        for (;;) {
          check();
          if (worker.poll(command) && command.kind == AirPlayVideoCommand::Kind::Play) break;
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Video worker did not publish a URL");
          std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        {
          std::ofstream descriptor("/data/local-video-url.json"); descriptor << "{\"url\":\"" << command.url << "\"}" << std::endl;
          if (!descriptor) throw std::runtime_error("Could not publish local stream URL");
        }
        report();
        while (!worker.state().output.connected) {
          check(); report();
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Video player did not connect");
          std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        const auto start = std::chrono::steady_clock::now();
        for (size_t i = 1; i < frames.size(); ++i) {
          const auto& frame = frames[i];
          std::this_thread::sleep_until(start+std::chrono::milliseconds(frame.pts));
          check(); submit(i); report();
        }
        if (mirror) {
          deadline = std::chrono::steady_clock::now()+std::chrono::seconds(3);
          while (mirror->received() != count) {
            check();
            if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Native mirror did not receive every frame");
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
          }
          mirror->stop();
        }
        worker.end();
        deadline = std::chrono::steady_clock::now()+std::chrono::seconds(20);
        while (worker.state().phase != "ended") {
          check(); report();
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Video output did not finish");
          std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
      } catch (...) {
        const auto error = worker.state().error;
        if (mirror) mirror->stop();
        worker.stop(); report();
        if (error != "clientClosed") throw;
      }
      if (mirror) {mirror->stop(); mirror->stop();}
      worker.stop(); worker.stop(); report();
      return 0;
    }
    if (avMux) {
      for (int cycle = 0; cycle < 2; ++cycle) {
        AirPlayVideoWorker worker([] {return std::make_unique<FileVideoOutput>();});
        worker.start();
        worker.audioFormat(4,1024);
        const float reportedWidth = width, reportedHeight = height;
        forwardAirPlayVideoSize(worker,&reportedWidth,&reportedHeight);
        constexpr uint64_t EpochNs = 1000000000;
        auto audioPts = [&](size_t index) {
          return audioOffsetUs+(uint64_t(index)*1024*1000000+22050)/44100;
        };
        size_t nextVideo = 0, nextAudio = 0;
        const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(10);
        auto check = [&] {
          const auto state = worker.state();
          if (!state.error.empty()) throw std::runtime_error(state.error);
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("A/V worker deadline");
          return state;
        };
        while (nextVideo < frames.size() || nextAudio < audio.size()) {
          const auto state = check();
          const auto pending = nextVideo+nextAudio-state.accepted-state.audioAccepted;
          if (pending >= 32) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
          }
          if (nextVideo < frames.size() && (nextAudio == audio.size() ||
              uint64_t(frames[nextVideo].pts)*1000 <= audioPts(nextAudio))) {
            auto& frame = frames[nextVideo++];
            video_decode_struct sample{};
            sample.data = frame.data.data();
            sample.data_len = frame.data.size();
            sample.ntp_time_local = EpochNs+uint64_t(frame.pts)*1000000;
            forwardAirPlayVideo(worker,&sample);
          } else {
            auto& bytes = audio[nextAudio];
            audio_decode_struct sample{};
            sample.data = bytes.data();
            sample.data_len = bytes.size();
            sample.ct = 4;
            sample.ntp_time_local = EpochNs+audioPts(nextAudio++)*1000;
            forwardAirPlayMirrorAudio(worker,&sample);
          }
        }
        worker.end();
        while (check().phase != "ended") std::this_thread::sleep_for(std::chrono::milliseconds(1));
        const auto state = check();
        if (!state.released || state.accepted != count || state.audioAccepted != audio.size() ||
            state.dropped || state.audioDropped || state.queuedBytes) {
          throw std::runtime_error("A/V worker lost a fixture packet or failed to release");
        }
        std::cout << "{\"cycle\":" << cycle+1 << ",\"frames\":" << state.accepted
          << ",\"audioPackets\":" << state.audioAccepted << ",\"workerOwned\":true,\"released\":true}" << std::endl;
      }
      return 0;
    }
    if (argc == 3 && std::string(argv[2]) == "--mux") {
      for (int cycle = 0; cycle < 2; ++cycle) {
        std::ofstream output("/data/buzin-video-remux.mp4",std::ios::binary);
        H264Fmp4Muxer mux(width,height,config,[&](const uint8_t* bytes, size_t size) {
          output.write(reinterpret_cast<const char*>(bytes),size);
          if (!output) throw std::runtime_error("Could not write remuxed fixture");
        });
        for (const auto& frame : frames) {
          mux.append(frame.data, int64_t(frame.pts)*1000, int64_t(frame.pts)*1000, int64_t(frame.duration)*1000);
        }
        mux.finish(); mux.finish(); output.close();
        if (!output) throw std::runtime_error("Could not close remuxed fixture");
        std::cout << "{\"cycle\":" << cycle+1 << ",\"frames\":" << count
          << ",\"audioPackets\":" << audio.size() << ",\"released\":true}" << std::endl;
      }
      return 0;
    }
    MsseVideoPlayer player;
    for (int cycle = 0; cycle < 2; ++cycle) {
      player.start(width,height,config,argc == 3 ? argv[2] : "video/avc");
      const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(20);
      for (const auto& frame : frames) {
        while (!player.append(frame.data,frame.pts,frame.duration,frame.keyframe)) {
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Video queue timed out");
          std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
      }
      player.end();
      double seconds = 0;
      while ((seconds = player.position()) < 7.8) {
        if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Native video clock did not advance");
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
      }
      const auto presented = player.totalFrames();
      player.stop(); player.stop();
      std::cout << "{\"cycle\":" << cycle+1 << ",\"frames\":" << count
        << ",\"totalFrames\":" << presented << ",\"seconds\":" << seconds << ",\"released\":true}" << std::endl;
    }
    return 0;
  } catch (const std::exception& error) {std::cerr << error.what() << std::endl;}
    catch (const apmf::BaseError& error) {std::cerr << error.what() << std::endl;}
  return 1;
}
