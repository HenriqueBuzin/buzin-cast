#include "probes/MsseVideoPlayer.h"
#include "turbo-modules/H264Fmp4Muxer.h"
#include "turbo-modules/Fmp4VideoOutput.h"
#include <cstdio>
#include <apmf/errors.h>
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

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
    struct Frame {uint32_t pts, duration; bool keyframe; std::vector<uint8_t> data;};
    std::vector<Frame> frames;
    size_t total = 0;
    for (uint32_t i = 0; i < count; ++i) {
      const auto pts = number(), duration = number(), key = number();
      auto data = payload(); total += data.size();
      if (key > 1 || total > 16*1024*1024) throw std::runtime_error("Unbounded video fixture");
      frames.push_back({pts,duration,key != 0,std::move(data)});
    }
    if (input.peek() != EOF) throw std::runtime_error("Unexpected fixture trailing data");
    if (argc == 3 && std::string(argv[2]) == "--serve") {
      Fmp4VideoOutput stream;
      const auto url = stream.start(width,height,config);
      size_t fed = 0;
      auto report = [&] {
        const auto state = stream.state();
        std::ofstream output("/data/local-video-state.json.tmp");
        output << "{\"framesFed\":" << fed << ",\"sentBytes\":" << state.sentBytes
          << ",\"queuedBytes\":" << state.queuedBytes << ",\"connected\":" << (state.connected ? "true" : "false")
          << ",\"complete\":" << (state.complete ? "true" : "false") << ",\"released\":" << (state.released ? "true" : "false")
          << ",\"error\":\"" << state.error << "\"}" << std::endl;
        output.close();
        if (!output || std::rename("/data/local-video-state.json.tmp","/data/local-video-state.json"))
          throw std::runtime_error("Could not save native stream observation");
      };
      {
        std::ofstream descriptor("/data/local-video-url.json"); descriptor << "{\"url\":\"" << url << "\"}" << std::endl;
        if (!descriptor) throw std::runtime_error("Could not publish local stream URL");
      }
      try {
        report();
        const auto deadline = std::chrono::steady_clock::now()+std::chrono::seconds(15);
        while (!stream.state().connected) {
          if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Video player did not connect");
          std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        const auto start = std::chrono::steady_clock::now();
        for (const auto& frame : frames) {
          std::this_thread::sleep_until(start+std::chrono::milliseconds(frame.pts));
          stream.append(frame.data,int64_t(frame.pts)*1000,int64_t(frame.pts)*1000,int64_t(frame.duration)*1000);
          ++fed; report();
        }
        stream.finish(); stream.waitFinished();
      } catch (...) {
        const auto error = stream.state().error; stream.stop(); report();
        if (error != "clientClosed") throw;
      }
      stream.stop(); stream.stop(); report();
      return 0;
    }
    if (argc == 3 && std::string(argv[2]) == "--mux") {
      for (int cycle = 0; cycle < 2; ++cycle) {
        std::ofstream output("/data/buzin-video-remux.mp4",std::ios::binary);
        H264Fmp4Muxer mux(width,height,config,[&](const uint8_t* bytes, size_t size) {
          output.write(reinterpret_cast<const char*>(bytes),size);
          if (!output) throw std::runtime_error("Could not write remuxed fixture");
        });
        for (const auto& frame : frames) mux.append(frame.data,int64_t(frame.pts)*1000,int64_t(frame.pts)*1000,int64_t(frame.duration)*1000);
        mux.finish(); mux.finish(); output.close();
        if (!output) throw std::runtime_error("Could not close remuxed fixture");
        std::cout << "{\"cycle\":" << cycle+1 << ",\"frames\":" << count << ",\"released\":true}" << std::endl;
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
