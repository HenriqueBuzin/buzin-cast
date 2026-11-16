// Coverage-only JNI hook. Never linked in production builds.
#include <jni.h>
#include <algorithm>
#include <array>
#include <memory>
#include <oboe/Oboe.h>
#include "audio_engine.h"
#include "log_sink.h"
extern "C" void __llvm_profile_set_filename(const char *);
extern "C" int __llvm_profile_write_file(void);
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
