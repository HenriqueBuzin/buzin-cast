// Coverage-only JNI hook. Never linked in production builds.
#include <jni.h>
#include <algorithm>
#include <array>
#include <memory>
#include <cstring>
#include <map>
#include <string>
#include <thread>
#include <vector>
#include <chrono>
#include <oboe/Oboe.h>
#include "audio_engine.h"
#include "audio_output.h"
#include "log_sink.h"
extern "C" {
#include <libavcodec/defs.h>
#include "dnssd.h"
#include "android_dnssd_shim.h"
#include "android_raop_callbacks.h"
}
extern "C" void __llvm_profile_set_filename(const char *);
extern "C" int __llvm_profile_write_file(void);

// Access owns references under the production lock; no stream state is faked.
struct AudioOutputContractAccess {
    static std::shared_ptr<OboeCallbacks> callbacks(const std::shared_ptr<AudioOutput> &output) {
        std::lock_guard<std::mutex> lock(output->mLock);
        return output->mCallbacks;
    }
    static std::shared_ptr<oboe::AudioStream> stream(const std::shared_ptr<AudioOutput> &output) {
        std::lock_guard<std::mutex> lock(output->mLock);
        return output->mStream;
    }
};

// Deliberately invokes the real post-close callback; no physical disconnection
// is simulated at the OS level, and all opened emulator streams are closed.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeOutputContracts(JNIEnv *env, jobject, jint scenario) {
#define OUTPUT_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    auto timeline = std::make_shared<TimelineBuffer>(44100, 2, 50, 95);
    auto log = std::make_shared<LogSink>();
    auto output = AudioOutput::create(scenario == 2 ? -1 : 44100, 2, 192, false, timeline, log);
    auto callbacks = AudioOutputContractAccess::callbacks(output);
    OUTPUT_CONTRACT(callbacks);
    if (scenario == 0) {
        output->stop();
        callbacks->onErrorAfterClose(nullptr, oboe::Result::ErrorDisconnected);
        OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        std::weak_ptr<AudioOutput> owner = output;
        output.reset();
        OUTPUT_CONTRACT(owner.expired());
        callbacks->onErrorAfterClose(nullptr, oboe::Result::ErrorDisconnected);
        std::array<int16_t, 16> pcm;
        pcm.fill(1234);
        OUTPUT_CONTRACT(callbacks->onAudioReady(nullptr, pcm.data(), 8) == oboe::DataCallbackResult::Continue);
        OUTPUT_CONTRACT(std::all_of(pcm.begin(), pcm.end(), [](int16_t sample) { return sample == 0; }));
        OUTPUT_CONTRACT(owner.expired());
    } else if (scenario == 1) {
        auto started = [](const std::shared_ptr<oboe::AudioStream> &stream) {
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
            while (std::chrono::steady_clock::now() < deadline) {
                auto before = stream->getState();
                if (before == oboe::StreamState::Started) return true;
                oboe::StreamState after;
                stream->waitForStateChange(before, &after, 50'000'000);
            }
            return false;
        };
        OUTPUT_CONTRACT(output->start());
        auto previous = AudioOutputContractAccess::stream(output);
        OUTPUT_CONTRACT(previous && started(previous));
        previous->stop(); previous->close();
        OUTPUT_CONTRACT(previous->getState() == oboe::StreamState::Closed);
        callbacks->onErrorAfterClose(previous.get(), oboe::Result::ErrorDisconnected);
        auto recovered = AudioOutputContractAccess::stream(output);
        OUTPUT_CONTRACT(recovered && recovered.get() != previous.get() && started(recovered));
        output->stop();
        OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        OUTPUT_CONTRACT(recovered->getState() == oboe::StreamState::Closed);
        callbacks->onErrorAfterClose(recovered.get(), oboe::Result::ErrorDisconnected);
        OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
    } else if (scenario == 2) {
        callbacks->onErrorAfterClose(nullptr, oboe::Result::ErrorDisconnected);
        OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        output->stop();
    } else {
        OUTPUT_CONTRACT(false && "Unknown output fixture scenario");
    }
