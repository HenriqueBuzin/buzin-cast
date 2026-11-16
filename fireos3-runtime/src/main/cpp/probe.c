#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdio.h>
#include <arpa/inet.h>
#include "llhttp.h"
#include "raop.h"
#include "android_dnssd_shim.h"

static int message_complete(llhttp_t *parser) {
    int *complete = parser->data;
    *complete = 1;
    return 0;
}

typedef struct {
    raop_t *receiver;
    dnssd_t *discovery;
    JavaVM *vm;
    jobject video_callback;
    jmethodID on_video_data;
    jmethodID on_video_reset;
    jmethodID on_audio_data, on_audio_format, on_audio_flush, on_audio_volume, get_audio_volume;
    jmethodID on_dacp_id, on_sender_stopped;
    jmethodID on_metadata,on_artwork,on_progress;
    bool audio_enabled;
    uint32_t network;
    uint32_t netmask;
} receiver_probe_t;

static JNIEnv *callback_env(receiver_probe_t *probe, bool *attached) {
    JNIEnv *env = NULL;
    *attached = false;
    if (!probe->video_callback) return NULL;
    if ((*probe->vm)->GetEnv(probe->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*probe->vm)->AttachCurrentThread(probe->vm, &env, NULL) != JNI_OK) return NULL;
        *attached = true;
    }
    return env;
}
static void finish_callback(receiver_probe_t *probe, JNIEnv *env, bool attached) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*probe->vm)->DetachCurrentThread(probe->vm);
}
static void forward_audio(void *context, raop_ntp_t *ntp, audio_decode_struct *audio) {
    receiver_probe_t *probe = context;
    if (!probe->audio_enabled || !audio || !audio->data || audio->data_len <= 0 || audio->data_len > 64*1024) return;
    bool attached;
    JNIEnv *env = callback_env(probe, &attached);
    if (!env) return;
    jbyteArray payload = (*env)->NewByteArray(env, audio->data_len);
    if (payload) {
        (*env)->SetByteArrayRegion(env, payload, 0, audio->data_len, (const jbyte *)audio->data);
        if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, probe->video_callback, probe->on_audio_data,
                payload, (jint)audio->ct, (jlong)audio->ntp_time_local);
        (*env)->DeleteLocalRef(env, payload);
    }
    finish_callback(probe, env, attached);
}
static void audio_format(void *context, unsigned char *ct, unsigned short *spf, bool *screen, bool *media, uint64_t *format) {
    receiver_probe_t *probe = context;
    bool attached;
    JNIEnv *env = callback_env(probe, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, probe->video_callback, probe->on_audio_format, (jint)*ct, (jint)*spf);
    finish_callback(probe, env, attached);
}
static void audio_flush(void *context) {
    receiver_probe_t *probe = context;
    bool attached;
    JNIEnv *env = callback_env(probe, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, probe->video_callback, probe->on_audio_flush);
    finish_callback(probe, env, attached);
}
static void audio_volume(void *context, float volume) {
    receiver_probe_t *probe = context;
    bool attached;
    JNIEnv *env = callback_env(probe, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, probe->video_callback, probe->on_audio_volume, (jfloat)volume);
    finish_callback(probe, env, attached);
}

static int allow_local_peer(void *context, const char *peer) {
    receiver_probe_t *probe = context;
    struct in_addr remote;
    if (!strcmp(peer, "::1")) return 1;
    if (inet_pton(AF_INET, peer, &remote) != 1) {
        struct in6_addr v6;
        if (inet_pton(AF_INET6, peer, &v6) != 1 || !IN6_IS_ADDR_V4MAPPED(&v6)) return 0;
        memcpy(&remote, v6.s6_addr + 12, sizeof(remote));
    }
    uint32_t address = ntohl(remote.s_addr);
    if ((address >> 24) == 127) return 1;
    return probe->netmask && (address & probe->netmask) == probe->network;
}

