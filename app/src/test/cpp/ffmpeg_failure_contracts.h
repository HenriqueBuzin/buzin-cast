// Included by audio_engine.cpp in coverage builds to exercise the same decoder instantiations.
#include <array>
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
}

extern "C" media_status_t __real_AMediaCodec_configure(
    AMediaCodec *, const AMediaFormat *, ANativeWindow *, AMediaCrypto *, uint32_t);
extern "C" media_status_t __real_AMediaCodec_start(AMediaCodec *);
extern "C" media_status_t __real_AMediaCodec_delete(AMediaCodec *);

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
    const bool rejected = fault && fault->rejected == codec;
    const auto result = __real_AMediaCodec_delete(codec);
    if (rejected && result == AMEDIA_OK) {
        fault->rejected = nullptr;
        ++fault->released;
    }
    return result;
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
