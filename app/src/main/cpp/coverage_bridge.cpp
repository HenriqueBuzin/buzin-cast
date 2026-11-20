// Coverage-only JNI hook. Never linked in production builds.
#include <jni.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <condition_variable>
#include <memory>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <future>
#include <string>
#include <thread>
#include <vector>
#include <chrono>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include "audio_engine.h"
#include "audio_output.h"
#include "log_sink.h"
#include "native_server_context.h"
extern "C" {
#include <libavcodec/defs.h>
#include "dnssd.h"
#include "android_dnssd_shim.h"
#include "android_raop_callbacks.h"
}
extern "C" void __llvm_profile_set_filename(const char *);
extern "C" int __llvm_profile_write_file(void);

extern "C" int __real_random_pin(void);
extern "C" raop_t *__real_raop_init(raop_callbacks_t *);
extern "C" int __real_raop_init2(raop_t *, int, const char *, const char *);
extern "C" dnssd_t *__real_dnssd_init(const char *, int, const char *, int, int *, unsigned char);
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeInit(
    JNIEnv *, jobject, jobject, jbyteArray, jstring, jstring, jboolean, jboolean);
extern "C" JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeDestroy(JNIEnv *, jobject, jlong);
extern "C" JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_DebugNativeBridge_nativeFeedAudio(JNIEnv *, jobject, jlong, jbyteArray, jlong);
extern "C" ssize_t __real_AMediaCodec_dequeueInputBuffer(AMediaCodec *, int64_t);
extern "C" const char *__real_android_dnssd_get_raop_txt_key(dnssd_t *, int);
extern "C" const char *__real_android_dnssd_get_raop_txt_val(dnssd_t *, int);
extern "C" const char *__real_android_dnssd_get_airplay_txt_key(dnssd_t *, int);
extern "C" const char *__real_android_dnssd_get_airplay_txt_val(dnssd_t *, int);
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetRaopTxtRecords(JNIEnv *, jobject, jlong);
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetAirplayTxtRecords(JNIEnv *, jobject, jlong);

namespace {
struct PinResultContract {
    int result;
    int calls = 0;
};
thread_local PinResultContract *activePinResult = nullptr;
struct ScopedPinResult {
    PinResultContract value;
    PinResultContract *previous;
    explicit ScopedPinResult(int result) : value{result}, previous(activePinResult) { activePinResult = &value; }
    ~ScopedPinResult() { activePinResult = previous; }
};
struct InputBufferContract { int remaining; int64_t minimumWaitUs; int calls = 0; };
thread_local InputBufferContract *activeInputBuffer = nullptr;
struct ScopedInputBuffer {
    InputBufferContract value;
    InputBufferContract *previous = activeInputBuffer;
    explicit ScopedInputBuffer(int failures, int64_t minimumWaitUs = 0) : value{failures, minimumWaitUs} {
        activeInputBuffer = &value;
    }
    ~ScopedInputBuffer() { activeInputBuffer = previous; }
};
struct TxtRecordContract { dnssd_t *owner; bool raop; int part; int hits = 0; };
thread_local TxtRecordContract *activeTxtRecord = nullptr;
struct ScopedTxtRecord {
    TxtRecordContract value;
    TxtRecordContract *previous = activeTxtRecord;
    ScopedTxtRecord(dnssd_t *owner, bool raop, int part) : value{owner, raop, part} { activeTxtRecord = &value; }
    ~ScopedTxtRecord() { activeTxtRecord = previous; }
};
bool missingTxtPart(dnssd_t *owner, int index, bool raop, int part) {
    if (activeTxtRecord && activeTxtRecord->owner == owner && index == 0 &&
        activeTxtRecord->raop == raop && activeTxtRecord->part == part) {
        ++activeTxtRecord->hits;
        return true;
    }
    return false;
}
}

extern "C" const char *__wrap_android_dnssd_get_raop_txt_key(dnssd_t *owner, int index) {
    return missingTxtPart(owner, index, true, 1) ? nullptr : __real_android_dnssd_get_raop_txt_key(owner, index);
}
extern "C" const char *__wrap_android_dnssd_get_raop_txt_val(dnssd_t *owner, int index) {
    return missingTxtPart(owner, index, true, 2) ? nullptr : __real_android_dnssd_get_raop_txt_val(owner, index);
}
extern "C" const char *__wrap_android_dnssd_get_airplay_txt_key(dnssd_t *owner, int index) {
    return missingTxtPart(owner, index, false, 1) ? nullptr : __real_android_dnssd_get_airplay_txt_key(owner, index);
}
extern "C" const char *__wrap_android_dnssd_get_airplay_txt_val(dnssd_t *owner, int index) {
    return missingTxtPart(owner, index, false, 2) ? nullptr : __real_android_dnssd_get_airplay_txt_val(owner, index);
}

// Registration in this shim only fills in-memory records. No NSD publication or
// network listener is started. Build the map through the shipped JNI function.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeDiscoveryMapWithMissingPart(
        JNIEnv *env, jobject, jobject bridge, jlong handle, jboolean raop, jint part) {
    auto *ctx = reinterpret_cast<server_ctx_t *>(handle);
    if (!ctx || !ctx->dnssd || part < 0 || part > 2) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error) { env->ThrowNew(error, "Expected an owned discovery context and part0..2"); env->DeleteLocalRef(error); }
        return nullptr;
    }
    if (dnssd_register_raop(ctx->dnssd, 7000) || dnssd_register_airplay(ctx->dnssd, 7000)) {
        jclass error = env->FindClass("java/lang/AssertionError");
        if (error) { env->ThrowNew(error, "Could not build in-memory discovery records"); env->DeleteLocalRef(error); }
        return nullptr;
    }
    ScopedTxtRecord forced(ctx->dnssd, raop == JNI_TRUE, part);
    jobject map = raop ? Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetRaopTxtRecords(env, bridge, handle)
                      : Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetAirplayTxtRecords(env, bridge, handle);
    if (!env->ExceptionCheck() && forced.value.hits != (part ? 1 : 0)) {
        if (map) env->DeleteLocalRef(map);
        jclass error = env->FindClass("java/lang/AssertionError");
        if (error) { env->ThrowNew(error, "Discovery contract did not reach the requested getter exactly once"); env->DeleteLocalRef(error); }
        return nullptr;
    }
    return map;
}

