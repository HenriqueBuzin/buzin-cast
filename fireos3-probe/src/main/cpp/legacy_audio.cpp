#include "audio_decoder.h"
#include <new>

// Owned by LegacyAudioDecoder. Java serializes every access, including close.
// Reuse the production codecs and jitter buffer; no libmediandk or Oboe on API17.
struct LegacyAudio {
    LogSink log;
    LatencyReporter latency{"legacy", log};
    TimelineBuffer timeline{44100, 2, 20, 95};
    std::unique_ptr<Decoder> decoder;
    std::vector<uint8_t> packet;
    std::vector<int16_t> pcm;
    int64_t decodedSamples = 0;
    int peak = 0;

    LegacyAudio() {
        timeline.capturePcm = [this](const int16_t *data, size_t count, int64_t) {
            decodedSamples += count;
            for (size_t i = 0; i < count; ++i) peak = std::max(peak, std::abs((int)data[i]));
        };
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_io_buzincast_fireos3probe_LegacyAudioDecoder_create(JNIEnv *, jclass, jint ct, jint spf) {
    if ((ct == CT_ALAC && spf != 352) ||
        (ct == CT_AAC_LC && spf != 960 && spf != 1024) ||
        (ct == CT_AAC_ELD && spf != 480 && spf != 512)) return 0;
    auto audio = std::make_unique<LegacyAudio>();
    audio->decoder = makeDecoder(ct, spf, 44100, 2, audio->timeline, audio->latency,
                                 audio->log, true, false, false);
    return audio->decoder ? reinterpret_cast<jlong>(audio.release()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_LegacyAudioDecoder_decodePacket(JNIEnv *env, jclass, jlong handle,
                                                            jbyteArray bytes, jlong pts) {
    auto *audio = reinterpret_cast<LegacyAudio *>(handle);
    if (!audio || !bytes) return JNI_FALSE;
    const jsize count = env->GetArrayLength(bytes);
    if (count <= 0 || count > 64 * 1024 || pts <= 0) return JNI_FALSE;
    // FFmpeg bit readers require zero padding beyond the encoded payload.
    audio->packet.assign(count + AV_INPUT_BUFFER_PADDING_SIZE, 0);
    env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte *>(audio->packet.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return audio->decoder->decode(audio->packet.data(), count, pts);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_buzincast_fireos3probe_LegacyAudioDecoder_readPcm(JNIEnv *env, jclass, jlong handle,
                                                       jshortArray output, jint frames) {
    auto *audio = reinterpret_cast<LegacyAudio *>(handle);
    if (!audio || !output || frames <= 0 || frames > 4096 || env->GetArrayLength(output) < frames * 2)
        return -1;
    audio->pcm.resize(frames * 2);
    audio->timeline.read(audio->pcm.data(), frames);
    env->SetShortArrayRegion(output, 0, frames * 2, audio->pcm.data());
    return env->ExceptionCheck() ? -1 : frames * 2;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_buzincast_fireos3probe_LegacyAudioDecoder_statistics(JNIEnv *env, jclass, jlong handle) {
    auto *audio = reinterpret_cast<LegacyAudio *>(handle);
    if (!audio) return nullptr;
    const jlong values[] = {audio->decodedSamples, audio->peak};
    jlongArray result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_buzincast_fireos3probe_LegacyAudioDecoder_destroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<LegacyAudio *>(handle);
}
