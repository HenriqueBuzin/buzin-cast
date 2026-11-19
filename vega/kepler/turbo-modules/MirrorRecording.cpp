#include "MirrorRecording.h"
#include "AirPlayAudioFormat.h"
#include "H264AnnexB.h"
#include "H264Fmp4Muxer.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <filesystem>
#include <mutex>
#include <regex>
#include <stdexcept>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <utility>

namespace {
namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
constexpr size_t MaxQueuedBytes = 8 * 1024 * 1024;
constexpr size_t MaxQueuedSamples = 240;
constexpr uint64_t MinStartSpace = 128 * 1024 * 1024;
constexpr uint64_t MinWriteSpace = 32 * 1024 * 1024;
constexpr uint64_t MaxFileBytes = uint64_t{2} * 1024 * 1024 * 1024;

struct Packet {
  bool audio;
  int64_t pts, dts, duration;
  std::vector<uint8_t> bytes;
};

bool recordingName(const std::string& name) {
  static const std::regex pattern("Buzin-[0-9]{13,}-[A-Za-z0-9]{6}\\.mp4");
  return name.size() <= 64 && std::regex_match(name, pattern);
}

class RecordingFile {
public:
  explicit RecordingFile(const fs::path& directory) {
    const auto stamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    temporary = (directory / ("Buzin-" + std::to_string(stamp) + "-XXXXXX.part")).string();
    const auto fd = mkstemps(temporary.data(), 5);
    if (fd < 0) throw std::runtime_error("Could not create private recording");
    file = fdopen(fd, "wb");
    if (!file) {
      close(fd);
      std::remove(temporary.c_str());
      throw std::runtime_error("Could not open private recording");
    }
  }
  ~RecordingFile() {
    if (file) fclose(file);
    if (!temporary.empty()) std::remove(temporary.c_str());
  }
  void write(const uint8_t* data, size_t size) {
    if (fwrite(data, 1, size, file) != size) throw std::runtime_error("Could not write recording");
    bytes += size;
  }
  void commit() {
    const auto final = fs::path(temporary).replace_extension(".mp4");
    const bool synced = fflush(file) == 0 && fsync(fileno(file)) == 0;
    const auto closed = fclose(std::exchange(file, nullptr));
    if (!synced || closed || std::rename(temporary.c_str(), final.c_str()))
      throw std::runtime_error("Could not save recording");
    temporary.clear();
  }
  uint64_t size() const {return bytes;}

private:
  std::string temporary;
  FILE* file = nullptr;
  uint64_t bytes = 0;
};
}

struct MirrorRecording::Impl {
  struct Format {
    int width = 0, height = 0, audioSamples = 0;
    int64_t start = 0;
    std::vector<uint8_t> config;
  };
  explicit Impl(std::string path) : directory(std::move(path)) {}
  const fs::path directory;
  mutable std::mutex mutex;
  std::condition_variable changed;
  std::thread disk;
  std::atomic<bool> accepting{false};
  Format format;
  std::deque<Packet> packets;
  size_t queuedBytes = 0;
  std::string phase = "idle";
  bool finishing = false, limited = false;

  void finishLocked(bool limit = false) {
    if (!accepting) return;
    accepting = false;
    finishing = true;
    limited = limit;
    phase = "saving";
    changed.notify_one();
  }

  void enqueue(bool audio, const std::vector<uint8_t>& bytes, int64_t pts, int64_t dts, int64_t duration) {
    if (packets.size() >= MaxQueuedSamples || bytes.size() > MaxQueuedBytes - queuedBytes) {
      finishLocked(true);
      return;
    }
    packets.push_back({audio, pts, dts, duration, bytes});
    queuedBytes += bytes.size();
    changed.notify_one();
  }

  void run() noexcept {
    std::string result = "empty";
    try {
      Format captured;
      {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] {return finishing || !packets.empty();});
        captured = std::move(format);
      }
      if (captured.width > 0) result = write(captured);
    } catch (...) {result = "error";}
    std::lock_guard lock(mutex);
    accepting = false;
    phase = std::move(result);
    finishing = false;
    format = {};
    packets.clear();
    queuedBytes = 0;
  }

  std::string write(const Format& captured) {
    RecordingFile file(directory);
    std::unique_ptr<H264Fmp4Muxer> mux;
    std::vector<Packet> initialVideo;
    bool hasAudio = false;

    auto append = [&](const Packet& packet) {
      if (!packet.audio) {
        mux->append(packet.bytes, packet.pts - captured.start,
                    packet.dts - captured.start, packet.duration);
      } else if (hasAudio) {
        mux->appendAudio(packet.bytes, packet.pts - captured.start);
      }
    };
    auto startMux = [&](const Packet* firstAudio) {
      const int samples = firstAudio ? captured.audioSamples : 0;
      hasAudio = samples != 0;
      mux = std::make_unique<H264Fmp4Muxer>(captured.width, captured.height, captured.config,
          [&](const uint8_t* bytes, size_t size) {file.write(bytes, size);},
          samples, airPlayAudioCodec(samples) == 2);
      append(initialVideo.front());
      if (firstAudio) append(*firstAudio);
      for (size_t n = 1; n < initialVideo.size(); ++n) append(initialVideo[n]);
      initialVideo.clear();
    };

    auto checkDisk = Clock::now() + 3s;
    size_t startupBytes = 0;
    for (;;) {
      Packet packet;
      {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] {return finishing || !packets.empty();});
        if (packets.empty()) break;
        packet = std::move(packets.front());
        packets.pop_front();
        queuedBytes -= packet.bytes.size();
      }
      if (mux) {
        append(packet);
      } else if (packet.audio) {
        startMux(&packet);
      } else {
        startupBytes += packet.bytes.size();
        initialVideo.push_back(std::move(packet));
        if (!captured.audioSamples || initialVideo.size() >= 24 || startupBytes >= MaxQueuedBytes)
          startMux(nullptr);
      }
      bool full = file.size() >= MaxFileBytes;
      if (Clock::now() >= checkDisk) {
        full = full || fs::space(directory).available < MinWriteSpace;
        checkDisk = Clock::now() + 3s;
      }
      if (full) {
        std::lock_guard lock(mutex);
        finishLocked(true);
        limited = true;
        break;
      }
    }
    if (!mux && !initialVideo.empty()) startMux(nullptr);
    if (!mux) return "empty";
    mux->finish();
    file.commit();
    std::lock_guard lock(mutex);
    return limited ? "limited" : "saved";
  }

  fs::path entry(const std::string& name) const {
    if (!recordingName(name)) throw std::invalid_argument("Invalid recording name");
    const auto path = directory / name;
    if (!fs::is_regular_file(fs::symlink_status(path))) throw std::runtime_error("Recording not found");
    return path;
  }
};

