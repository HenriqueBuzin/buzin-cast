#include "MediaDownload.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <memory>
#include <mutex>
#include <regex>
#include <stdexcept>
#include <string_view>
#include <thread>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/error.h>
}

namespace {
namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
constexpr int64_t maxFileSize = 2LL * 1024 * 1024 * 1024;
constexpr uint64_t minFreeSpace = 32 * 1024 * 1024;
constexpr int bufferSize = 64 * 1024;

bool webUrl(std::string_view url) {
  const auto scheme = url.starts_with("https://") ? 8 : url.starts_with("http://") ? 7 : 0;
  if (!scheme || url.size() > 8192 || url.find('\\') != url.npos ||
      std::any_of(url.begin(), url.end(), [](unsigned char c) {return c <= 32 || c == 127;})) return false;
  const auto authority = url.substr(scheme, url.find_first_of("/?#", scheme) - scheme);
  return !authority.empty() && authority.find('@') == authority.npos;
}

bool mediaName(const std::string& name, bool partial = false) {
  static const std::regex finished("Download-[0-9]{13,}-[A-Za-z0-9]{6}\\.mp4");
  static const std::regex pending("\\.download-[0-9]{13,}-[A-Za-z0-9]{6}");
  return name.size() <= 64 && std::regex_match(name, partial ? pending : finished);
}

void checked(int result) {
  if (result < 0) throw std::runtime_error("Media download failed");
}

struct Input {
  AVFormatContext* context = avformat_alloc_context();
  ~Input() {avformat_close_input(&context);}
};

struct Output {
  AVFormatContext* context = nullptr;
  ~Output() {
    if (!context) return;
    if (context->pb) {
      av_freep(&context->pb->buffer);
      avio_context_free(&context->pb);
    }
    avformat_free_context(context);
  }
};

class DownloadFile {
public:
  explicit DownloadFile(const fs::path& directory) {
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    const auto stamp = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    temporary = (directory / (".download-" + std::to_string(stamp) + "-XXXXXX")).string();
    const int fd = mkstemp(temporary.data());
    if (fd < 0) throw std::runtime_error("Could not open download file");
    stream = fdopen(fd, "wb");
    if (!stream) {
      close(fd);
      unlink(temporary.c_str());
      throw std::runtime_error("Could not open download stream");
    }
  }

  ~DownloadFile() {
    if (stream) fclose(stream);
    unlink(temporary.c_str());
  }

  static int write(void* opaque, const uint8_t* bytes, int size) {
    auto& file = *static_cast<DownloadFile*>(opaque);
    if (file.offset + size > maxFileSize) return AVERROR(ENOSPC);
    if (file.offset + size >= file.nextSpaceCheck) {
      const auto nextCheck = file.offset + size + 4 * 1024 * 1024;
      std::error_code error;
      if (fs::space(file.temporary, error).available < minFreeSpace || error) return AVERROR(ENOSPC);
      file.nextSpaceCheck = nextCheck;
    }
    if (fwrite(bytes, 1, size, file.stream) != static_cast<size_t>(size)) return AVERROR(EIO);
    file.offset += size;
    file.extent = std::max(file.extent, file.offset);
    return size;
  }

  static int64_t seek(void* opaque, int64_t offset, int whence) {
    auto& file = *static_cast<DownloadFile*>(opaque);
    if (whence == AVSEEK_SIZE) return file.extent;
    if (whence != SEEK_SET || offset < 0 || offset > file.extent) return AVERROR(EINVAL);
    if (fseeko(file.stream, offset, SEEK_SET) != 0) return AVERROR(errno);
    file.offset = offset;
    return offset;
  }

  void flush() {
    if (fflush(stream) || fsync(fileno(stream))) throw std::runtime_error("Could not flush download");
    const auto result = fclose(stream);
    stream = nullptr;
    if (result) throw std::runtime_error("Could not close download");
  }

  void publish(const fs::path& directory) {
    const auto suffix = fs::path(temporary).filename().string().substr(sizeof(".download-") - 1);
    fs::rename(temporary, directory / ("Download-" + suffix + ".mp4"));
  }

private:
  std::string temporary;
  FILE* stream = nullptr;
  int64_t offset = 0;
  int64_t extent = 0;
  int64_t nextSpaceCheck = 0;
};
}

struct MediaDownload::Impl {
  Impl(std::string folder, std::string certificates)
      : directory(std::move(folder)), caFile(std::move(certificates)) {}
  fs::path directory;
  std::string caFile;
  mutable std::mutex mutex;
  std::thread worker;
  Status state{"idle", -1};
  std::atomic_bool cancelled{false};
  std::atomic_int percent{-1};
  Clock::time_point deadline;
  bool secure = false;

  static int interrupted(void* opaque) {
    const auto& job = *static_cast<Impl*>(opaque);
    return job.cancelled || Clock::now() > job.deadline;
  }

