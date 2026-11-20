// Included by audio_engine.cpp in coverage builds to exercise the same decoder instantiations.
#include <array>
#include <chrono>
#include <jni.h>

namespace {
enum class DecoderAllocation { Context, ExtraData, Packet, Frame, None };

struct DecoderAllocationFault {
    const DecoderAllocation target;
    DecoderAllocation next = DecoderAllocation::Context;
    bool rejected = false;
    AVCodecContext *context = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;

    bool reject(DecoderAllocation kind) {
        if (kind != target) return false;
        rejected = true;
        return true;
    }
};

thread_local DecoderAllocationFault *activeDecoderFault = nullptr;

struct ScopedDecoderAllocationFault {
    DecoderAllocationFault fault;
    DecoderAllocationFault *previous = activeDecoderFault;
    explicit ScopedDecoderAllocationFault(DecoderAllocation target) : fault{target} {
        activeDecoderFault = &fault;
    }
    ~ScopedDecoderAllocationFault() { activeDecoderFault = previous; }
};

DecoderAllocationFault *decoderAllocation(DecoderAllocation kind) {
    auto *fault = activeDecoderFault;
    if (!fault || fault->next != kind) return nullptr;
    // FFmpeg allocates its own buffers. Leave calls it makes internally alone.
    fault->next = DecoderAllocation::None;
    return fault;
}

enum class DecodedFrame { Empty, Mono, WideSamples };
struct TestDecodedFrame {
    const DecodedFrame kind;
    bool delivered = false;
};
thread_local TestDecodedFrame *activeDecodedFrame = nullptr;

enum class PlatformCodecStage { Configure, Start };
struct PlatformCodecFault {
    const PlatformCodecStage stage;
    AMediaCodec *rejected = nullptr;
    int failures = 0;
    int released = 0;

    media_status_t reject(AMediaCodec *codec) {
        rejected = codec;
        ++failures;
        return AMEDIA_ERROR_UNKNOWN;
    }
};
thread_local PlatformCodecFault *activePlatformFault = nullptr;

struct ScopedPlatformCodecFault {
    PlatformCodecFault fault;
    PlatformCodecFault *previous = activePlatformFault;
    explicit ScopedPlatformCodecFault(PlatformCodecStage stage) : fault{stage} { activePlatformFault = &fault; }
    ~ScopedPlatformCodecFault() { activePlatformFault = previous; }
};

struct PreferredCodecContract {
    size_t namedRequests = 0;
    size_t defaultRequests = 0;
    bool deleted = false;
    AMediaCodec *codec = nullptr;
};
thread_local PreferredCodecContract *activePreferredCodec = nullptr;

struct ScopedPreferredCodecContract {
    PreferredCodecContract codec;
    ScopedPreferredCodecContract() { activePreferredCodec = &codec; }
    ~ScopedPreferredCodecContract() { activePreferredCodec = nullptr; }
};

struct FfmpegLogContract {
    std::vector<std::pair<int, std::string>> entries;
};
thread_local FfmpegLogContract *activeFfmpegLog = nullptr;

struct ScopedFfmpegLogContract {
    FfmpegLogContract log;
    ScopedFfmpegLogContract() { activeFfmpegLog = &log; }
    ~ScopedFfmpegLogContract() { activeFfmpegLog = nullptr; }
};

// The NDK's input offset type can differ from off_t on 32-bit Android.
template <typename Offset>
Offset platformInputOffset(media_status_t (*)(AMediaCodec *, size_t, Offset, size_t, uint64_t, uint32_t));
using PlatformInputOffset = decltype(platformInputOffset(&AMediaCodec_queueInputBuffer));

enum class PlatformInputCase { Missing, Limited, QueueRejected };
struct PlatformInputContract {
    AMediaCodec *codec;
    PlatformInputCase mode;
    std::array<uint8_t, 8> buffer{};
    size_t requested = 0;
    size_t queued = 0;
    size_t requestedIndex = 0;
    size_t queuedIndex = 0;
    PlatformInputOffset offset = 0;
    uint64_t time = 0;
    uint32_t flags = 0;
    size_t length = 0;
};
thread_local PlatformInputContract *activePlatformInput = nullptr;

struct ScopedPlatformInputContract {
    PlatformInputContract contract;
    PlatformInputContract *previous = activePlatformInput;
    ScopedPlatformInputContract(AMediaCodec *codec, PlatformInputCase mode) : contract{codec, mode} {
        activePlatformInput = &contract;
    }
    ~ScopedPlatformInputContract() { activePlatformInput = previous; }
};

struct PlatformOutputContract {
    AMediaCodec *codec;
    std::array<int16_t, 6> buffer{99, 98, 1, -1, 2, -2};
    size_t next = 0;
    std::array<size_t, 3> releases{};
    std::atomic<size_t> released{0};
    bool requestedRender = false;
};
std::mutex platformOutputMutex;
PlatformOutputContract *activePlatformOutput = nullptr;

struct ScopedPlatformOutputContract {
    PlatformOutputContract output;
    explicit ScopedPlatformOutputContract(AMediaCodec *codec) : output{codec} {
        std::lock_guard<std::mutex> lock(platformOutputMutex);
        activePlatformOutput = &output;
    }
    ~ScopedPlatformOutputContract() {
        std::lock_guard<std::mutex> lock(platformOutputMutex);
        activePlatformOutput = nullptr;
    }
};
}