#undef OUTPUT_CONTRACT
    return env->NewStringUTF("output contracts passed");
}

// ALAC decoding uses the production engine and its normal PCM capture tap.
// Returned records are native-endian {int64 pts, int32 rate, int32 channels, PCM16}.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeDecodeAlac(JNIEnv *env, jobject, jobjectArray packets, jlongArray timestamps, jint samplesPerFrame) {
#define ALAC_CONTRACT(condition) do { if (!(condition)) { \
    auto error = env->FindClass("java/lang/AssertionError"); \
    if (error) { env->ThrowNew(error, #condition); env->DeleteLocalRef(error); } \
    return nullptr; } } while (false)
    ALAC_CONTRACT(packets && timestamps);
    ALAC_CONTRACT(samplesPerFrame >= 0 && samplesPerFrame <= 4096);
    const auto count = env->GetArrayLength(packets);
    ALAC_CONTRACT(count > 0 && count <= 32 && env->GetArrayLength(timestamps) == count);
    std::vector<jlong> pts(count);
    env->GetLongArrayRegion(timestamps, 0, count, pts.data());
    if (env->ExceptionCheck()) return nullptr;
    struct Capture {
        std::vector<std::vector<uint8_t>> records;
        static void receive(void *user, const int16_t *pcm, size_t samples, int64_t time, int rate, int channels) {
            auto &records = static_cast<Capture *>(user)->records;
            std::vector<uint8_t> record(16 + samples * sizeof(int16_t));
            const int32_t sampleRate = rate, channelCount = channels;
            memcpy(record.data(), &time, 8); memcpy(record.data() + 8, &sampleRate, 4);
            memcpy(record.data() + 12, &channelCount, 4); memcpy(record.data() + 16, pcm, samples * sizeof(int16_t));
            records.push_back(std::move(record));
        }
    } capture;
    using Engine = std::unique_ptr<AudioEngine, decltype(&audio_engine_destroy)>;
    Engine engine(audio_engine_create(std::make_shared<LogSink>(), 44100, 2), audio_engine_destroy);
    ALAC_CONTRACT(engine);
    ALAC_CONTRACT(audio_engine_configure(engine.get(), 50, 95, 0, true, true, false, false, false));
    audio_engine_set_pcm_callback(engine.get(), Capture::receive, &capture);
    audio_engine_set_capture_enabled(engine.get(), true);
    audio_engine_on_format(engine.get(), 2, samplesPerFrame);
    for (jsize i = 0; i < count; ++i) {
        auto packet = static_cast<jbyteArray>(env->GetObjectArrayElement(packets, i));
        if (env->ExceptionCheck()) return nullptr;
        ALAC_CONTRACT(packet);
        const auto length = env->GetArrayLength(packet);
        if (length <= 0 || length > 8192) {
            env->DeleteLocalRef(packet);
            ALAC_CONTRACT(false && "Invalid ALAC fixture packet length");
        }
        std::vector<uint8_t> bytes(length + AV_INPUT_BUFFER_PADDING_SIZE, 0);
        env->GetByteArrayRegion(packet, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
        env->DeleteLocalRef(packet);
        if (env->ExceptionCheck()) return nullptr;
        audio_engine_decode(engine.get(), bytes.data(), length, 2, pts[i]);
        ALAC_CONTRACT(capture.records.size() == static_cast<size_t>(i + 1));
    }
    auto bytesClass = env->FindClass("[B");
    if (!bytesClass) return nullptr;
    auto result = env->NewObjectArray(count, bytesClass, nullptr);
    env->DeleteLocalRef(bytesClass);
    if (!result) return nullptr;
    for (jsize i = 0; i < count; ++i) {
        const auto &record = capture.records[i];
        auto bytes = env->NewByteArray(static_cast<jsize>(record.size()));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(record.size()), reinterpret_cast<const jbyte *>(record.data()));
        if (!env->ExceptionCheck()) env->SetObjectArrayElement(result, i, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return nullptr;
    }
#undef ALAC_CONTRACT
    return result;
}

