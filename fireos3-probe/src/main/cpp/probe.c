#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "llhttp.h"
#include "raop.h"

static int message_complete(llhttp_t *parser) {
    int *complete = parser->data;
    *complete = 1;
    return 0;
}

static void discard_audio(void *context, raop_ntp_t *ntp, audio_decode_struct *audio) {}
static void discard_video(void *context, raop_ntp_t *ntp, video_decode_struct *video) {}

static int allow_local_peer(void *context, const char *peer) {
    return strcmp(peer, "127.0.0.1") == 0;
}

typedef struct {
    raop_t *receiver;
    dnssd_t *discovery;
} receiver_probe_t;

static void destroy_receiver(receiver_probe_t *probe) {
    raop_destroy(probe->receiver);
    if (probe->discovery) {
        dnssd_destroy(probe->discovery);
    }
    free(probe);
}

JNIEXPORT jlong JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_startReceiver(JNIEnv *env, jclass type, jstring key_path) {
    receiver_probe_t *probe = calloc(1, sizeof(*probe));
    if (!probe) return 0;
    const char *path = (*env)->GetStringUTFChars(env, key_path, NULL);
    if (!path) {
        free(probe);
        return 0;
    }
    raop_callbacks_t callbacks = {
        .audio_process = discard_audio,
        .video_process = discard_video,
        .conn_admit = allow_local_peer,
    };
    raop_t *receiver = raop_init(&callbacks);
    probe->receiver = receiver;
    int initialized = receiver && raop_init2(receiver, 1, "02:00:00:00:00:17", path) == 0;
    (*env)->ReleaseStringUTFChars(env, key_path, path);

    if (!initialized) {
        destroy_receiver(probe);
        return 0;
    }
    const char name[] = "Buzin Cast API 17 Probe";
    const char address[] = { 2, 0, 0, 0, 0, 0x17 };
    int error = 0;
    probe->discovery = dnssd_init(name, sizeof(name) - 1, address, sizeof(address), &error, 0);
    if (!probe->discovery) {
        destroy_receiver(probe);
        return 0;
    }
    raop_set_dnssd(receiver, probe->discovery);
    unsigned short port = 0;
    if (raop_start_httpd(receiver, &port) < 0 || !port) {
        destroy_receiver(probe);
        return 0;
    }
    raop_set_port(receiver, port);
    return (jlong)(intptr_t)probe;
}

JNIEXPORT jint JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_receiverPort(JNIEnv *env, jclass type, jlong handle) {
    receiver_probe_t *probe = (receiver_probe_t *)(intptr_t)handle;
    return raop_get_port(probe->receiver);
}

JNIEXPORT void JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_stopReceiver(JNIEnv *env, jclass type, jlong handle) {
    destroy_receiver((receiver_probe_t *)(intptr_t)handle);
}

JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_checkNativeParser(JNIEnv *env, jclass activity) {
    const char request[] = "GET /info HTTP/1.1\r\nHost: localhost\r\n\r\n";
    int complete = 0;
    llhttp_t parser;
    llhttp_settings_t settings;
    llhttp_settings_init(&settings);
    settings.on_message_complete = message_complete;
    llhttp_init(&parser, HTTP_REQUEST, &settings);
    parser.data = &complete;
    return llhttp_execute(&parser, request, sizeof(request) - 1) == HPE_OK
        && complete && llhttp_get_method(&parser) == HTTP_GET;
}

/* Replay the same frame payload, timestamp, and codec flag as the receiver's
 * video_process callback. The sample source stands in for a live RAOP session. */
JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_replayVideoFrame(
        JNIEnv *env, jclass activity, jobject callback, jobject input, jint length, jlong time_ns) {
    unsigned char *bytes = (*env)->GetDirectBufferAddress(env, input);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, input);
    if (!bytes || length <= 0 || length > capacity || time_ns < 0) {
        return JNI_FALSE;
    }
    video_decode_struct frame = {
        .data = bytes,
        .data_len = length,
        .ntp_time_local = (uint64_t)time_ns,
        .is_h265 = false,
    };
    jclass type = (*env)->GetObjectClass(env, callback);
    if (!type) {
        return JNI_FALSE;
    }
    jmethodID on_video_data = (*env)->GetMethodID(env, type, "onVideoData", "([BJZ)V");
    if (!on_video_data) {
        return JNI_FALSE;
    }
    jbyteArray payload = (*env)->NewByteArray(env, frame.data_len);
    if (!payload) {
        return JNI_FALSE;
    }
    (*env)->SetByteArrayRegion(env, payload, 0, frame.data_len, (const jbyte *)frame.data);
    if ((*env)->ExceptionCheck(env)) {
        return JNI_FALSE;
    }
    (*env)->CallVoidMethod(env, callback, on_video_data, payload,
                           (jlong)frame.ntp_time_local, (jboolean)frame.is_h265);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}