extern "C" media_status_t __real_AMediaCodec_configure(
    AMediaCodec *, const AMediaFormat *, ANativeWindow *, AMediaCrypto *, uint32_t);
extern "C" media_status_t __real_AMediaCodec_start(AMediaCodec *);
extern "C" media_status_t __real_AMediaCodec_delete(AMediaCodec *);
extern "C" AMediaCodec *__real_AMediaCodec_createCodecByName(const char *);
extern "C" AMediaCodec *__real_AMediaCodec_createDecoderByType(const char *);
extern "C" int __real___android_log_vprint(int, const char *, const char *, va_list);
extern "C" uint8_t *__real_AMediaCodec_getInputBuffer(AMediaCodec *, size_t, size_t *);
extern "C" media_status_t __real_AMediaCodec_queueInputBuffer(
    AMediaCodec *, size_t, PlatformInputOffset, size_t, uint64_t, uint32_t);
extern "C" ssize_t __real_AMediaCodec_dequeueOutputBuffer(AMediaCodec *, AMediaCodecBufferInfo *, int64_t);
extern "C" uint8_t *__real_AMediaCodec_getOutputBuffer(AMediaCodec *, size_t, size_t *);
extern "C" media_status_t __real_AMediaCodec_releaseOutputBuffer(AMediaCodec *, size_t, bool);

extern "C" media_status_t __wrap_AMediaCodec_configure(
    AMediaCodec *codec, const AMediaFormat *format, ANativeWindow *surface, AMediaCrypto *crypto, uint32_t flags) {
    auto *fault = activePlatformFault;
    if (fault && fault->stage == PlatformCodecStage::Configure) return fault->reject(codec);
    return __real_AMediaCodec_configure(codec, format, surface, crypto, flags);
}

extern "C" media_status_t __wrap_AMediaCodec_start(AMediaCodec *codec) {
    auto *fault = activePlatformFault;
    if (fault && fault->stage == PlatformCodecStage::Start) return fault->reject(codec);
    return __real_AMediaCodec_start(codec);
}

extern "C" media_status_t __wrap_AMediaCodec_delete(AMediaCodec *codec) {
    auto *fault = activePlatformFault;
    auto *preferred = activePreferredCodec;
    const bool rejected = fault && fault->rejected == codec;
    const bool selected = preferred && preferred->codec == codec;
    const auto result = __real_AMediaCodec_delete(codec);
    if (rejected && result == AMEDIA_OK) {
        fault->rejected = nullptr;
        ++fault->released;
    }
    if (selected && result == AMEDIA_OK) {
        preferred->codec = nullptr;
        preferred->deleted = true;
    }
    return result;
}

extern "C" AMediaCodec *__wrap_AMediaCodec_createCodecByName(const char *name) {
    auto *fixture = activePreferredCodec;
    if (!fixture || strcmp(name, AAC_INPROC_DECODER) != 0) return __real_AMediaCodec_createCodecByName(name);
    // Most emulators lack the named codec. Supply a real AAC decoder to the
    // named attempt so configuration and startup still use the platform.
    ++fixture->namedRequests;
    fixture->codec = __real_AMediaCodec_createDecoderByType("audio/mp4a-latm");
    return fixture->codec;
}