// Calls the production callback table with real JNI and an isolated Java sink.
// No receiver, player, discovery service or network listener is created.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeCallbackContracts(JNIEnv *env, jobject, jobject sink) {
    struct Context {
        android_callback_ctx_t value{};
        JNIEnv *env;
        Context(JNIEnv *e, jobject sink) : env(e) { android_callbacks_init(&value, e, sink); }
        ~Context() { android_callbacks_destroy(&value, env); }
    } owner(env, sink);
    auto *ctx = &owner.value;
    if (env->ExceptionCheck()) return nullptr;
#define CALLBACK_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    raop_callbacks_t cbs{};
    android_callbacks_fill(&cbs, ctx);
    CALLBACK_CONTRACT(cbs.cls == ctx && !cbs.register_client && !cbs.check_register);
    CALLBACK_CONTRACT(cbs.video_set_codec(ctx, VIDEO_CODEC_H265) == 0);
    ctx->h265_enabled = 0;
    CALLBACK_CONTRACT(cbs.video_set_codec(ctx, VIDEO_CODEC_H265) == -1);
    CALLBACK_CONTRACT(cbs.video_set_codec(ctx, VIDEO_CODEC_H264) == 0);
    CALLBACK_CONTRACT(cbs.video_set_codec(ctx, VIDEO_CODEC_UNKNOWN) == 0);
    CALLBACK_CONTRACT(cbs.conn_admit(ctx, "allow") == 1);
    CALLBACK_CONTRACT(cbs.conn_admit(ctx, "deny") == 0);
    CALLBACK_CONTRACT(cbs.conn_admit(ctx, "throw") == 0 && !env->ExceptionCheck());
    // The next callback after an exception must remain usable.
    CALLBACK_CONTRACT(cbs.conn_admit(ctx, "allow") == 1);

    unsigned char bytes[] = {0, 0, 1, 7};
    video_decode_struct video{};
    cbs.video_process(ctx, nullptr, &video);
    video.data = bytes; video.data_len = 0; cbs.video_process(ctx, nullptr, &video);
    video.data_len = -1; cbs.video_process(ctx, nullptr, &video);
    video.data_len = sizeof(bytes); bytes[0] = 1; cbs.video_process(ctx, nullptr, &video);
    bytes[0] = 0; video.ntp_time_local = 1234567; video.is_h265 = false;
    cbs.video_process(ctx, nullptr, &video);
    video.is_h265 = true; cbs.video_process(ctx, nullptr, &video);
    audio_decode_struct audio{}; // No engine: input must not be decoded.
    cbs.audio_process(ctx, nullptr, &audio);
    for (const auto callback : {cbs.audio_set_metadata, cbs.audio_set_coverart}) {
        callback(ctx, nullptr, sizeof(bytes)); callback(ctx, bytes, 0); callback(ctx, bytes, -1);
        callback(ctx, bytes, sizeof(bytes));
    }
    uint32_t start = 1, current = 0x80000000U, end = 0xffffffffU;
    cbs.audio_set_progress(ctx, nullptr, &current, &end);
    cbs.audio_set_progress(ctx, &start, nullptr, &end);
    cbs.audio_set_progress(ctx, &start, &current, nullptr);
    cbs.audio_set_progress(ctx, &start, &current, &end);
    cbs.audio_remote_control_id(ctx, nullptr, nullptr);
    cbs.audio_remote_control_id(ctx, "device", "remote");
    cbs.audio_set_volume(ctx, -12.5f);
    CALLBACK_CONTRACT(cbs.audio_set_client_volume(ctx) == -6.25);
    unsigned char ct = 4; unsigned short spf = 1024; bool screen = true, media = false; uint64_t format = 0;
    cbs.audio_get_format(ctx, &ct, &spf, &screen, &media, &format);
    float sw = 640, sh = 480, w = 1920, h = 1080;
    cbs.video_report_size(ctx, &sw, &sh, &w, &h);
    cbs.mirror_video_running(ctx, false); cbs.mirror_video_running(ctx, true);
    char pin[] = "1234";
    cbs.display_pin(ctx, pin); // No PIN requested: sink must not be called.
    ctx->require_pin = 1; android_callbacks_fill(&cbs, ctx); cbs.display_pin(ctx, pin);
    CALLBACK_CONTRACT(cbs.register_client && cbs.check_register);
    char key[] = "key-0";
    CALLBACK_CONTRACT(!cbs.check_register(ctx, key));
    cbs.register_client(ctx, "device", key, "name");
    cbs.register_client(ctx, "device", key, "name");
    CALLBACK_CONTRACT(ctx->registered_count == 1 && cbs.check_register(ctx, key));
    key[4] = 'x';
    CALLBACK_CONTRACT(!cbs.check_register(ctx, key) && cbs.check_register(ctx, "key-0"));
    for (int i = 1; i < 16; ++i) {
        const auto next = std::string("key-") + std::to_string(i);
        cbs.register_client(ctx, "device", next.c_str(), "name");
        CALLBACK_CONTRACT(cbs.check_register(ctx, next.c_str()));
    }
    cbs.register_client(ctx, "device", "overflow", "name");
    CALLBACK_CONTRACT(ctx->registered_count == 16 && !cbs.check_register(ctx, "overflow"));

    playback_info_t info{};
    android_callbacks_update_playback_info(ctx, 12.5, 90, 1.5f, 1);
    cbs.on_video_wait_ready(ctx); // Already ready; no timeout or player needed.
    cbs.on_video_acquire_playback_info(ctx, &info);
    CALLBACK_CONTRACT(info.position == 12.5 && info.duration == 90 && info.rate == 1.5f && info.ready_to_play);
    CALLBACK_CONTRACT(info.seek_start == 0 && info.seek_duration == 90 && !info.playback_buffer_empty && info.playback_buffer_full && info.playback_likely_to_keep_up);
    CALLBACK_CONTRACT(cbs.on_video_playlist_remove(ctx) == 12.5f);
    cbs.conn_init(ctx); // Must preserve an active timeline.
    CALLBACK_CONTRACT(ctx->playback_duration == 90 && ctx->playback_ready == 1);
    cbs.on_video_play(ctx, nullptr, 2.5f);
    CALLBACK_CONTRACT(ctx->playback_position == 2.5 && ctx->playback_duration == 0 && !ctx->playback_ready && !ctx->play_ready);
    cbs.on_video_play(ctx, "https://example.invalid/movie", 3.5f);
    cbs.on_video_scrub(ctx, 8.5f); cbs.on_video_rate(ctx, 0.5f);
    cbs.on_video_stop(ctx);
    CALLBACK_CONTRACT(ctx->playback_duration == -1 && !ctx->playback_ready);
    cbs.conn_init(ctx); // A finished timeline must be cleared.
    CALLBACK_CONTRACT(ctx->playback_position == 0 && ctx->playback_duration == 0 && ctx->playback_rate == 0);
    cbs.on_video_acquire_playback_info(ctx, &info);
    CALLBACK_CONTRACT(info.seek_duration == 0 && !info.ready_to_play);
    cbs.video_reset(ctx, RESET_TYPE_HLS_CONN_CLOSED); // Buffering is not paused playback.
    CALLBACK_CONTRACT(ctx->playback_duration == 0);
    android_callbacks_update_playback_info(ctx, 4, 90, 1, 1);
    cbs.video_reset(ctx, RESET_TYPE_HLS_CONN_CLOSED);
    CALLBACK_CONTRACT(ctx->playback_duration == 90);
    android_callbacks_update_playback_info(ctx, 4, 90, 0, 1);
    cbs.video_reset(ctx, RESET_TYPE_HLS_CONN_CLOSED);
    CALLBACK_CONTRACT(ctx->playback_duration == -1);
    for (auto reset : {RESET_TYPE_NOHOLD, RESET_TYPE_RTP_SHUTDOWN, RESET_TYPE_ON_VIDEO_PLAY, RESET_TYPE_RTP_TO_HLS_TEARDOWN}) {
        android_callbacks_update_playback_info(ctx, 4, 90, 1, 1);
        cbs.video_reset(ctx, reset); CALLBACK_CONTRACT(ctx->playback_duration == 90);
    }
    for (auto reset : {RESET_TYPE_HLS_SHUTDOWN, RESET_TYPE_HLS_EOS}) {
        android_callbacks_update_playback_info(ctx, 4, 90, 1, 1);
        cbs.video_reset(ctx, reset); CALLBACK_CONTRACT(ctx->playback_duration == -1);
    }
    cbs.video_pause(ctx); cbs.video_resume(ctx); cbs.conn_feedback(ctx);
    cbs.audio_flush(ctx); cbs.video_flush(ctx); cbs.audio_stop_coverart_rendering(ctx);
    cbs.conn_reset(ctx, 7); cbs.conn_destroy(ctx);
    cbs.conn_reset(ctx, -1); // Sink deliberately throws: next callback must recover.
    CALLBACK_CONTRACT(env->ExceptionCheck());
    cbs.conn_init(ctx);
    CALLBACK_CONTRACT(!env->ExceptionCheck());

    int16_t pcm[] = {1, -2, 32767, -32768};
    android_capture_pcm(nullptr, pcm, 4, 42, 44100, 2);
    android_callback_ctx_t empty{};
    android_capture_pcm(&empty, pcm, 4, 42, 44100, 2);
    android_capture_pcm(ctx, pcm, 0, 42, 44100, 2);
    android_capture_pcm(ctx, pcm, 65537, 42, 44100, 2);
    android_capture_pcm(ctx, pcm, 4, 42, 44100, 2);
    std::thread attached([&] {
        cbs.conn_init(ctx); // _get_env attaches once; TLS destructor detaches.
        cbs.conn_destroy(ctx);
    });
    attached.join();
    std::thread captured([&] { android_capture_pcm(ctx, pcm, 4, 43, 48000, 1); });
    captured.join(); // Capture has its own balanced attach/detach path.
    android_capture_pcm(ctx, pcm, 4, 44, 22050, 2); // Sink throws; capture clears it.
    CALLBACK_CONTRACT(!env->ExceptionCheck());
    android_capture_pcm(ctx, pcm, 4, 45, 16000, 1);
    CALLBACK_CONTRACT(!env->ExceptionCheck());
