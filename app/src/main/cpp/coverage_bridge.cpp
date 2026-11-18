// Coverage-only JNI hook. Never linked in production builds.
#include <jni.h>
#include <algorithm>
#include <array>
#include <memory>
#include <cstring>
#include <map>
#include <string>
#include <oboe/Oboe.h>
#include "audio_engine.h"
#include "log_sink.h"
extern "C" {
#include "dnssd.h"
#include "android_dnssd_shim.h"
}
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