static void noop(void *context) {}
static void control_identity(void *context,const char *id,const char *token) {
    receiver_probe_t *probe=context;
    if(!id || !token || strnlen(id,512)>=512 || strnlen(token,512)>=512)return;
    bool attached;JNIEnv *env=callback_env(probe,&attached);if(!env)return;
    jstring name=(*env)->NewStringUTF(env,id);
    jstring secret=(*env)->ExceptionCheck(env)?NULL:(*env)->NewStringUTF(env,token);
    if(name && secret)(*env)->CallVoidMethod(env,probe->video_callback,probe->on_dacp_id,name,secret);
    if(name)(*env)->DeleteLocalRef(env,name);
    if(secret)(*env)->DeleteLocalRef(env,secret);
    finish_callback(probe,env,attached);
}
static void export_control_identity(void *context,const char *token,const char *id) {control_identity(context,id,token);}
static void sender_stopped(void *context) {
    receiver_probe_t *probe=context;bool attached;JNIEnv *env=callback_env(probe,&attached);if(!env)return;
    (*env)->CallVoidMethod(env,probe->video_callback,probe->on_sender_stopped);
    finish_callback(probe,env,attached);
}
static void media_bytes(receiver_probe_t *probe,const void *bytes,int length,jmethodID method,int limit) {
    if(length<0 || length>limit || (!bytes && length))return;
    bool attached;JNIEnv *env=callback_env(probe,&attached);if(!env)return;
    jbyteArray payload=(*env)->NewByteArray(env,length);
    if(payload) {
        if(length)(*env)->SetByteArrayRegion(env,payload,0,length,bytes);
        if(!(*env)->ExceptionCheck(env))(*env)->CallVoidMethod(env,probe->video_callback,method,payload);
        (*env)->DeleteLocalRef(env,payload);
    }
    finish_callback(probe,env,attached);
}
static void metadata(void *context,const void *bytes,int length) {receiver_probe_t *probe=context;media_bytes(probe,bytes,length,probe->on_metadata,64*1024);}
static void artwork(void *context,const void *bytes,int length) {receiver_probe_t *probe=context;media_bytes(probe,bytes,length,probe->on_artwork,4*1024*1024);}
static void progress(void *context,uint32_t *start,uint32_t *current,uint32_t *end) {
    if(!start || !current || !end)return;
    receiver_probe_t *probe=context;bool attached;JNIEnv *env=callback_env(probe,&attached);if(!env)return;
    (*env)->CallVoidMethod(env,probe->video_callback,probe->on_progress,(jlong)*start,(jlong)*current,(jlong)*end);
    finish_callback(probe,env,attached);
}
static double client_volume(void *context) {
    receiver_probe_t *probe = context;
    bool attached;
    JNIEnv *env = callback_env(probe, &attached);
    if (!env) return 0;
    double result = (*env)->CallDoubleMethod(env, probe->video_callback, probe->get_audio_volume);
    finish_callback(probe, env, attached);
    return result;
}
static int set_codec(void *context, video_codec_t codec) { return codec == VIDEO_CODEC_H264 ? 0 : -1; }