extern "C" AMediaCodec *__wrap_AMediaCodec_createDecoderByType(const char *mime) {
    auto *fixture = activePreferredCodec;
    if (!fixture || strcmp(mime, "audio/mp4a-latm") != 0) return __real_AMediaCodec_createDecoderByType(mime);
    ++fixture->defaultRequests;
    fixture->codec = __real_AMediaCodec_createDecoderByType(mime);
    return fixture->codec;
}

extern "C" int __wrap___android_log_vprint(int priority, const char *tag, const char *format, va_list args) {
    auto *fixture = activeFfmpegLog;
    if (!fixture || !tag || strcmp(tag, "ffmpeg") != 0) return __real___android_log_vprint(priority, tag, format, args);
    std::array<char, 128> buffer{};
    const int length = vsnprintf(buffer.data(), buffer.size(), format, args);
    fixture->entries.emplace_back(priority, buffer.data());
    return length;
}

extern "C" uint8_t *__wrap_AMediaCodec_getInputBuffer(AMediaCodec *codec, size_t index, size_t *capacity) {
    auto *contract = activePlatformInput;
    if (!contract || contract->codec != codec) return __real_AMediaCodec_getInputBuffer(codec, index, capacity);
    ++contract->requested;
    contract->requestedIndex = index;
    if (contract->mode == PlatformInputCase::Missing) return nullptr;
    *capacity = contract->mode == PlatformInputCase::Limited ? 4 : contract->buffer.size();
    return contract->buffer.data();
}

extern "C" media_status_t __wrap_AMediaCodec_queueInputBuffer(
    AMediaCodec *codec, size_t index, PlatformInputOffset offset, size_t size, uint64_t time, uint32_t flags) {
    auto *contract = activePlatformInput;
    if (!contract || contract->codec != codec) {
        return __real_AMediaCodec_queueInputBuffer(codec, index, offset, size, time, flags);
    }
    // Each case uses one input slot in a fresh codec and releases it on teardown.
    // Inspect the request without handing synthetic test bytes to the decoder.
    ++contract->queued;
    contract->queuedIndex = index;
    contract->offset = offset;
    contract->length = size;
    contract->time = time;
    contract->flags = flags;
    return contract->mode == PlatformInputCase::QueueRejected ? AMEDIA_ERROR_UNKNOWN : AMEDIA_OK;
}

extern "C" ssize_t __wrap_AMediaCodec_dequeueOutputBuffer(
    AMediaCodec *codec, AMediaCodecBufferInfo *info, int64_t timeoutUs) {
    {
        std::lock_guard<std::mutex> lock(platformOutputMutex);
        auto *output = activePlatformOutput;
        if (output && output->codec == codec && output->next < output->releases.size()) {
            const size_t index = output->next++;
            // Missing buffer, zero-size buffer, then valid PCM with a nonzero offset.
            *info = AMediaCodecBufferInfo{4, index == 1 ? 0 : 8, 100'000 + static_cast<int64_t>(index) * 20'000, 0};
            return static_cast<ssize_t>(index);
        }
    }
    return __real_AMediaCodec_dequeueOutputBuffer(codec, info, timeoutUs);
}

extern "C" uint8_t *__wrap_AMediaCodec_getOutputBuffer(AMediaCodec *codec, size_t index, size_t *capacity) {
    {
        std::lock_guard<std::mutex> lock(platformOutputMutex);
        auto *output = activePlatformOutput;
        if (output && output->codec == codec) {
            *capacity = sizeof(output->buffer);
            return index == 0 ? nullptr : reinterpret_cast<uint8_t *>(output->buffer.data());
        }
    }
    return __real_AMediaCodec_getOutputBuffer(codec, index, capacity);
}

extern "C" media_status_t __wrap_AMediaCodec_releaseOutputBuffer(AMediaCodec *codec, size_t index, bool render) {
    {
        std::lock_guard<std::mutex> lock(platformOutputMutex);
        auto *output = activePlatformOutput;
        if (output && output->codec == codec && index < output->releases.size()) {
            ++output->releases[index];
            output->requestedRender |= render;
            output->released.fetch_add(1, std::memory_order_release);
            return AMEDIA_OK;
        }
    }
    return __real_AMediaCodec_releaseOutputBuffer(codec, index, render);
}

extern "C" AVCodecContext *__real_avcodec_alloc_context3(const AVCodec *);
extern "C" void *__real_av_mallocz(size_t);
extern "C" int __real_avcodec_open2(AVCodecContext *, const AVCodec *, AVDictionary **);
extern "C" AVPacket *__real_av_packet_alloc();
extern "C" AVFrame *__real_av_frame_alloc();
extern "C" void __real_avcodec_free_context(AVCodecContext **);
extern "C" void __real_av_packet_free(AVPacket **);
extern "C" void __real_av_frame_free(AVFrame **);
extern "C" int __real_avcodec_receive_frame(AVCodecContext *, AVFrame *);