#undef CALLBACK_CONTRACT
    return env->NewStringUTF("callback contracts passed");
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeDump(JNIEnv *env, jobject, jstring path) {
    const char *name = env->GetStringUTFChars(path, nullptr);
    if (!name) return -1;
    __llvm_profile_set_filename(name);
    int result = __llvm_profile_write_file();
    env->ReleaseStringUTFChars(path, name);
    return result;
}

// Calls the production C API, including its pre-configuration and invalid-input
// contracts. No mock decoder/output replaces application code in this fixture.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeAudioContracts(JNIEnv *env, jobject) {
#define AUDIO_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    const uint8_t invalidPacket = 0;
    std::array<uint8_t, 4096> debug{};
    AUDIO_CONTRACT(audio_engine_create({}, 44100, 2) == nullptr);
    AUDIO_CONTRACT(!audio_engine_configure(nullptr, 0, 95, 0, false, false, false, false, false));
    AUDIO_CONTRACT(!audio_engine_start(nullptr));
    AUDIO_CONTRACT(!audio_engine_get_debug(nullptr, debug.data(), debug.size()));
    audio_engine_set_pcm_callback(nullptr, nullptr, nullptr);
    audio_engine_set_capture_enabled(nullptr, true);
    audio_engine_set_client_gain(nullptr, 0.5f);
    audio_engine_on_format(nullptr, 4, 1024);
    audio_engine_pause(nullptr);
    audio_engine_decode(nullptr, &invalidPacket, 1, 4, 1);
    audio_engine_destroy(nullptr);

    using Engine = std::unique_ptr<AudioEngine, decltype(&audio_engine_destroy)>;
    Engine engine(audio_engine_create(std::make_shared<LogSink>(), 44100, 2), audio_engine_destroy);
    AUDIO_CONTRACT(engine != nullptr);
    AUDIO_CONTRACT(!audio_engine_start(engine.get()));
    audio_engine_pause(engine.get());
    audio_engine_set_client_gain(engine.get(), 0.25f);
    AUDIO_CONTRACT(!audio_engine_get_debug(engine.get(), nullptr, debug.size()));
    AUDIO_CONTRACT(!audio_engine_get_debug(engine.get(), debug.data(), 1));
    debug.fill(0xa5);
    AUDIO_CONTRACT(audio_engine_get_debug(engine.get(), debug.data(), debug.size()));
    AUDIO_CONTRACT(debug[0] == 0);
    audio_engine_decode(engine.get(), nullptr, 1, 4, 1);
    audio_engine_decode(engine.get(), &invalidPacket, 0, 4, 1);
    audio_engine_decode(engine.get(), &invalidPacket, -1, 4, 1);
    audio_engine_decode(engine.get(), &invalidPacket, 1, 4, 1); // before configuration
    audio_engine_on_format(engine.get(), -1, 1024);
    audio_engine_on_format(engine.get(), 4, 0);
    audio_engine_on_format(engine.get(), 2, 352);
    audio_engine_on_format(engine.get(), 4, 1024);
    audio_engine_on_format(engine.get(), 8, 480);

    // Changing defaults is a process-wide operation: restore both values before
    // any failure can return to the Android instrumentation runner.
    const auto oldRate = oboe::DefaultStreamValues::SampleRate;
    const auto oldBurst = oboe::DefaultStreamValues::FramesPerBurst;
    audio_engine_set_default_stream_values(48000, 192);
    const bool changed = oboe::DefaultStreamValues::SampleRate == 48000 && oboe::DefaultStreamValues::FramesPerBurst == 192;
    audio_engine_set_default_stream_values(0, -1);
    const bool ignored = oboe::DefaultStreamValues::SampleRate == 48000 && oboe::DefaultStreamValues::FramesPerBurst == 192;
    oboe::DefaultStreamValues::SampleRate = oldRate;
    oboe::DefaultStreamValues::FramesPerBurst = oldBurst;
    AUDIO_CONTRACT(changed && ignored);

    AUDIO_CONTRACT(audio_engine_configure(engine.get(), 20, 95, 0, true, true, false, false, false));
    audio_engine_set_pcm_callback(engine.get(), nullptr, nullptr);
    audio_engine_set_capture_enabled(engine.get(), true);
    audio_engine_set_capture_enabled(engine.get(), false);
    audio_engine_set_client_gain(engine.get(), 0.5f);
    AUDIO_CONTRACT(audio_engine_get_debug(engine.get(), debug.data(), debug.size()));
    // Two queued changes must replace, rather than leak, the older pending config.
    AUDIO_CONTRACT(audio_engine_configure(engine.get(), 30, 95, 0, true, true, false, false, false));
    AUDIO_CONTRACT(audio_engine_configure(engine.get(), 40, 95, 0, true, true, false, false, false));
    audio_engine_decode(engine.get(), &invalidPacket, 1, -1, 1);
    audio_engine_decode(engine.get(), &invalidPacket, 1, -1, 2); // failed-decoder retry backoff
    AUDIO_CONTRACT(audio_engine_get_debug(engine.get(), debug.data(), debug.size()));
    audio_engine_pause(engine.get());
    // Destruction must also release a config still pending when playback ends.
    AUDIO_CONTRACT(audio_engine_configure(engine.get(), 50, 95, 0, true, true, false, false, false));