static void reset_video(void *context, reset_type_t reason) {
    if (reason != RESET_TYPE_NOHOLD && reason != RESET_TYPE_RTP_SHUTDOWN &&
        reason != RESET_TYPE_RTP_TO_HLS_TEARDOWN) return;
    receiver_probe_t *probe = context;
    if (probe->audio_enabled) audio_flush(context);
    if (!probe->video_callback) return;
    JNIEnv *env = NULL;
    bool attached = false;
    if ((*probe->vm)->GetEnv(probe->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*probe->vm)->AttachCurrentThread(probe->vm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    if (env) {
        (*env)->CallVoidMethod(env, probe->video_callback, probe->on_video_reset);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (attached) (*probe->vm)->DetachCurrentThread(probe->vm);
}

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
    if (probe->receiver) raop_destroy(probe->receiver);
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

static jlong create_receiver(JNIEnv *env, jstring key_path, jobject video_callback,
                             const char *device_id, const char *network_address, int prefix,
                             const char *service_name, int requested_port, int width,int height,int fps) {
    if (requested_port < 0 || requested_port > 65535 || width<16 || width>4096 || height<16 || height>4096 || fps<1 || fps>240) return 0;
    receiver_probe_t *probe = calloc(1, sizeof(*probe));
    if (!probe) return 0;
    unsigned int octets[6];
    if (strlen(device_id) != 17 || sscanf(device_id, "%2x:%2x:%2x:%2x:%2x:%2x", &octets[0], &octets[1],
        &octets[2], &octets[3], &octets[4], &octets[5]) != 6) { free(probe); return 0; }
    if (network_address) {
        struct in_addr address;
        if (prefix < 1 || prefix > 32 || inet_pton(AF_INET, network_address, &address) != 1) { free(probe); return 0; }
        probe->netmask = UINT32_MAX << (32 - prefix);
        probe->network = ntohl(address.s_addr) & probe->netmask;
    }
    if ((*env)->GetJavaVM(env, &probe->vm) != JNI_OK) { free(probe); return 0; }
    if (video_callback) {
        jclass callback_type = (*env)->GetObjectClass(env, video_callback);
        probe->on_video_data = (*env)->GetMethodID(env, callback_type, "onVideoData", "([BJZ)V");
        if (probe->on_video_data) probe->on_video_reset = (*env)->GetMethodID(env, callback_type, "onVideoReset", "()V");
        probe->on_audio_data = (*env)->GetMethodID(env, callback_type, "onAudioData", "([BIJ)V");
        probe->on_audio_format = (*env)->GetMethodID(env, callback_type, "onAudioFormat", "(II)V");
        probe->on_audio_flush = (*env)->GetMethodID(env, callback_type, "onAudioFlush", "()V");
        probe->on_audio_volume = (*env)->GetMethodID(env, callback_type, "onAudioVolume", "(F)V");
        probe->get_audio_volume = (*env)->GetMethodID(env, callback_type, "getAudioVolume", "()D");
        probe->on_dacp_id = (*env)->GetMethodID(env, callback_type, "onDacpId", "(Ljava/lang/String;Ljava/lang/String;)V");
        probe->on_sender_stopped = (*env)->GetMethodID(env, callback_type, "onSenderStopped", "()V");
        probe->on_metadata=(*env)->GetMethodID(env,callback_type,"onMetadata","([B)V");
        probe->on_artwork=(*env)->GetMethodID(env,callback_type,"onArtwork","([B)V");
        probe->on_progress=(*env)->GetMethodID(env,callback_type,"onProgress","(JJJ)V");
        jmethodID enabled = (*env)->GetMethodID(env, callback_type, "audioEnabled", "()Z");
        if (enabled) probe->audio_enabled = (*env)->CallBooleanMethod(env, video_callback, enabled);
        (*env)->DeleteLocalRef(env, callback_type);
        if (!probe->on_video_data || !probe->on_video_reset || !probe->on_audio_data || !probe->on_audio_format ||
            !probe->on_audio_flush || !probe->on_audio_volume || !probe->get_audio_volume || !probe->on_dacp_id || !probe->on_sender_stopped ||
            !probe->on_metadata || !probe->on_artwork || !probe->on_progress || !enabled ||
            (*env)->ExceptionCheck(env)) { free(probe); return 0; }
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
        .audio_process = forward_audio,
        .audio_get_format = audio_format,
        .audio_set_volume = audio_volume,
        .video_process = forward_video,
        .conn_admit = allow_local_peer,
        .video_set_codec = set_codec,
        .video_pause = noop,
        .video_resume = noop,
        .video_reset = reset_video,
        .video_flush = noop,
        .audio_flush = audio_flush,
        .audio_set_client_volume = client_volume,
        .audio_remote_control_id = control_identity,
        .export_dacp = export_control_identity,
        .audio_stop_coverart_rendering = sender_stopped,
        .audio_set_metadata = metadata,
        .audio_set_coverart = artwork,
        .audio_set_progress = progress,
        .conn_feedback = noop,
    };
    raop_t *receiver = raop_init(&callbacks);
    probe->receiver = receiver;
    int initialized = receiver && raop_init2(receiver, 1, device_id, path) == 0;
    (*env)->ReleaseStringUTFChars(env, key_path, path);

    if (!initialized) {
        destroy_receiver(probe);
        return 0;
    }
    char name[64];
    if (service_name) snprintf(name,sizeof(name),"%s",service_name);
    else snprintf(name, sizeof(name), "Buzin Cast API17 %02X%02X", octets[4], octets[5]);
    const char address[] = { octets[0], octets[1], octets[2], octets[3], octets[4], octets[5] };
    int error = 0;
    probe->discovery = dnssd_init(name, strlen(name), address, sizeof(address), &error, 0);
    if (!probe->discovery) {
        destroy_receiver(probe);
        return 0;
    }
    raop_set_dnssd(receiver, probe->discovery);
    // Enable audio only when a bounded Java queue and player belong to this session.
    const int unsupported[] = { 0, 1, 3, 4, 5, 8, 9, 11, 13, 15, 16, 17, 18, 19, 20, 21, 42 };
    for (unsigned i = 0; i < sizeof(unsupported)/sizeof(unsupported[0]); i++)
        dnssd_set_airplay_features(probe->discovery, unsupported[i], 0);
    if (probe->audio_enabled) {
        const int audio_bits[] = {9, 19, 20, 21};
        for (unsigned i=0; i<sizeof(audio_bits)/sizeof(audio_bits[0]); i++)
            dnssd_set_airplay_features(probe->discovery, audio_bits[i], 1);
        android_dnssd_set_compressed_audio_only(probe->discovery);
        android_dnssd_enable_metadata(probe->discovery);
    }
    raop_set_plist(receiver, "width", width);
    raop_set_plist(receiver, "height", height);
    raop_set_plist(receiver, "maxFPS", fps);
    unsigned short port = (unsigned short)requested_port;
    if (raop_start_httpd(receiver, &port) < 0 || !port) {
        destroy_receiver(probe);
        return 0;
    }
    raop_set_port(receiver, port);
    if (dnssd_register_airplay(probe->discovery, port) || dnssd_register_raop(probe->discovery, port)) {
        destroy_receiver(probe); return 0;
    }
    return (jlong)(intptr_t)probe;
}

JNIEXPORT jlong JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_startReceiver(JNIEnv *env, jclass type, jstring key_path, jobject callback) {
    return create_receiver(env, key_path, callback, "02:00:00:00:00:17", NULL, 0, NULL, 0, 1280,720,30);
}

JNIEXPORT jlong JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_createNetworkReceiver(JNIEnv *env, jclass type,
        jstring key_path, jobject callback, jstring identity, jstring network_address, jint prefix,
        jbyteArray utf8_name, jint requested_port,jint width,jint height,jint fps) {
    char name[64]={0};
    if (utf8_name) {
        jsize length=(*env)->GetArrayLength(env,utf8_name);
        if (length<1 || length>63) return 0;
        (*env)->GetByteArrayRegion(env,utf8_name,0,length,(jbyte *)name);
        if ((*env)->ExceptionCheck(env) || memchr(name,0,length)) return 0;
    }
    const char *id = (*env)->GetStringUTFChars(env, identity, NULL);
    if (!id) return 0;
    const char *address = (*env)->GetStringUTFChars(env, network_address, NULL);
    if (!address) { (*env)->ReleaseStringUTFChars(env, identity, id); return 0; }
    jlong result = create_receiver(env, key_path, callback, id, address, prefix,
                                   utf8_name ? name : NULL, requested_port,width,height,fps);
    (*env)->ReleaseStringUTFChars(env, network_address, address);
    (*env)->ReleaseStringUTFChars(env, identity, id);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_receiverTxt(JNIEnv *env, jclass type, jlong handle, jboolean airplay) {
    receiver_probe_t *probe = (receiver_probe_t *)(intptr_t)handle;
    if (!probe) return NULL;
    int count = airplay ? android_dnssd_get_airplay_txt_count(probe->discovery) : android_dnssd_get_raop_txt_count(probe->discovery);
    jclass strings = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count * 2, strings, NULL);
    (*env)->DeleteLocalRef(env, strings);
    if (!result) return NULL;
    for (int i = 0; i < count; i++) {
        const char *key = airplay ? android_dnssd_get_airplay_txt_key(probe->discovery, i) : android_dnssd_get_raop_txt_key(probe->discovery, i);
        const char *value = airplay ? android_dnssd_get_airplay_txt_val(probe->discovery, i) : android_dnssd_get_raop_txt_val(probe->discovery, i);
        jstring k = (*env)->NewStringUTF(env, key), v = (*env)->NewStringUTF(env, value);
        if (!k || !v) return NULL;
        (*env)->SetObjectArrayElement(env, result, i*2, k);
        (*env)->SetObjectArrayElement(env, result, i*2+1, v);
        (*env)->DeleteLocalRef(env, k); (*env)->DeleteLocalRef(env, v);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_receiverPort(JNIEnv *env, jclass type, jlong handle) {
    receiver_probe_t *probe = (receiver_probe_t *)(intptr_t)handle;
    return raop_get_port(probe->receiver);
}

JNIEXPORT void JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_stopReceiver(JNIEnv *env, jclass type, jlong handle) {
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
Java_io_buzincast_fireos3probe_NativeReceiver_replayReceiverVideo(
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

typedef struct {
    receiver_probe_t *probe;
    audio_decode_struct packet;
    unsigned short spf;
    float volume;
    bool flush;
} audio_replay_t;

static void *replay_audio_on_worker(void *context) {
    audio_replay_t *replay = context;
    bool screen = true, media = false;
    uint64_t format = 0;
    if (replay->flush) audio_flush(replay->probe);
    if (replay->spf) audio_format(replay->probe, &replay->packet.ct, &replay->spf, &screen, &media, &format);
    audio_volume(replay->probe, replay->volume);
    if (replay->packet.data_len) forward_audio(replay->probe, NULL, &replay->packet);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_replayReceiverAudio(JNIEnv *env, jclass type, jlong handle,
        jobject input, jint length, jint codec, jint spf, jlong time_ns, jfloat volume, jboolean flush,jboolean native_thread) {
    receiver_probe_t *probe = (receiver_probe_t *)(intptr_t)handle;
    unsigned char *bytes = (*env)->GetDirectBufferAddress(env, input);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, input);
    if (!probe || !probe->audio_enabled || (!bytes && length != 0) || length < 0 || length > 64*1024 || length > capacity ||
        time_ns < 0 || codec < 0 || codec > 255 || spf < 0 || spf > 65535) return JNI_FALSE;
    audio_replay_t replay = {.probe=probe, .packet={.data=bytes, .ct=(unsigned char)codec,
        .data_len=length, .ntp_time_local=(uint64_t)time_ns}, .spf=(unsigned short)spf, .volume=volume, .flush=flush};
    // The timed fixture uses its existing managed sender thread. The separate
    // callback/format/flush tests retain the native-created worker and VM attach.
    if (!native_thread) { replay_audio_on_worker(&replay); return JNI_TRUE; }
    pthread_t worker;
    if (pthread_create(&worker, NULL, replay_audio_on_worker, &replay) != 0) return JNI_FALSE;
    pthread_join(worker, NULL);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_checkNativeParser(JNIEnv *env, jclass activity) {
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

typedef struct {receiver_probe_t *probe;const void *bytes;int length,kind;uint32_t start,current,end;} media_replay_t;
static void *replay_media_on_worker(void *context) {
    media_replay_t *fixture=context;
    if(fixture->kind==1)metadata(fixture->probe,fixture->bytes,fixture->length);
    else if(fixture->kind==2)artwork(fixture->probe,fixture->bytes,fixture->length);
    else if(fixture->kind==3)progress(fixture->probe,&fixture->start,&fixture->current,&fixture->end);
    else sender_stopped(fixture->probe);
    return NULL;
}
JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_replayReceiverMedia(JNIEnv *env,jclass type,jlong handle,jobject input,
        jint length,jint kind,jlong start,jlong current,jlong end) {
    receiver_probe_t *probe=(receiver_probe_t *)(intptr_t)handle;
    void *bytes=(*env)->GetDirectBufferAddress(env,input);jlong capacity=(*env)->GetDirectBufferCapacity(env,input);
    if(!probe || !probe->video_callback || length<0 || length>capacity || (!bytes && length) || kind<1 || kind>4 ||
        (kind==1 && length>64*1024) || (kind==2 && length>4*1024*1024) ||
        start<0 || current<0 || end<0 || start>UINT32_MAX || current>UINT32_MAX || end>UINT32_MAX)return JNI_FALSE;
    media_replay_t fixture={probe,bytes,length,kind,(uint32_t)start,(uint32_t)current,(uint32_t)end};pthread_t worker;
    if(pthread_create(&worker,NULL,replay_media_on_worker,&fixture)!=0)return JNI_FALSE;
    pthread_join(worker,NULL);return JNI_TRUE;
}

/* Replay the same frame payload, timestamp, and codec flag as the receiver's
 * video_process callback. The sample source stands in for a live RAOP session. */
JNIEXPORT jboolean JNICALL
Java_io_buzincast_fireos3probe_NativeReceiver_replayVideoFrame(
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