extern "C" AVCodecContext *__wrap_avcodec_alloc_context3(const AVCodec *codec) {
    auto *fault = decoderAllocation(DecoderAllocation::Context);
    if (fault && fault->reject(DecoderAllocation::Context)) return nullptr;
    auto *context = __real_avcodec_alloc_context3(codec);
    if (fault) {
        fault->context = context;
        fault->next = DecoderAllocation::ExtraData;
    }
    return context;
}

extern "C" void *__wrap_av_mallocz(size_t bytes) {
    auto *fault = decoderAllocation(DecoderAllocation::ExtraData);
    return fault && fault->reject(DecoderAllocation::ExtraData) ? nullptr : __real_av_mallocz(bytes);
}

extern "C" int __wrap_avcodec_open2(AVCodecContext *context, const AVCodec *codec, AVDictionary **options) {
    const int result = __real_avcodec_open2(context, codec, options);
    if (activeDecoderFault && activeDecoderFault->context == context && result >= 0) {
        activeDecoderFault->next = DecoderAllocation::Packet;
    }
    return result;
}

extern "C" AVPacket *__wrap_av_packet_alloc() {
    auto *fault = decoderAllocation(DecoderAllocation::Packet);
    auto *packet = fault && fault->reject(DecoderAllocation::Packet) ? nullptr : __real_av_packet_alloc();
    if (fault) {
        fault->packet = packet;
        fault->next = DecoderAllocation::Frame;
    }
    return packet;
}

extern "C" AVFrame *__wrap_av_frame_alloc() {
    auto *fault = decoderAllocation(DecoderAllocation::Frame);
    auto *frame = fault && fault->reject(DecoderAllocation::Frame) ? nullptr : __real_av_frame_alloc();
    if (fault) fault->frame = frame;
    return frame;
}

extern "C" void __wrap_avcodec_free_context(AVCodecContext **context) {
    auto *fault = activeDecoderFault;
    if (fault && context && *context == fault->context) fault->context = nullptr;
    __real_avcodec_free_context(context);
}

extern "C" void __wrap_av_packet_free(AVPacket **packet) {
    auto *fault = activeDecoderFault;
    if (fault && packet && *packet == fault->packet) fault->packet = nullptr;
    __real_av_packet_free(packet);
}

extern "C" void __wrap_av_frame_free(AVFrame **frame) {
    auto *fault = activeDecoderFault;
    if (fault && frame && *frame == fault->frame) fault->frame = nullptr;
    __real_av_frame_free(frame);
}