#undef AUDIO_CONTRACT
    return env->NewStringUTF("audio contracts passed");
}

// The Android shim builds TXT records in memory; these calls do not publish DNS.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeDnsContracts(JNIEnv *env, jobject) {
#define DNS_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    using Service = std::unique_ptr<dnssd_t, decltype(&dnssd_destroy)>;
    using Records = std::map<std::string, std::string>;
    auto records = [](dnssd_t *service, bool raop, Records &result) {
        result.clear();
        const int count = raop ? android_dnssd_get_raop_txt_count(service) : android_dnssd_get_airplay_txt_count(service);
        auto key = raop ? android_dnssd_get_raop_txt_key : android_dnssd_get_airplay_txt_key;
        auto value = raop ? android_dnssd_get_raop_txt_val : android_dnssd_get_airplay_txt_val;
        if (count < 0 || count > 32) return false;
        for (int index : {-1, count, count + 1}) {
            if (key(service, index) || value(service, index)) return false;
        }
        for (int index = 0; index < count; ++index) {
            const char *k = key(service, index), *v = value(service, index);
            if (!k || !v || !*k || std::strlen(k) > 31 || std::strlen(v) > 127) return false;
            if (!result.emplace(k, v).second) return false;
        }
        return true;
    };
    const char name[] = "Buzin DNS \xC3\xA7";
    const std::array<char, 6> hardware{{0x01, 0x23, 0x45, 0x67, static_cast<char>(0x89), static_cast<char>(0xab)}};
    dnssd_destroy(nullptr);
    for (unsigned char pin : {0, 1, 2, 3, 255}) {
        int error = -1;
        Service service(dnssd_init(name, sizeof(name) - 1, hardware.data(), hardware.size(), &error, pin), dnssd_destroy);
        DNS_CONTRACT(service && error == DNSSD_ERROR_NOERROR);
        Records raop, airplay;
        DNS_CONTRACT(records(service.get(), true, raop) && raop.empty());
        DNS_CONTRACT(records(service.get(), false, airplay) && airplay.empty());
        int length = -1;
        const char *copied = dnssd_get_name(service.get(), &length);
        DNS_CONTRACT(length == sizeof(name) - 1 && std::memcmp(copied, name, sizeof(name)) == 0 && copied != name);
        copied = dnssd_get_hw_addr(service.get(), &length);
        DNS_CONTRACT(length == hardware.size() && copied != hardware.data() && std::memcmp(copied, hardware.data(), hardware.size()) == 0);
        length = 99;
        DNS_CONTRACT(dnssd_get_raop_txt(service.get(), &length) == nullptr && length == 0);
        DNS_CONTRACT(dnssd_get_raop_txt(service.get(), nullptr) == nullptr);
        length = 99;
        DNS_CONTRACT(dnssd_get_airplay_txt(service.get(), &length) == nullptr && length == 0);
        DNS_CONTRACT(dnssd_get_airplay_txt(service.get(), nullptr) == nullptr);
        for (int bit : {0, 31, 32, 63}) {
            uint64_t expected = dnssd_get_airplay_features(service.get()) & ~(uint64_t{1} << bit);
            dnssd_set_airplay_features(service.get(), bit, 0);
            DNS_CONTRACT(dnssd_get_airplay_features(service.get()) == expected);
            dnssd_set_airplay_features(service.get(), bit, 1);
            expected |= uint64_t{1} << bit;
            DNS_CONTRACT(dnssd_get_airplay_features(service.get()) == expected);
        }
        const auto features = dnssd_get_airplay_features(service.get());
        for (const auto &[bit, value] : std::array<std::pair<int, int>, 4>{{{-1, 0}, {64, 0}, {0, -1}, {0, 2}}}) {
            dnssd_set_airplay_features(service.get(), bit, value);
            DNS_CONTRACT(dnssd_get_airplay_features(service.get()) == features);
        }
        for (int alac : {0, 1}) for (int aac : {0, 1}) {
            android_dnssd_set_codecs(service.get(), alac, aac);
            DNS_CONTRACT(dnssd_register_raop(service.get(), 7000) == 0);
            DNS_CONTRACT(dnssd_register_airplay(service.get(), 7000) == 0);
            DNS_CONTRACT(records(service.get(), true, raop) && records(service.get(), false, airplay));
            const std::string codecs = std::string("0") + (alac ? ",1" : "") + (aac ? ",2,3" : "");
            DNS_CONTRACT(raop["cn"] == codecs && raop["md"] == "0,1,2");
            DNS_CONTRACT(raop["pw"] == ((pin >= 1 && pin <= 3) ? "true" : "false"));
            DNS_CONTRACT(raop["sf"] == ((pin == 2 || pin == 3) ? "0x84" : pin == 1 ? "0x8c" : "0x4"));
            DNS_CONTRACT(airplay["pw"] == ((pin >= 1 && pin <= 3) ? "true" : "false"));
            DNS_CONTRACT(airplay["deviceid"] == "01:23:45:67:89:ab");
            DNS_CONTRACT(std::string(android_dnssd_get_raop_servname(service.get())) == std::string("0123456789AB@") + name);
        }
        char publicKey[] = "coverage-public-key";
        dnssd_set_pk(service.get(), publicKey);
        DNS_CONTRACT(dnssd_register_raop(service.get(), 7000) == 0 && dnssd_register_airplay(service.get(), 7000) == 0);
        DNS_CONTRACT(records(service.get(), true, raop) && records(service.get(), false, airplay));
        DNS_CONTRACT(raop["pk"] == publicKey && airplay["pk"] == publicKey);
        dnssd_set_pk(service.get(), nullptr);
        android_dnssd_set_compressed_audio_only(service.get());
        DNS_CONTRACT(dnssd_register_raop(service.get(), 7000) == 0 && dnssd_register_airplay(service.get(), 7000) == 0);
        DNS_CONTRACT(records(service.get(), true, raop) && records(service.get(), false, airplay));
        DNS_CONTRACT(raop["cn"] == "1,2,3" && !raop.contains("md") && !raop.contains("pk") && !airplay.contains("pk"));
        const auto beforeMetadata = dnssd_get_airplay_features(service.get());
        android_dnssd_enable_metadata(service.get());
        DNS_CONTRACT(dnssd_get_airplay_features(service.get()) == (beforeMetadata | (uint64_t{7} << 15)));
        DNS_CONTRACT(dnssd_register_raop(service.get(), 7000) == 0);
        DNS_CONTRACT(records(service.get(), true, raop) && raop["cn"] == "1,2,3" && raop["md"] == "0,1,2");
        dnssd_unregister_raop(service.get());
        dnssd_unregister_airplay(service.get());
    }
    std::array<char, 128> oversizedHardware{};
    Service oversized(dnssd_init(name, sizeof(name) - 1, oversizedHardware.data(), oversizedHardware.size(), nullptr, 0), dnssd_destroy);
    DNS_CONTRACT(oversized != nullptr);
    DNS_CONTRACT(dnssd_register_raop(oversized.get(), 7000) == -1);
    DNS_CONTRACT(dnssd_register_airplay(oversized.get(), 7000) == -1);
#undef DNS_CONTRACT
    return env->NewStringUTF("DNS contracts passed");
}