extern "C" ssize_t __wrap_AMediaCodec_dequeueInputBuffer(AMediaCodec *codec, int64_t timeoutUs) {
    if (activeInputBuffer) {
        ++activeInputBuffer->calls;
        if (activeInputBuffer->minimumWaitUs == 0) {
            __android_log_print(ANDROID_LOG_DEBUG, "NativeInputContract", "input call=%d timeoutUs=%lld forcedRemaining=%d",
                activeInputBuffer->calls, static_cast<long long>(timeoutUs), activeInputBuffer->remaining);
        }
        if (activeInputBuffer->remaining > 0) {
            --activeInputBuffer->remaining;
            std::this_thread::sleep_for(std::chrono::microseconds(std::clamp<int64_t>(timeoutUs, 0, 100000)));
            return AMEDIACODEC_INFO_TRY_AGAIN_LATER;
        }
        timeoutUs = std::max(timeoutUs, activeInputBuffer->minimumWaitUs);
    }
    const ssize_t result = __real_AMediaCodec_dequeueInputBuffer(codec, timeoutUs);
    if (result < 0) __android_log_print(ANDROID_LOG_WARN, "NativeInputContract",
        "actual input dequeue timeoutUs=%lld result=%lld", static_cast<long long>(timeoutUs), static_cast<long long>(result));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeFeedWithInputNotReady(
        JNIEnv *env, jobject, jobject bridge, jlong handle, jbyteArray data, jlong ptsNs, jint failures) {
    if (failures < 0 || failures > 2) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error) { env->ThrowNew(error, "Expected zero, one or two unavailable input results"); env->DeleteLocalRef(error); }
        return 0;
    }
    ScopedInputBuffer forced(failures);
    Java_io_github_jqssun_airplay_DebugNativeBridge_nativeFeedAudio(env, bridge, handle, data, ptsNs);
    return forced.value.calls;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeFeedAwaitingCodecInput(
        JNIEnv *env, jobject, jobject bridge, jlong handle, jbyteArray data, jlong ptsNs) {
    // PCM verification feeds an offline fixture. Let the host emulator's
    // decoder drain a busy queue while keeping live input's budget separate.
    ScopedInputBuffer playbackFixture(0, 500'000);
    Java_io_github_jqssun_airplay_DebugNativeBridge_nativeFeedAudio(env, bridge, handle, data, ptsNs);
}

// Only the instrumented library links this wrapper. Ordinary calls, including
// other threads, use the original CSPRNG implementation without modification.
extern "C" int __wrap_random_pin(void) {
    if (activePinResult) {
        ++activePinResult->calls;
        return activePinResult->result;
    }
    return __real_random_pin();
}

namespace {
enum class InitStage { RaopAllocation, RaopConfiguration, DnsAllocation };
struct InitFailure {
    InitStage stage;
    int calls = 0;
};
thread_local InitFailure *forcedInitFailure = nullptr;

bool failInitStage(InitStage stage) {
    if (!forcedInitFailure || forcedInitFailure->stage != stage) return false;
    ++forcedInitFailure->calls;
    return true;
}
}

extern "C" raop_t *__wrap_raop_init(raop_callbacks_t *callbacks) {
    return failInitStage(InitStage::RaopAllocation) ? nullptr : __real_raop_init(callbacks);
}

extern "C" int __wrap_raop_init2(raop_t *raop, int nohold, const char *deviceId, const char *keyFile) {
    return failInitStage(InitStage::RaopConfiguration) ? -1 : __real_raop_init2(raop, nohold, deviceId, keyFile);
}

extern "C" dnssd_t *__wrap_dnssd_init(const char *name, int nameLength, const char *address,
                                      int addressLength, int *error, unsigned char pin) {
    if (!failInitStage(InitStage::DnsAllocation)) {
        return __real_dnssd_init(name, nameLength, address, addressLength, error, pin);
    }
    if (error) *error = DNSSD_ERROR_OUTOFMEM;
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeWithInitFailure(JNIEnv *env, jobject, jint stage, jobject action) {
    jclass runnable = env->FindClass("java/lang/Runnable");
    if (!runnable) return 0;
    jmethodID run = env->GetMethodID(runnable, "run", "()V");
    env->DeleteLocalRef(runnable);
    if (!run) return 0;

    InitFailure failure{static_cast<InitStage>(stage)};
    auto *previous = forcedInitFailure;
    forcedInitFailure = &failure;
    env->CallVoidMethod(action, run);
    forcedInitFailure = previous;
    return failure.calls;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeInitWithPinResult(
        JNIEnv *env, jobject, jobject bridge, jobject callback, jbyteArray hardware,
        jstring name, jstring keyFile, jint result) {
    ScopedPinResult forced(result);
    const jlong handle = Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeInit(
        env, bridge, callback, hardware, name, keyFile, JNI_FALSE, JNI_TRUE);
    if (!env->ExceptionCheck() && forced.value.calls != 1) {
        if (handle) Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeDestroy(env, bridge, handle);
        jclass error = env->FindClass("java/lang/AssertionError");
        if (error) {
            env->ThrowNew(error, "PIN contract did not invoke the actual wrapped generator exactly once");
            env->DeleteLocalRef(error);
        }
        return 0;
    }
    return handle;
}

// Own a real callback binding and synchronization primitives, but intentionally
// stop before creating child subsystems. No listener, decoder or output opens.
// Kotlin must release the returned context through the shipped nativeDestroy.
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeCreatePartialServer(
        JNIEnv *env, jobject, jobject callback) {
    auto ctx = std::make_unique<server_ctx_t>();
    if (!android_callbacks_init(&ctx->cb_ctx, env, callback)) return 0;
    android_callbacks_fill(&ctx->callbacks, &ctx->cb_ctx);
    return reinterpret_cast<jlong>(ctx.release());
}

// Access owns references under the production lock; no stream state is faked.
struct AudioOutputContractAccess {
    static bool debugWhileLocked(const std::shared_ptr<AudioOutput> &output,
                                 AudioOutput::Debug &result) {
        std::promise<void> acquired, release;
        auto ready = acquired.get_future();
        auto released = release.get_future();
        std::thread holder([&] {
            std::lock_guard<std::mutex> lock(output->mLock);
            acquired.set_value();
            // A regression in debugInfo must fail in bounded time, not deadlock.
            released.wait_for(std::chrono::seconds(2));
        });
        const bool locked = ready.wait_for(std::chrono::seconds(5)) == std::future_status::ready;
        const auto begin = std::chrono::steady_clock::now();
        if (locked) result = output->debugInfo();
        const auto elapsed = std::chrono::steady_clock::now() - begin;
        release.set_value();
        holder.join();
        return locked && elapsed < std::chrono::milliseconds(500);
    }
    static std::shared_ptr<OboeCallbacks> callbacks(const std::shared_ptr<AudioOutput> &output) {
        std::lock_guard<std::mutex> lock(output->mLock);
        return output->mCallbacks;
    }
    static std::shared_ptr<oboe::AudioStream> stream(const std::shared_ptr<AudioOutput> &output) {
        std::lock_guard<std::mutex> lock(output->mLock);
        return output->mStream;
    }
};

namespace {
thread_local bool *audioStreamClosure = nullptr;

struct ScopedAudioStreamClosure {
    bool closed = false;
    bool *previous = audioStreamClosure;
    ScopedAudioStreamClosure() { audioStreamClosure = &closed; }
    ~ScopedAudioStreamClosure() { audioStreamClosure = previous; }
};

// Exercise diagnostic values that a particular emulator may never report.
class DiagnosticOboeStream final : public oboe::AudioStream {
public:
    DiagnosticOboeStream(const oboe::AudioStreamBuilder &builder, oboe::AudioApi api)
        : AudioStream(builder), mApi(api) {
        mFramesPerBurst = 192;
        mBufferSizeInFrames = 192;
        mBufferCapacityInFrames = 384;
    }

    oboe::AudioApi getAudioApi() const override { return mApi; }
    oboe::StreamState getState() override { return mState; }
    oboe::Result requestStart() override { mState = oboe::StreamState::Started; return oboe::Result::OK; }
    oboe::Result requestStop() override { mState = oboe::StreamState::Stopped; return oboe::Result::OK; }
    oboe::Result requestPause() override { return oboe::Result::ErrorUnimplemented; }
    oboe::Result requestFlush() override { return oboe::Result::ErrorUnimplemented; }
    oboe::Result waitForStateChange(oboe::StreamState, oboe::StreamState *state, int64_t) override {
        *state = mState;
        return oboe::Result::OK;
    }
    oboe::Result close() override { mState = oboe::StreamState::Closed; return oboe::Result::OK; }
    bool isXRunCountSupported() const override { return mFirstPoll; }
    oboe::ResultWithValue<int32_t> getXRunCount() override {
        if (!mFirstPoll) return oboe::ResultWithValue<int32_t>(oboe::Result::ErrorUnimplemented);
        mFirstPoll = false;
        return oboe::ResultWithValue<int32_t>(41);
    }

private:
    void updateFramesWritten() override {}
    void updateFramesRead() override {}
    const oboe::AudioApi mApi;
    oboe::StreamState mState = oboe::StreamState::Open;
    bool mFirstPoll = true;
};

thread_local const oboe::AudioApi *diagnosticAudioApi = nullptr;
struct ScopedAudioDiagnostics {
    const oboe::AudioApi api;
    const oboe::AudioApi *previous = diagnosticAudioApi;
    explicit ScopedAudioDiagnostics(oboe::AudioApi value) : api(value) { diagnosticAudioApi = &api; }
    ~ScopedAudioDiagnostics() { diagnosticAudioApi = previous; }
};
}

// Run the real builder, then emulate losing the opened stream before it starts.
extern "C" oboe::Result realOboeOpenStream(
    oboe::AudioStreamBuilder *, std::shared_ptr<oboe::AudioStream> &)
    asm("__real__ZN4oboe18AudioStreamBuilder10openStreamERNSt6__ndk110shared_ptrINS_11AudioStreamEEE");
extern "C" oboe::Result wrapOboeOpenStream(
    oboe::AudioStreamBuilder *, std::shared_ptr<oboe::AudioStream> &)
    asm("__wrap__ZN4oboe18AudioStreamBuilder10openStreamERNSt6__ndk110shared_ptrINS_11AudioStreamEEE");
extern "C" oboe::Result wrapOboeOpenStream(
    oboe::AudioStreamBuilder *builder, std::shared_ptr<oboe::AudioStream> &stream) {
    if (diagnosticAudioApi) {
        stream = std::make_shared<DiagnosticOboeStream>(*builder, *diagnosticAudioApi);
        return oboe::Result::OK;
    }
    // OpenSL ES retains readable buffer metadata after close. Android API 28
    // AAudio's MMAP query dereferences the native stream even after close.
    if (audioStreamClosure) builder->setAudioApi(oboe::AudioApi::OpenSLES);
    const auto result = realOboeOpenStream(builder, stream);
    if (audioStreamClosure && result == oboe::Result::OK) {
        *audioStreamClosure = stream->close() == oboe::Result::OK &&
                              stream->getState() == oboe::StreamState::Closed;
    }
    return result;
}

extern "C" bool realOboeIsMMapUsed(oboe::AudioStream *)
    asm("__real__ZN4oboe14OboeExtensions10isMMapUsedEPNS_11AudioStreamE");
extern "C" bool wrapOboeIsMMapUsed(oboe::AudioStream *)
    asm("__wrap__ZN4oboe14OboeExtensions10isMMapUsedEPNS_11AudioStreamE");
extern "C" bool wrapOboeIsMMapUsed(oboe::AudioStream *stream) {
    return diagnosticAudioApi ? *diagnosticAudioApi == oboe::AudioApi::AAudio : realOboeIsMMapUsed(stream);
}

// Deliberately invokes the real post-close callback; no physical disconnection
// is simulated at the OS level, and all opened emulator streams are closed.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeOutputContracts(JNIEnv *env, jobject, jint scenario, jobject listener) {
#define OUTPUT_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    auto timeline = std::make_shared<TimelineBuffer>(44100, 2, 50, 95);
    auto log = std::make_shared<LogSink>();
    if (listener) log->bind(env, listener);
    const bool automaticLowLatency = scenario == 4;
    auto output = AudioOutput::create(scenario == 2 ? -1 : 44100, 2,
                                     automaticLowLatency ? 0 : 192, automaticLowLatency, timeline, log);
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
        OUTPUT_CONTRACT(!output->start());
        OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        callbacks->onErrorAfterClose(nullptr, oboe::Result::ErrorDisconnected);
        OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        output->stop();
    } else if (scenario == 3) {
        OUTPUT_CONTRACT(output->start());
        auto stream = AudioOutputContractAccess::stream(output);
        OUTPUT_CONTRACT(stream);
        const auto cached = output->debugInfo();
        AudioOutput::Debug concurrent{};
        OUTPUT_CONTRACT(AudioOutputContractAccess::debugWhileLocked(output, concurrent));
        OUTPUT_CONTRACT(concurrent.xrun == cached.xrun);
        OUTPUT_CONTRACT(AudioOutputContractAccess::stream(output) == stream);
        output->stop();
        OUTPUT_CONTRACT(stream->getState() == oboe::StreamState::Closed);
        OUTPUT_CONTRACT(output->debugInfo().xrun == cached.xrun);
    } else if (scenario == 4) {
        {
            ScopedAudioStreamClosure closeOnOpen;
            OUTPUT_CONTRACT(!output->start());
            OUTPUT_CONTRACT(closeOnOpen.closed);
            OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        }
        OUTPUT_CONTRACT(output->start());
        auto stream = AudioOutputContractAccess::stream(output);
        OUTPUT_CONTRACT(stream && stream->getBufferSizeInFrames() > 0);
        OUTPUT_CONTRACT(stream->getBufferSizeInFrames() <= stream->getBufferCapacityInFrames());
        output->stop();
        OUTPUT_CONTRACT(stream->getState() == oboe::StreamState::Closed);
    } else if (scenario == 5) {
        for (const auto api : {oboe::AudioApi::Unspecified, oboe::AudioApi::AAudio}) {
            {
                ScopedAudioDiagnostics diagnostics(api);
                OUTPUT_CONTRACT(output->start());
            }
            auto stream = AudioOutputContractAccess::stream(output);
            OUTPUT_CONTRACT(stream && stream->getState() == oboe::StreamState::Started);
            OUTPUT_CONTRACT(stream->isXRunCountSupported());
            OUTPUT_CONTRACT(output->debugInfo().xrun == 41);
            OUTPUT_CONTRACT(!stream->isXRunCountSupported());
            OUTPUT_CONTRACT(output->debugInfo().xrun == 41);
            output->stop();
            OUTPUT_CONTRACT(stream->getState() == oboe::StreamState::Closed);
            OUTPUT_CONTRACT(!AudioOutputContractAccess::stream(output));
        }
    } else {
        OUTPUT_CONTRACT(false && "Unknown output fixture scenario");
    }
#undef OUTPUT_CONTRACT
    return env->NewStringUTF("output contracts passed");
}

// ALAC decoding uses the production engine and its normal PCM capture tap.
// Returned records are native-endian {int64 pts, int32 rate, int32 channels, PCM16}.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeDecodeAlac(
    JNIEnv *env, jobject, jobjectArray packets, jlongArray timestamps, jint samplesPerFrame,
    jboolean attachSinkAfterFirstDecode, jboolean forceSoftware) {
#define ALAC_CONTRACT(condition) do { if (!(condition)) { \
    auto error = env->FindClass("java/lang/AssertionError"); \
    if (error) { env->ThrowNew(error, #condition); env->DeleteLocalRef(error); } \
    return nullptr; } } while (false)
    ALAC_CONTRACT(packets && timestamps);
    ALAC_CONTRACT(samplesPerFrame >= 0 && samplesPerFrame <= 4096);
    ALAC_CONTRACT(!attachSinkAfterFirstDecode || forceSoftware);
    const auto count = env->GetArrayLength(packets);
    ALAC_CONTRACT(count > 0 && count <= 32 && env->GetArrayLength(timestamps) == count);
    std::vector<jlong> pts(count);
    env->GetLongArrayRegion(timestamps, 0, count, pts.data());
    if (env->ExceptionCheck()) return nullptr;
    struct Capture {
        std::mutex lock;
        std::condition_variable ready;
        std::vector<std::vector<uint8_t>> records;
        size_t size() {
            std::lock_guard<std::mutex> guard(lock);
            return records.size();
        }
        bool waitFor(size_t expected) {
            std::unique_lock<std::mutex> guard(lock);
            return ready.wait_for(guard, std::chrono::seconds(3), [&] { return records.size() >= expected; });
        }
        static void receive(void *user, const int16_t *pcm, size_t samples, int64_t time, int rate, int channels) {
            std::vector<uint8_t> record(16 + samples * sizeof(int16_t));
            const int32_t sampleRate = rate, channelCount = channels;
            memcpy(record.data(), &time, 8); memcpy(record.data() + 8, &sampleRate, 4);
            memcpy(record.data() + 12, &channelCount, 4); memcpy(record.data() + 16, pcm, samples * sizeof(int16_t));
            auto &capture = *static_cast<Capture *>(user);
            {
                std::lock_guard<std::mutex> guard(capture.lock);
                capture.records.push_back(std::move(record));
            }
            capture.ready.notify_one();
        }
    } capture;
    using Engine = std::unique_ptr<AudioEngine, decltype(&audio_engine_destroy)>;
    Engine engine(audio_engine_create(std::make_shared<LogSink>(), 44100, 2), audio_engine_destroy);
    ALAC_CONTRACT(engine);
    ALAC_CONTRACT(audio_engine_configure(engine.get(), 50, 95, 0, forceSoftware, true, false, false, false));
    audio_engine_set_pcm_callback(engine.get(), Capture::receive, &capture);
    audio_engine_set_capture_enabled(engine.get(), true);
    audio_engine_on_format(engine.get(), 2, samplesPerFrame);
    // Deliver through the real RAOP table, as the receiver does. This context
    // borrows the engine and uses only audio_process (no JNI or other callbacks).
    android_callback_ctx_t callbackContext{};
    raop_callbacks_t callbacks{};
    android_callbacks_fill(&callbacks, &callbackContext);
    ALAC_CONTRACT(callbacks.cls == &callbackContext && callbacks.audio_process);
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
        audio_decode_struct input{};
        input.ct = 2;
        input.ntp_time_local = static_cast<uint64_t>(pts[i]);
        if (i == 0) {
            input.data = bytes.data(); input.data_len = length;
            callbacks.audio_process(&callbackContext, nullptr, &input); // No engine yet.
            callbackContext.audio_engine = engine.get();
            input.data = nullptr;
            callbacks.audio_process(&callbackContext, nullptr, &input);
            input.data = bytes.data(); input.data_len = 0;
            callbacks.audio_process(&callbackContext, nullptr, &input);
            input.data_len = -1;
            callbacks.audio_process(&callbackContext, nullptr, &input);
            ALAC_CONTRACT(capture.size() == 0);
            if (attachSinkAfterFirstDecode) {
                // Capture may be enabled before a consumer is attached. Decode
                // actual audio in that state, then attach the normal PCM tap.
                audio_engine_set_pcm_callback(engine.get(), nullptr, nullptr);
                input.data = bytes.data(); input.data_len = length;
                ALAC_CONTRACT(pts[i] > 1'000'000'000);
                input.ntp_time_local = static_cast<uint64_t>(pts[i] - 1'000'000'000);
                callbacks.audio_process(&callbackContext, nullptr, &input);
                ALAC_CONTRACT(capture.size() == 0);
                audio_engine_set_pcm_callback(engine.get(), Capture::receive, &capture);
                input.ntp_time_local = static_cast<uint64_t>(pts[i]);
            }
        }
        input.data = bytes.data(); input.data_len = length;
        callbacks.audio_process(&callbackContext, nullptr, &input);
        const auto expected = static_cast<size_t>(i + 1);
        ALAC_CONTRACT(forceSoftware ? capture.size() == expected : capture.waitFor(expected));
    }
    // Stop and join the optional platform drain thread before reading its records.
    engine.reset();
    ALAC_CONTRACT(capture.records.size() == static_cast<size_t>(count));
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
        bool ready;
        Context(JNIEnv *e, jobject sink) : env(e), ready(android_callbacks_init(&value, e, sink) != 0) {}
        ~Context() { if (ready) android_callbacks_destroy(&value, env); }
    } owner(env, sink);
    auto *ctx = &owner.value;
    if (env->ExceptionCheck()) return nullptr;
    if (!owner.ready) return env->NewStringUTF("callback binding failed");
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

    // A failed strdup leaves a counted null slot. Model that dependency failure
    // without exhausting device memory, retaining ownership of every live key.
    std::free(ctx->registered_keys[0]); ctx->registered_keys[0] = nullptr;
    std::free(ctx->registered_keys[15]); ctx->registered_keys[15] = nullptr;
    ctx->registered_count = 15;
    CALLBACK_CONTRACT(!cbs.check_register(ctx, "key-0") && cbs.check_register(ctx, "key-14"));
    cbs.register_client(ctx, "device", "key-14", "name");
    CALLBACK_CONTRACT(ctx->registered_count == 15); // Null slot must not hide a later duplicate.
    cbs.register_client(ctx, "device", "replacement", "name");
    CALLBACK_CONTRACT(ctx->registered_count == 16 && cbs.check_register(ctx, "replacement"));
    CALLBACK_CONTRACT(ctx->registered_keys[0] == nullptr && ctx->registered_keys[15] != nullptr);

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
    {
        // Initialize the real HTTP connection owner, without raop_start/listeners.
        // Empty keyfile uses an in-memory fixture identity and writes no key file.
        using Receiver = std::unique_ptr<raop_t, decltype(&raop_destroy)>;
        Receiver receiver(raop_init(&cbs), raop_destroy);
        CALLBACK_CONTRACT(receiver);
        CALLBACK_CONTRACT(raop_init2(receiver.get(), 0, "02:00:00:00:00:01", "") == 0);
        CALLBACK_CONTRACT(!raop_is_running(receiver.get()));
        ctx->raop = receiver.get();
        for (auto reset : {RESET_TYPE_HLS_SHUTDOWN, RESET_TYPE_HLS_EOS}) {
            android_callbacks_update_playback_info(ctx, 4, 90, 1, 1);
            cbs.video_reset(ctx, reset);
            CALLBACK_CONTRACT(ctx->playback_position == 0 && ctx->playback_duration == -1);
            CALLBACK_CONTRACT(!ctx->playback_ready && !raop_is_running(receiver.get()));
        }
        ctx->raop = nullptr;
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


// Controlled JNI dependency failures. The real callback table is exercised;
// no allocation failure is induced in ART itself.
namespace {
struct CallbackFaultFixture {
    JNINativeInterface functions{};
    JNIInvokeInterface invocation{};
    JNIEnv env{};
    JavaVM vm{};
    android_callback_ctx_t ctx{};
    raop_callbacks_t callbacks{};
    inline static thread_local CallbackFaultFixture *active = nullptr;
    int allocation = 0, failAllocation = 0, live = 0, calls = 0, writes = 0;
    int illegal = 0, clears = 0, envStatus = JNI_OK;
    int attaches = 0, detachRegistrations = 0, detaches = 0;
    bool pending = false, failCopy = false, callbackThrows = false, missingEnv = false;
    bool failDetachRegistration = false;
    void access() { if (pending || missingEnv || envStatus != JNI_OK) ++illegal; }
    jobject allocate() {
        access();
        if (++allocation == failAllocation) { pending = true; return nullptr; }
        ++live;
        return reinterpret_cast<jobject>(static_cast<uintptr_t>(allocation + 100));
    }
    static void callVoid(JNIEnv *, jobject object, jmethodID, ...) {
        auto &s = *active; s.access(); ++s.calls;
        if (object != s.ctx.callback_obj) ++s.illegal;
        if (s.callbackThrows) s.pending = true;
    }
    static jfloat callFloat(JNIEnv *env, jobject object, jmethodID method, ...) {
        callVoid(env, object, method);
        return -6.25f;
    }
    static jboolean callBoolean(JNIEnv *env, jobject object, jmethodID method, ...) {
        callVoid(env, object, method);
        return JNI_TRUE;
    }
    CallbackFaultFixture() {
        active = this; env.functions = &functions; vm.functions = &invocation;
        ctx.jvm = &vm; ctx.callback_obj = reinterpret_cast<jobject>(1);
        ctx.require_pin = 1;
        pthread_mutex_init(&ctx.playback_info_lock, nullptr);
        pthread_cond_init(&ctx.play_ready_cond, nullptr);
        functions.NewByteArray = [](JNIEnv *, jsize size) -> jbyteArray {
            if (size <= 0) ++active->illegal;
            return reinterpret_cast<jbyteArray>(active->allocate());
        };
        functions.NewStringUTF = [](JNIEnv *, const char *text) -> jstring {
            if (!text) ++active->illegal;
            return reinterpret_cast<jstring>(active->allocate());
        };
        functions.SetByteArrayRegion = [](JNIEnv *, jbyteArray array, jsize, jsize size, const jbyte *data) {
            auto &s = *active; s.access(); ++s.writes;
            if (!array || !data || size <= 0) ++s.illegal;
            if (s.failCopy) s.pending = true;
        };
        functions.CallVoidMethod = callVoid;
        functions.CallFloatMethod = callFloat;
        functions.CallBooleanMethod = callBoolean;
        functions.DeleteLocalRef = [](JNIEnv *, jobject object) {
            if (object) --active->live; // Legal while an exception is pending.
        };
        functions.ExceptionCheck = [](JNIEnv *) -> jboolean { return active->pending ? JNI_TRUE : JNI_FALSE; };
        functions.ExceptionDescribe = [](JNIEnv *) {};
        functions.ExceptionClear = [](JNIEnv *) { active->pending = false; ++active->clears; };
        invocation.GetEnv = [](JavaVM *, void **out, jint version) -> jint {
            if (version != JNI_VERSION_1_6) ++active->illegal;
            // Failure queries have a non-null output; a separate malformed-VM
            // case reports success without the environment required by JNI.
            *out = active->missingEnv ? nullptr : &active->env;
            return active->envStatus;
        };
        invocation.AttachCurrentThread = [](JavaVM *, JNIEnv **out, void *) -> jint {
            ++active->attaches;
            *out = &active->env;
            return active->failDetachRegistration ? JNI_OK : JNI_ERR;
        };
        invocation.DetachCurrentThread = [](JavaVM *vm) -> jint {
            if (vm != &active->vm) ++active->illegal;
            ++active->detaches;
            return JNI_OK;
        };
        android_callbacks_fill(&callbacks, &ctx);
    }
    ~CallbackFaultFixture() {
        pthread_cond_destroy(&ctx.play_ready_cond);
        pthread_mutex_destroy(&ctx.playback_info_lock);
        active = nullptr;
    }
    void deliver(int scenario) {
        unsigned char packet[] = {0, 0, 0, 1, 7, 8};
        switch (scenario) {
            case 0: {
                video_decode_struct data{}; data.data = packet; data.data_len = sizeof(packet);
                callbacks.video_process(&ctx, nullptr, &data); break;
            }
            case 1: callbacks.audio_set_metadata(&ctx, packet, sizeof(packet)); break;
            case 2: callbacks.audio_set_coverart(&ctx, packet, sizeof(packet)); break;
            case 3: { char pin[] = "1234"; callbacks.display_pin(&ctx, pin); break; }
            case 4: callbacks.on_video_play(&ctx, "http://127.0.0.1/fixture", 2.0f); break;
            case 5: case 6: callbacks.audio_remote_control_id(&ctx, "fixture-dacp", "fixture-remote"); break;
            case 7: { int16_t pcm[] = {1, 2, 3, 4}; android_capture_pcm(&ctx, pcm, 4, 123, 44100, 2); break; }
            case 8: {
                uint32_t start = 1, current = 0x80000000U, end = 0xffffffffU;
                callbacks.audio_set_progress(&ctx, &start, &current, &end); break;
            }
            case 9: callbacks.mirror_video_running(&ctx, true); break;
            case 10: callbacks.audio_stop_coverart_rendering(&ctx); break;
            case 11: callbacks.conn_init(&ctx); break;
            case 12: callbacks.conn_destroy(&ctx); break;
            case 13: callbacks.conn_reset(&ctx, 7); break;
            case 14: callbacks.audio_set_volume(&ctx, -12.5f); break;
            case 15: {
                unsigned char ct = 4; unsigned short spf = 1024;
                bool screen = true, media = false; uint64_t format = 0;
                callbacks.audio_get_format(&ctx, &ct, &spf, &screen, &media, &format); break;
            }
            case 16: {
                float sw = 640, sh = 480, w = 1920, h = 1080;
                callbacks.video_report_size(&ctx, &sw, &sh, &w, &h); break;
            }
            case 17: callbacks.on_video_scrub(&ctx, 8.5f); break;
            case 18: callbacks.on_video_rate(&ctx, 0.5f); break;
            case 19: callbacks.on_video_stop(&ctx); break;
            case 20: {
                playback_info_t info{};
                callbacks.on_video_acquire_playback_info(&ctx, &info); break;
            }
            case 21: {
                const auto volume = callbacks.audio_set_client_volume(&ctx);
                if (volume != (envStatus == JNI_OK && !missingEnv ? -6.25 : 0.0)) ++illegal;
                break;
            }
            case 22: {
                const bool allowed = envStatus == JNI_OK && !missingEnv && !failAllocation && !callbackThrows;
                if (callbacks.conn_admit(&ctx, "fixture-peer") != allowed || pending != (failAllocation != 0)) ++illegal;
                break;
            }
            default: ++illegal; break;
        }
    }
};
}
extern "C" int __real_pthread_setspecific(pthread_key_t key, const void *value);
extern "C" int __wrap_pthread_setspecific(pthread_key_t key, const void *value) {
    auto *fixture = CallbackFaultFixture::active;
    if (fixture && fixture->failDetachRegistration && value == &fixture->vm) {
        ++fixture->detachRegistrations;
        return ENOMEM;
    }
    return __real_pthread_setspecific(key, value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeCallbackFaultContracts(JNIEnv *env, jobject, jint scenario, jint failure) {
#define FAULT_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    FAULT_CONTRACT(scenario >= 0 && scenario <= 22 && failure >= 0 && failure <= 7);
    FAULT_CONTRACT(failure != 6 || scenario == 7);
    FAULT_CONTRACT(failure != 7 || scenario == 8);
    FAULT_CONTRACT(scenario != 22 || failure != 1);
    // Scalar/control callbacks allocate no JNI objects and copy no byte arrays.
    FAULT_CONTRACT(scenario <= 7 || scenario == 22 || failure >= 2);
    CallbackFaultFixture fixture;
    if (failure == 0) fixture.failAllocation = scenario == 6 ? 2 : 1;
    if (failure == 1) fixture.failCopy = true;
    if (failure == 2) fixture.envStatus = JNI_ERR;
    if (failure == 3 || failure == 7) fixture.envStatus = JNI_EDETACHED;
    if (failure == 4) fixture.callbackThrows = true;
    if (failure == 5) fixture.missingEnv = true;
    if (failure == 6) fixture.pending = true;
    if (failure == 7) fixture.failDetachRegistration = true;
    const int delivered = failure == 4 || failure == 6 ? 1 : 0;
    fixture.deliver(scenario);
    FAULT_CONTRACT(fixture.illegal == 0);
    FAULT_CONTRACT(fixture.live == 0);
    FAULT_CONTRACT(fixture.calls == delivered);
    if (failure == 0) FAULT_CONTRACT(fixture.allocation == (scenario == 6 ? 2 : 1));
    if (failure == 1) FAULT_CONTRACT(fixture.writes == 1);
    if (failure == 5) FAULT_CONTRACT(fixture.allocation == 0 && fixture.writes == 0);
    if (scenario >= 8 && scenario <= 21) FAULT_CONTRACT(fixture.allocation == 0 && fixture.writes == 0);
    if (scenario == 22) FAULT_CONTRACT(fixture.clears == (failure == 4 ? 1 : 0));
    if (failure == 6) FAULT_CONTRACT(!fixture.pending && fixture.clears == 1 && fixture.writes == 1);
    if (failure == 7) FAULT_CONTRACT(fixture.attaches == 1 && fixture.detachRegistrations == 1 && fixture.detaches == 1);
    fixture.failAllocation = 0; fixture.failCopy = false;
    fixture.callbackThrows = false; fixture.missingEnv = false; fixture.envStatus = JNI_OK;
    fixture.failDetachRegistration = false;
    // PCM clears its own exception; the other callbacks recover at _get_env.
    fixture.deliver(scenario);
    FAULT_CONTRACT(fixture.illegal == 0);
    FAULT_CONTRACT(fixture.live == 0);
    FAULT_CONTRACT(fixture.calls == delivered + 1);
    FAULT_CONTRACT(!fixture.pending);
    return env->NewStringUTF("callback fault contracts passed");
#undef FAULT_CONTRACT
}

// Controlled dependency faults for the real callback initializer.
namespace {
struct CallbackInitFixture {
    JNINativeInterface functions{};
    JNIEnv env{};
    JavaVM vm{};
    android_callback_ctx_t ctx{};
    inline static thread_local CallbackInitFixture *active = nullptr;
    int mode, illegal = 0, globalCalls = 0, classCalls = 0, methodCalls = 0;
    int globalLive = 0, classLive = 0;
    bool pending = false, initialized = false;
    int result = -1;
    explicit CallbackInitFixture(int failure) : mode(failure) {
        active = this; env.functions = &functions;
        functions.GetJavaVM = [](JNIEnv *, JavaVM **out) -> jint {
            auto &s = *active; s.access();
            *out = s.mode == 27 ? nullptr : &s.vm;
            return s.mode == 1 ? JNI_ERR : JNI_OK;
        };
        functions.NewGlobalRef = [](JNIEnv *, jobject object) -> jobject {
            auto &s = *active; s.access(); ++s.globalCalls;
            if (object != receiver()) ++s.illegal;
            if (s.mode == 2 || s.mode == 26) { s.pending = s.mode == 2; return nullptr; }
            ++s.globalLive; return object;
        };
        functions.GetObjectClass = [](JNIEnv *, jobject object) -> jclass {
            auto &s = *active; s.access(); ++s.classCalls;
            if (object != receiver()) ++s.illegal;
            if (s.mode == 3) { s.pending = true; return nullptr; }
            ++s.classLive; return klass();
        };
        functions.GetMethodID = [](JNIEnv *, jclass type, const char *name, const char *signature) -> jmethodID {
            auto &s = *active; s.access();
            const int index = s.methodCalls++;
            if (type != klass() || index >= 22 || std::strcmp(name, methods[index].first) ||
                std::strcmp(signature, methods[index].second)) ++s.illegal;
            if (s.mode == 4 + index) { s.pending = true; return nullptr; }
            return method(index);
        };
        functions.DeleteGlobalRef = [](JNIEnv *, jobject object) {
            auto &s = *active;
            if (object != receiver() || s.globalLive != 1) ++s.illegal;
            --s.globalLive;
        };
        functions.DeleteLocalRef = [](JNIEnv *, jobject object) {
            auto &s = *active;
            if (object != klass() || s.classLive != 1) ++s.illegal;
            --s.classLive;
        };
        functions.ExceptionCheck = [](JNIEnv *) -> jboolean { return active->pending ? JNI_TRUE : JNI_FALSE; };
        functions.ExceptionClear = [](JNIEnv *) { active->pending = false; };
        functions.ExceptionDescribe = [](JNIEnv *) {};
    }
    void access() { if (pending) ++illegal; }
    static jobject receiver() { return reinterpret_cast<jobject>(1); }
    static jclass klass() { return reinterpret_cast<jclass>(2); }
    static jmethodID method(int index) { return reinterpret_cast<jmethodID>(static_cast<uintptr_t>(index + 10)); }
    inline static const std::array<std::pair<const char *, const char *>, 22> methods{{
        {"onConnectionRequest", "(Ljava/lang/String;)Z"}, {"onVideoData", "([BJZ)V"},
        {"onAudioPcm", "([BJII)V"}, {"onAudioFormat", "(IIZ)V"}, {"onVideoSize", "(FFFF)V"},
        {"onClientVolume", "(F)V"}, {"onClientVolumeQuery", "()F"}, {"onAudioTeardown", "()V"},
        {"onConnectionInit", "()V"}, {"onConnectionDestroy", "()V"}, {"onConnectionReset", "(I)V"},
        {"onDisplayPin", "(Ljava/lang/String;)V"}, {"onMetadata", "([B)V"}, {"onCoverArt", "([B)V"},
        {"onProgress", "(JJJ)V"}, {"onDacpId", "(Ljava/lang/String;Ljava/lang/String;)V"},
        {"onMirrorRunning", "(Z)V"}, {"onVideoPlay", "(Ljava/lang/String;F)V"},
        {"onVideoScrub", "(F)V"}, {"onVideoRate", "(F)V"}, {"onVideoStop", "()V"},
        {"onVideoSessionPoll", "()V"}
    }};
    void initialize() {
        result = android_callbacks_init(&ctx, &env, receiver());
        initialized = result == 1;
    }
    void destroy() {
        if (initialized) { android_callbacks_destroy(&ctx, &env); initialized = false; }
    }
    ~CallbackInitFixture() { destroy(); active = nullptr; }
};
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_DebugCoverage_nativeCallbackInitContracts(JNIEnv *env, jobject, jint mode) {
#define INIT_CONTRACT(condition) do { if (!(condition)) return env->NewStringUTF(#condition); } while (false)
    INIT_CONTRACT(mode >= 0 && mode <= 27);
    CallbackInitFixture fixture(mode);
    fixture.initialize();
    INIT_CONTRACT(fixture.illegal == 0);
    INIT_CONTRACT(fixture.classLive == 0);
    if (mode == 0) {
        INIT_CONTRACT(fixture.result == 1);
        INIT_CONTRACT(fixture.globalLive == 1 && fixture.methodCalls == 22);
        INIT_CONTRACT(!fixture.pending && fixture.ctx.h265_enabled == 1 && fixture.ctx.require_pin == 0);
        INIT_CONTRACT(fixture.ctx.registered_count == 0 && !fixture.ctx.audio_engine);
        INIT_CONTRACT(fixture.ctx.playback_duration == 0 && fixture.ctx.play_ready == 0);
        std::array<jmethodID, 22> ids{fixture.ctx.on_connection_request, fixture.ctx.on_video_data,
            fixture.ctx.on_audio_pcm, fixture.ctx.on_audio_format, fixture.ctx.on_video_size,
            fixture.ctx.on_client_volume, fixture.ctx.on_client_volume_query, fixture.ctx.on_audio_teardown,
            fixture.ctx.on_conn_init, fixture.ctx.on_conn_destroy, fixture.ctx.on_conn_reset,
            fixture.ctx.on_display_pin, fixture.ctx.on_metadata, fixture.ctx.on_coverart,
            fixture.ctx.on_progress, fixture.ctx.on_dacp_id, fixture.ctx.on_mirror_running,
            fixture.ctx.on_video_play, fixture.ctx.on_video_scrub, fixture.ctx.on_video_rate,
            fixture.ctx.on_video_stop, fixture.ctx.on_video_session_poll};
        for (int index = 0; index < 22; ++index) INIT_CONTRACT(ids[index] == CallbackInitFixture::method(index));
        INIT_CONTRACT(pthread_mutex_trylock(&fixture.ctx.playback_info_lock) == 0);
        INIT_CONTRACT(pthread_mutex_unlock(&fixture.ctx.playback_info_lock) == 0);
        INIT_CONTRACT(pthread_cond_signal(&fixture.ctx.play_ready_cond) == 0);
        fixture.destroy();
        INIT_CONTRACT(fixture.globalLive == 0 && !fixture.ctx.callback_obj);
    } else {
        INIT_CONTRACT(fixture.result == 0);
        INIT_CONTRACT(fixture.globalLive == 0 && !fixture.ctx.callback_obj && !fixture.ctx.jvm);
        INIT_CONTRACT(fixture.pending == (mode != 1 && mode != 26 && mode != 27));
        if (mode == 1 || mode == 27) {
            INIT_CONTRACT(fixture.globalCalls == 0 && fixture.classCalls == 0 && fixture.methodCalls == 0);
        } else if (mode == 2 || mode == 26) {
            INIT_CONTRACT(fixture.globalCalls == 1 && fixture.classCalls == 0 && fixture.methodCalls == 0);
        } else if (mode == 3) {
            INIT_CONTRACT(fixture.classCalls == 1 && fixture.methodCalls == 0);
        } else {
            INIT_CONTRACT(fixture.methodCalls == mode - 3);
        }
    }
    return env->NewStringUTF("callback init contracts passed");
#undef INIT_CONTRACT
}