extern "C" int __wrap_avcodec_receive_frame(AVCodecContext *context, AVFrame *frame) {
    const int result = __real_avcodec_receive_frame(context, frame);
    auto *replacement = activeDecodedFrame;
    if (result != 0 || !replacement || replacement->delivered) return result;

    // Consume the actual packet with FFmpeg, then provide an empty frame or a
    // correctly allocated frame in an output layout the receiver doesn't support.
    const int samples = frame->nb_samples;
    const int rate = frame->sample_rate;
    av_frame_unref(frame);
    if (replacement->kind != DecodedFrame::Empty) {
        const auto format = replacement->kind == DecodedFrame::WideSamples ? AV_SAMPLE_FMT_S32P : AV_SAMPLE_FMT_S16P;
        const int channels = replacement->kind == DecodedFrame::Mono ? 1 : 2;
        frame->nb_samples = samples;
        frame->sample_rate = rate;
        frame->format = format;
        av_channel_layout_default(&frame->ch_layout, channels);
        const int bufferResult = av_frame_get_buffer(frame, 0);
        if (bufferResult < 0) return bufferResult;
        const int silenceResult = av_samples_set_silence(frame->extended_data, 0, samples, channels, format);
        if (silenceResult < 0) return silenceResult;
    }
    replacement->delivered = true;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeAacConfigurationContracts(JNIEnv *env, jobject) {
#define AAC_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    struct Expected {
        int type, samples, profile;
        size_t size;
        std::array<uint8_t, 4> bytes;
    };
    const Expected cases[] = {
        {CT_AAC_LC, 1024, 2, 2, {0x12, 0x10}},
        {CT_AAC_LC, 960, 2, 2, {0x12, 0x14}},
        {CT_AAC_ELD, 512, 39, 4, {0xF8, 0xE8, 0x40, 0x00}},
        {CT_AAC_ELD, 480, 39, 4, {0xF8, 0xE8, 0x50, 0x00}},
    };
    for (const auto &expected : cases) {
        const auto config = codecConfig(expected.type, expected.samples, 44100, 2);
        AAC_CONTRACT(config && config->csdLen == expected.size);
        AAC_CONTRACT(config->aacProfile == expected.profile && config->ffmpegId == AV_CODEC_ID_AAC);
        AAC_CONTRACT(config->sampleRate == 44100 && config->channels == 2);
        AAC_CONTRACT(strcmp(config->mime, "audio/mp4a-latm") == 0);
        AAC_CONTRACT(memcmp(config->csd, expected.bytes.data(), expected.size) == 0);
    }
    AAC_CONTRACT(!codecConfig(-1, 1024, 44100, 2));
#undef AAC_CONTRACT
    return env->NewStringUTF("AAC configuration contracts passed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativePlatformDecoderFallbackContracts(JNIEnv *env, jobject) {
#define FALLBACK_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    LogSink log;
    LatencyReporter latency("AAC fallback contract", log);
    TimelineBuffer timeline(44100, 2, 0, 95);
    for (const auto stage : {PlatformCodecStage::Configure, PlatformCodecStage::Start}) {
        ScopedPlatformCodecFault platform(stage);
        ScopedDecoderAllocationFault software(DecoderAllocation::None);
        auto decoder = makeDecoder(CT_AAC_LC, 1024, 44100, 2, timeline, latency, log, false, false, false);
        FALLBACK_CONTRACT(platform.fault.failures > 0 && platform.fault.failures == platform.fault.released);
        FALLBACK_CONTRACT(decoder && software.fault.context && software.fault.frame && software.fault.packet);
        decoder.reset();
        FALLBACK_CONTRACT(!software.fault.context && !software.fault.frame && !software.fault.packet);
    }
    {
        ScopedPlatformCodecFault platform(PlatformCodecStage::Configure);
        ScopedDecoderAllocationFault software(DecoderAllocation::Context);
        FALLBACK_CONTRACT(!makeDecoder(CT_AAC_LC, 1024, 44100, 2, timeline, latency, log, false, false, false));
        FALLBACK_CONTRACT(platform.fault.failures > 0 && platform.fault.failures == platform.fault.released);
        FALLBACK_CONTRACT(software.fault.rejected);
    }
    auto decoder = makeDecoder(CT_AAC_LC, 1024, 44100, 2, timeline, latency, log, false, false, false);
    FALLBACK_CONTRACT(decoder);
#undef FALLBACK_CONTRACT
    return env->NewStringUTF("platform decoder fallback contracts passed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativePreferredDecoderContracts(JNIEnv *env, jobject) {
#define PREFERRED_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    LogSink log;
    LatencyReporter latency("AAC preferred contract", log);
    TimelineBuffer timeline(44100, 2, 0, 95);
    ScopedPreferredCodecContract fixture;
    auto decoder = makeDecoder(CT_AAC_LC, 1024, 44100, 2, timeline, latency, log, false, false, false);
    PREFERRED_CONTRACT(decoder && fixture.codec.codec && !fixture.codec.deleted);
    if (android_get_device_api_level() >= 23) {
        PREFERRED_CONTRACT(fixture.codec.namedRequests == 1 && fixture.codec.defaultRequests == 0);
    } else {
        PREFERRED_CONTRACT(fixture.codec.namedRequests == 0 && fixture.codec.defaultRequests == 1);
    }
    decoder.reset();
    PREFERRED_CONTRACT(fixture.codec.deleted && !fixture.codec.codec);
#undef PREFERRED_CONTRACT
    return env->NewStringUTF("preferred decoder contracts passed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeFfmpegLogContracts(JNIEnv *env, jobject) {
    installFfmpegLogcat();
    ScopedFfmpegLogContract fixture;
    av_log(nullptr, AV_LOG_INFO, "filtered %d", 1);
    av_log(nullptr, AV_LOG_WARNING, "warning %d", 2);
    av_log(nullptr, AV_LOG_ERROR, "error %d", 3);
    const std::vector<std::pair<int, std::string>> expected{
        {ANDROID_LOG_WARN, "warning 2"},
        {ANDROID_LOG_ERROR, "error 3"},
    };
    return env->NewStringUTF(fixture.log.entries == expected
        ? "FFmpeg log contracts passed" : "FFmpeg priority, message, or filtering differed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativePlatformInputBufferContracts(JNIEnv *env, jobject) {
#define INPUT_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    LogSink log;
    LatencyReporter latency("AAC input contract", log);
    TimelineBuffer timeline(44100, 2, 0, 95);
    const auto config = codecConfig(CT_AAC_LC, 1024, 44100, 2);
    INPUT_CONTRACT(config.has_value());
    const std::array<uint8_t, 6> payload{1, 2, 3, 4, 5, 6};
    constexpr int64_t pts = 1'234'567'890;
    for (const auto mode : {PlatformInputCase::Missing, PlatformInputCase::Limited, PlatformInputCase::QueueRejected}) {
        auto *codec = startMediaCodec(*config, log, false, false);
        INPUT_CONTRACT(codec);
        MediaCodecDecoder decoder(codec, timeline, latency);
        ScopedPlatformInputContract fixture(codec, mode);
        const auto &input = fixture.contract;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
        // This contract starts at an acquired slot. A separate test checks
        // the attempt count and timeout for a codec that has not warmed up.
        do {
            INPUT_CONTRACT(!decoder.decode(payload.data(), payload.size(), pts));
        } while (input.requested == 0 && std::chrono::steady_clock::now() < deadline);
        INPUT_CONTRACT(input.requested == 1);
        if (mode == PlatformInputCase::Missing) {
            INPUT_CONTRACT(input.queued == 0);
        } else {
            const size_t expected = mode == PlatformInputCase::Limited ? 4 : payload.size();
            INPUT_CONTRACT(input.queued == 1 && input.flags == 0 && input.time == pts / 1000);
            INPUT_CONTRACT(input.queuedIndex == input.requestedIndex && input.offset == 0);
            INPUT_CONTRACT(input.length == expected && memcmp(input.buffer.data(), payload.data(), expected) == 0);
            INPUT_CONTRACT(input.buffer[expected] == 0);
        }
    }
#undef INPUT_CONTRACT
    return env->NewStringUTF("platform input buffer contracts passed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativePlatformOutputBufferContracts(JNIEnv *env, jobject) {
#define DRAIN_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    static std::mutex fixtureMutex;
    std::lock_guard<std::mutex> runFixture(fixtureMutex);
    LogSink log;
    LatencyReporter latency("AAC drain contract", log);
    TimelineBuffer timeline(44100, 2, 0, 95);
    std::vector<int64_t> timestamps;
    std::vector<int16_t> samples;
    timeline.capturePcm = [&](const int16_t *pcm, size_t count, int64_t pts) {
        timestamps.push_back(pts);
        samples.insert(samples.end(), pcm, pcm + count);
    };

    const auto config = codecConfig(CT_AAC_LC, 1024, 44100, 2);
    DRAIN_CONTRACT(config.has_value());
    auto *codec = startMediaCodec(*config, log, false, false);
    DRAIN_CONTRACT(codec);
    ScopedPlatformOutputContract fixture(codec);
    {
        MediaCodecDecoder decoder(codec, timeline, latency);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (fixture.output.released.load(std::memory_order_acquire) < fixture.output.releases.size() &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    } // Join the drain thread before examining PCM and released slot counts.
    const std::array<size_t, 3> expectedReleases{1, 1, 1};
    const std::vector<int64_t> expectedTimestamps{140'000'000};
    const std::vector<int16_t> expectedSamples{1, -1, 2, -2};
    DRAIN_CONTRACT(fixture.output.releases == expectedReleases && !fixture.output.requestedRender);
    DRAIN_CONTRACT(timestamps == expectedTimestamps && samples == expectedSamples);
    DRAIN_CONTRACT(latency.debugInfo().held == 0);
#undef DRAIN_CONTRACT
    return env->NewStringUTF("platform output buffer contracts passed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeDecoderLatencyContracts(JNIEnv *env, jobject) {
#define LATENCY_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    LatencyProbe probe;
    LATENCY_CONTRACT(probe.match(100, 200'000) == -1 && probe.inFlight() == 0);
    probe.record(100, 110'000);
    probe.record(200, 210'000);
    LATENCY_CONTRACT(probe.match(150, 220'000) == -1 && probe.inFlight() == 2);
    LATENCY_CONTRACT(probe.match(200, 290'000) == 80'000 && probe.inFlight() == 0);
    LATENCY_CONTRACT(probe.match(100, 300'000) == -1);

    LogSink log;
    LatencyReporter latency("decode latency contract", log);
    latency.setEnableLogging(false);
    latency.setHeld(3);
    for (int sample = 0; sample < 200; ++sample) latency.record((sample % 2 == 0) ? 1000 : 3000);
    const auto measured = latency.debugInfo();
    LATENCY_CONTRACT(measured.meanUs == 2 && measured.maxUs == 3 && measured.held == 3);
#undef LATENCY_CONTRACT
    return env->NewStringUTF("decoder latency contracts passed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeFfmpegFailureContracts(JNIEnv *env, jobject, jbyteArray input) {
#define FFMPEG_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    const auto size = input ? env->GetArrayLength(input) : 0;
    FFMPEG_CONTRACT(size > 0 && size <= 8192);
    std::vector<uint8_t> packet(size + AV_INPUT_BUFFER_PADDING_SIZE, 0);
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(packet.data()));
    if (env->ExceptionCheck()) return nullptr;

    LogSink log;
    LatencyReporter latency("ffmpeg contract", log);
    TimelineBuffer timeline(44100, 2, 0, 95);
    const auto config = codecConfig(CT_ALAC, 352, 44100, 2);
    FFMPEG_CONTRACT(config);
    for (const auto failure : {DecoderAllocation::Context, DecoderAllocation::ExtraData,
                               DecoderAllocation::Packet, DecoderAllocation::Frame}) {
        ScopedDecoderAllocationFault fixture(failure);
        FFMPEG_CONTRACT(!FfmpegDecoder::make(*config, timeline, latency, log));
        FFMPEG_CONTRACT(fixture.fault.rejected);
        FFMPEG_CONTRACT(!fixture.fault.context && !fixture.fault.packet && !fixture.fault.frame);
    }

    auto invalid = *config;
    invalid.ffmpegId = AV_CODEC_ID_NONE;
    FFMPEG_CONTRACT(!FfmpegDecoder::make(invalid, timeline, latency, log));
    invalid.ffmpegId = AV_CODEC_ID_ALAC;
    invalid.csdLen = 0;
    FFMPEG_CONTRACT(!FfmpegDecoder::make(invalid, timeline, latency, log));

    size_t captured = 0;
    int64_t capturedPts = 0;
    timeline.capturePcm = [&](const int16_t *, size_t samples, int64_t pts) {
        captured += samples;
        capturedPts = pts;
    };
    auto decoder = FfmpegDecoder::make(*config, timeline, latency, log);
    FFMPEG_CONTRACT(decoder);
    const auto pts = monoNs();
    FFMPEG_CONTRACT(!decoder->decode(packet.data(), 0, pts));
    FFMPEG_CONTRACT(!decoder->decode(packet.data(), INT_MAX, pts));
    std::array<uint8_t, 1 + AV_INPUT_BUFFER_PADDING_SIZE> malformed{};
    FFMPEG_CONTRACT(!decoder->decode(malformed.data(), 1, pts));
    FFMPEG_CONTRACT(captured == 0);
    FFMPEG_CONTRACT(decoder->decode(packet.data(), size, pts));
    FFMPEG_CONTRACT(captured == 352 * 2 && capturedPts == pts);

    int64_t nextPts = pts;
    for (const auto kind : {DecodedFrame::Empty, DecodedFrame::Mono, DecodedFrame::WideSamples}) {
        nextPts += 20'000'000;
        const auto before = captured;
        TestDecodedFrame replacement{kind};
        activeDecodedFrame = &replacement;
        const bool accepted = decoder->decode(packet.data(), size, nextPts);
        activeDecodedFrame = nullptr;
        FFMPEG_CONTRACT(replacement.delivered);
        FFMPEG_CONTRACT(accepted == (kind == DecodedFrame::Empty));
        FFMPEG_CONTRACT(captured == before);

        nextPts += 20'000'000;
        FFMPEG_CONTRACT(decoder->decode(packet.data(), size, nextPts));
        FFMPEG_CONTRACT(captured == before + 352 * 2 && capturedPts == nextPts);
    }
#undef FFMPEG_CONTRACT
    return env->NewStringUTF("ffmpeg failure contracts passed");
}