  static int openInput(AVFormatContext* context, AVIOContext** io, const char* url, int flags, AVDictionary** options) {
    const auto& job = *static_cast<Impl*>(context->opaque);
    std::string_view location(url);
    if (location.starts_with("crypto+")) location.remove_prefix(7);
    if (!webUrl(location) || flags != AVIO_FLAG_READ) return AVERROR(EINVAL);
    if (job.secure && !location.starts_with("https://")) return AVERROR(EACCES);
    av_dict_set(options, "protocol_whitelist", job.secure ? "https,tcp,tls,crypto" : "http,https,tcp,tls,crypto", 0);
    av_dict_set(options, "tls_verify", "1", 0);
    av_dict_set(options, "ca_file", job.caFile.c_str(), 0);
    av_dict_set(options, "rw_timeout", "10000000", 0);
    return avio_open2(io, url, flags, &context->interrupt_callback, options);
  }

  static int track(AVFormatContext* input, AVFormatContext* output, int index) {
    if (index < 0) return -1;
    const auto source = input->streams[index];
    if (avformat_query_codec(output->oformat, source->codecpar->codec_id, FF_COMPLIANCE_NORMAL) <= 0)
      throw std::runtime_error("Media track requires conversion");
    auto destination = avformat_new_stream(output, nullptr);
    if (!destination) throw std::bad_alloc();
    checked(avcodec_parameters_copy(destination->codecpar, source->codecpar));
    destination->codecpar->codec_tag = 0;
    destination->time_base = source->time_base;
    destination->avg_frame_rate = source->avg_frame_rate;
    destination->disposition = source->disposition;
    av_dict_copy(&destination->metadata, source->metadata, 0);
    return destination->index;
  }