MirrorRecording::MirrorRecording(std::string directory) : impl(std::make_unique<Impl>(std::move(directory))) {}
MirrorRecording::~MirrorRecording() {
  stop();
  if (impl->disk.joinable()) impl->disk.join();
}

std::string MirrorRecording::state() const {
  std::lock_guard lock(impl->mutex);
  return impl->phase;
}

void MirrorRecording::arm() {
  {
    std::lock_guard lock(impl->mutex);
    if (impl->accepting || impl->finishing) return;
  }
  if (impl->disk.joinable()) impl->disk.join();
  if (!fs::exists(impl->directory) && mkdir(impl->directory.c_str(), 0700))
    throw std::runtime_error("Could not create recording directory");
  if (!fs::is_directory(fs::symlink_status(impl->directory)))
    throw std::runtime_error("Invalid recording directory");
  if (fs::space(impl->directory).available < MinStartSpace) {
    std::lock_guard lock(impl->mutex);
    impl->phase = "space";
    return;
  }
  for (const auto& entry : fs::directory_iterator(impl->directory)) {
    const auto name = entry.path().filename().string();
    if (name.ends_with(".part") && recordingName(entry.path().filename().replace_extension(".mp4").string())) {
      std::error_code ignored;
      fs::remove(entry.path(), ignored);
    }
  }
  std::lock_guard lock(impl->mutex);
  impl->limited = false;
  impl->accepting = true;
  impl->phase = "waiting";
  try {impl->disk = std::thread([this] {impl->run();});}
  catch (...) {impl->accepting = false; impl->phase = "error"; throw;}
}

void MirrorRecording::stop() noexcept {
  std::lock_guard lock(impl->mutex);
  impl->finishLocked();
}

void MirrorRecording::endSource() noexcept {
  if (!impl->accepting) return;
  std::lock_guard lock(impl->mutex);
  if (impl->phase == "recording") impl->finishLocked();
}

void MirrorRecording::video(int width, int height, const std::vector<uint8_t>& config, int audioSamples,
                            const std::vector<uint8_t>& bytes, int64_t pts, int64_t dts, int64_t duration) noexcept {
  if (!impl->accepting) return;
  std::lock_guard lock(impl->mutex);
  if (!impl->accepting) return;
  try {
    if (impl->phase == "waiting") {
      const auto units = BuzinH264::split(bytes);
      if (std::none_of(units.begin(), units.end(), [](const auto& nal) {return nal.type == 5;})) return;
      impl->format = {width, height, audioSamples, dts, config};
      impl->phase = "recording";
    }
    impl->enqueue(false, bytes, pts, dts, duration);
  } catch (...) {impl->finishLocked(true);}
}

void MirrorRecording::audio(const std::vector<uint8_t>& bytes, int64_t pts) noexcept {
  if (!impl->accepting) return;
  std::lock_guard lock(impl->mutex);
  // The disk worker owns its own copy of the format once the IDR is queued.
  if (impl->phase != "recording" || !impl->format.audioSamples || pts < impl->format.start) return;
  try {impl->enqueue(true, bytes, pts, pts, 0);}
  catch (...) {impl->finishLocked(true);}
}

std::vector<MirrorRecording::Entry> MirrorRecording::list() const {
  std::vector<Entry> entries;
  if (!fs::is_directory(fs::symlink_status(impl->directory))) return entries;
  for (const auto& entry : fs::directory_iterator(impl->directory)) {
    const auto name = entry.path().filename().string();
    if (recordingName(name) && fs::is_regular_file(entry.symlink_status())) {
      std::error_code error;
      const auto size = entry.file_size(error);
      if (!error && size) entries.push_back({name, size});
    }
  }
  std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {return a.name > b.name;});
  return entries;
}
std::string MirrorRecording::url(const std::string& name) const {
  return "file://" + impl->entry(name).string();
}
void MirrorRecording::remove(const std::string& name) {
  if (!fs::remove(impl->entry(name))) throw std::runtime_error("Recording could not be deleted");
}
