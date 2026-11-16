#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "llhttp.h"
#include "raop.h"

static int message_complete(llhttp_t *parser) {
    int *complete = parser->data;
    *complete = 1;
    return 0;
}

static void discard_audio(void *context, raop_ntp_t *ntp, audio_decode_struct *audio) {}

static int allow_local_peer(void *context, const char *peer) {
    return strcmp(peer, "127.0.0.1") == 0;
}

typedef struct {
    raop_t *receiver;
    dnssd_t *discovery;
    JavaVM *vm;
    jobject video_callback;
    jmethodID on_video_data;
} receiver_probe_t;

/* Network callbacks run on UxPlay workers, not the thread that started JNI. */
static void forward_video(void *context, raop_ntp_t *ntp, video_decode_struct *video) {
    receiver_probe_t *probe = context;
    if (!probe->video_callback || !video || !video->data || video->data_len <= 0 ||
        video->data_len > 4 * 1024 * 1024) return;
    JNIEnv *env = NULL;
    bool attached = false;
    if ((*probe->vm)->GetEnv(probe->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*probe->vm)->AttachCurrentThread(probe->vm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    if (!env) return;
    jbyteArray payload = (*env)->NewByteArray(env, video->data_len);
    if (payload) {
        (*env)->SetByteArrayRegion(env, payload, 0, video->data_len, (const jbyte *)video->data);
        if (!(*env)->ExceptionCheck(env)) {
            (*env)->CallVoidMethod(env, probe->video_callback, probe->on_video_data, payload,
                                  (jlong)video->ntp_time_local, (jboolean)video->is_h265);
        }
        (*env)->DeleteLocalRef(env, payload);
    }
    // Never leave a pending Java exception on an UxPlay worker.
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*probe->vm)->DetachCurrentThread(probe->vm);
}

static void destroy_receiver(receiver_probe_t *probe) {
    if (!probe) return;
    // raop_destroy stops/joins workers before releasing the callback reference.
    raop_destroy(probe->receiver);
    if (probe->discovery) {
        dnssd_destroy(probe->discovery);
    }
    if (probe->video_callback) {
        JNIEnv *env = NULL;
        if ((*probe->vm)->GetEnv(probe->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
            (*env)->DeleteGlobalRef(env, probe->video_callback);
        }
    }
    free(probe);
}

JNIEXPORT jlong JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_startReceiver(JNIEnv *env, jclass type, jstring key_path, jobject video_callback) {
    receiver_probe_t *probe = calloc(1, sizeof(*probe));
    if (!probe) return 0;
    if ((*env)->GetJavaVM(env, &probe->vm) != JNI_OK) { free(probe); return 0; }
    if (video_callback) {
        jclass callback_type = (*env)->GetObjectClass(env, video_callback);
        probe->on_video_data = (*env)->GetMethodID(env, callback_type, "onVideoData", "([BJZ)V");
        (*env)->DeleteLocalRef(env, callback_type);
        if (!probe->on_video_data) { free(probe); return 0; }
        probe->video_callback = (*env)->NewGlobalRef(env, video_callback);
        if (!probe->video_callback) { free(probe); return 0; }
    }
    const char *path = (*env)->GetStringUTFChars(env, key_path, NULL);
    if (!path) {
        destroy_receiver(probe);
        return 0;
    }
    raop_callbacks_t callbacks = {
        .cls = probe,
        .audio_process = discard_audio,
        .video_process = forward_video,
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

/* Exercise the exact registered callback while the real RTSP receiver is alive. */
typedef struct { receiver_probe_t *probe; video_decode_struct frame; } replay_t;

static void *replay_on_worker(void *context) {
    replay_t *replay = context;
    forward_video(replay->probe, NULL, &replay->frame);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_ProbeActivity_replayReceiverVideo(
        JNIEnv *env, jclass type, jlong handle, jobject input, jint length, jlong time_ns) {
    receiver_probe_t *probe = (receiver_probe_t *)(intptr_t)handle;
    unsigned char *bytes = (*env)->GetDirectBufferAddress(env, input);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, input);
    if (!probe || !probe->video_callback || !bytes || length <= 0 || length > capacity || time_ns < 0) return JNI_FALSE;
    replay_t replay = { .probe = probe, .frame = { .data = bytes, .data_len = length,
        .ntp_time_local = (uint64_t)time_ns, .is_h265 = false } };
    pthread_t worker;
    if (pthread_create(&worker, NULL, replay_on_worker, &replay) != 0) return JNI_FALSE;
    pthread_join(worker, NULL);
    return JNI_TRUE;
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