  void remux(const std::string& url, DownloadFile& file) {
    Input input;
    if (!input.context) throw std::bad_alloc();
    input.context->interrupt_callback = {interrupted, this};
    input.context->opaque = this;
    input.context->io_open = openInput;
    AVDictionary* options = nullptr;
    // HLS inherits the whitelist when it opens its transport stream or AAC segments.
    av_dict_set(&options, "format_whitelist", "mov,hls,dash,mpegts,aac", 0);
    av_dict_set(&options, "max_reload", "1", 0);
    av_dict_set(&options, "max_streams", "64", 0);
    av_dict_set(&options, "enable_drefs", "0", 0);
    av_dict_set(&options, "probesize", "4194304", 0);
    const int opened = avformat_open_input(&input.context, url.c_str(), nullptr, &options);
    av_dict_free(&options);
    checked(opened);
    checked(avformat_find_stream_info(input.context, nullptr));
    const int64_t duration = input.context->duration;
    if (duration <= 0) throw std::runtime_error("Download requires on-demand video");

    const int video = av_find_best_stream(input.context, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    checked(video);
    const int audio = av_find_best_stream(input.context, AVMEDIA_TYPE_AUDIO, -1, video, nullptr, 0);
    for (unsigned i = 0; i < input.context->nb_streams; ++i)
      if (static_cast<int>(i) != video && static_cast<int>(i) != audio) input.context->streams[i]->discard = AVDISCARD_ALL;

    Output output;
    checked(avformat_alloc_output_context2(&output.context, nullptr, "mp4", nullptr));
    const int videoOut = track(input.context, output.context, video);
    const int audioOut = track(input.context, output.context, audio);
    auto buffer = static_cast<uint8_t*>(av_malloc(bufferSize));
    if (!buffer) throw std::bad_alloc();
    output.context->pb = avio_alloc_context(buffer, bufferSize, 1, &file, nullptr, DownloadFile::write, DownloadFile::seek);
    if (!output.context->pb) {av_free(buffer); throw std::bad_alloc();}
    output.context->flags |= AVFMT_FLAG_CUSTOM_IO;
    checked(avformat_write_header(output.context, nullptr));

    using Packet = std::unique_ptr<AVPacket, void(*)(AVPacket*)>;
    Packet packet(av_packet_alloc(), [](AVPacket* value) {av_packet_free(&value);});
    if (!packet) throw std::bad_alloc();
    bool wroteVideo = false;
    double progress = 0;
    const auto start = input.context->start_time == AV_NOPTS_VALUE ? 0 : input.context->start_time;
    percent = 0;
    for (;;) {
      if (interrupted(this)) throw std::runtime_error("Download interrupted");
      const auto read = av_read_frame(input.context, packet.get());
      if (read == AVERROR_EOF) break;
      checked(read);
      const bool isVideo = packet->stream_index == video;
      const int destination = isVideo ? videoOut : packet->stream_index == audio ? audioOut : -1;
      if (destination >= 0) {
        if (packet->flags & AV_PKT_FLAG_CORRUPT) throw std::runtime_error("Incomplete source");
        const auto from = input.context->streams[packet->stream_index]->time_base;
        if (packet->pts != AV_NOPTS_VALUE) {
          const auto point = av_rescale_q_rnd(packet->pts, from, AV_TIME_BASE_Q, AV_ROUND_ZERO);
          // Use floating point for the progress display to avoid timestamp subtraction overflow.
          const auto elapsed = static_cast<double>(point) - static_cast<double>(start);
          progress = std::max(progress, std::clamp(elapsed, 0.0, static_cast<double>(duration)));
          percent = static_cast<int>(std::min(99.0, 100.0 * progress / duration));
        }
        packet->stream_index = destination;
        av_packet_rescale_ts(packet.get(), from, output.context->streams[destination]->time_base);
        packet->pos = -1;
        checked(av_interleaved_write_frame(output.context, packet.get()));
        wroteVideo = wroteVideo || isVideo;
      }
      av_packet_unref(packet.get());
    }
    if (!wroteVideo || (input.context->pb && input.context->pb->error < 0) ||
        duration - progress > std::max<int64_t>(500000, duration / 30))
      throw std::runtime_error("Incomplete source");
    checked(av_write_trailer(output.context));
    avio_flush(output.context->pb);
    checked(output.context->pb->error);
  }

  void run(const std::string& url) noexcept {
    try {
      DownloadFile file(directory);
      remux(url, file);
      file.flush();
      std::lock_guard lock(mutex);
      if (interrupted(this)) throw std::runtime_error("Download interrupted");
      file.publish(directory);
      state = {"saved", 100};
    } catch (...) {
      std::lock_guard lock(mutex);
      state = {cancelled ? "cancelled" : "error", -1};
    }
  }
};

MediaDownload::MediaDownload(std::string directory, std::string caFile)
    : impl(std::make_unique<Impl>(std::move(directory), std::move(caFile))) {}

MediaDownload::~MediaDownload() {
  cancel();
  if (impl->worker.joinable()) impl->worker.join();
}

void MediaDownload::start(const std::string& url) {
  if (!webUrl(url)) throw std::invalid_argument("Invalid download URL");
  std::lock_guard lock(impl->mutex);
  if (impl->state.phase == "downloading" || impl->state.phase == "cancelling")
    throw std::runtime_error("A download is already active");
  if (impl->worker.joinable()) impl->worker.join();
  if (!fs::exists(impl->directory) && mkdir(impl->directory.c_str(), 0700))
    throw std::runtime_error("Could not open downloads library");
  if (!fs::is_directory(fs::symlink_status(impl->directory)) || fs::space(impl->directory).available < minFreeSpace)
    throw std::runtime_error("Insufficient download space");
  for (const auto& entry : fs::directory_iterator(impl->directory)) {
    if (mediaName(entry.path().filename().string(), true) && fs::is_regular_file(entry.symlink_status()))
      fs::remove(entry);
  }
  impl->secure = url.starts_with("https://");
  impl->cancelled = false;
  impl->deadline = Clock::now() + 30min;
  impl->percent = -1;
  impl->state = {"downloading", -1};
  try {impl->worker = std::thread([job = impl.get(), url] {job->run(url);});}
  catch (...) {impl->state = {"error", -1}; throw;}
}

void MediaDownload::cancel() {
  std::lock_guard lock(impl->mutex);
  if (impl->state.phase == "downloading") {
    impl->cancelled = true;
    impl->state = {"cancelling", -1};
  }
}

MediaDownload::Status MediaDownload::status() const {
  std::lock_guard lock(impl->mutex);
  auto result = impl->state;
  if (result.phase == "downloading") result.percent = impl->percent;
  return result;
}

std::vector<MediaDownload::Entry> MediaDownload::list() const {
  std::vector<Entry> entries;
  if (!fs::is_directory(fs::symlink_status(impl->directory))) return entries;
  for (const auto& entry : fs::directory_iterator(impl->directory)) {
    const auto name = entry.path().filename().string();
    if (mediaName(name) && fs::is_regular_file(entry.symlink_status())) {
      std::error_code error;
      const auto bytes = entry.file_size(error);
      if (!error && bytes) entries.push_back({name, bytes});
    }
  }
  std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {return a.name > b.name;});
  return entries;
}

std::string MediaDownload::path(const std::string& name) const {
  if (!mediaName(name) || !fs::is_directory(fs::symlink_status(impl->directory)))
    throw std::invalid_argument("Invalid download name");
  const auto entry = impl->directory / name;
  if (!fs::is_regular_file(fs::symlink_status(entry))) throw std::runtime_error("Download not found");
  return entry.string();
}

void MediaDownload::remove(const std::string& name) {
  if (!fs::remove(path(name))) throw std::runtime_error("Download could not be deleted");
}
