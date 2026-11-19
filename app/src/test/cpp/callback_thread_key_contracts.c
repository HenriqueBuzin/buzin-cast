#include "android_raop_callbacks.h"
#include <errno.h>
#include <stdlib.h>

typedef struct {
    struct JNINativeInterface functions;
    struct JNIInvokeInterface invocation;
    JNIEnv env;
    JavaVM vm;
    int queries, attaches, jni_calls, key_attempts, unexpected;
    bool bad_arguments;
} KeyFault;

// This case runs on main after every worker test has joined. The intercepted
// TLS bootstrap must not depend on emulated TLS itself.
static KeyFault *active;

static jint query_env(JavaVM *vm, void **out, jint version) {
    active->queries++;
    active->bad_arguments |= vm != &active->vm || version != JNI_VERSION_1_6;
    *out = &active->env; // A failed query must not make this pointer usable.
    return JNI_EDETACHED;
}
static jint attach_thread(JavaVM *vm, JNIEnv **out, void *args) {
    active->attaches++;
    *out = &active->env;
    return JNI_ERR;
}
static jboolean check_exception(JNIEnv *env) {
    active->jni_calls++;
    return JNI_FALSE;
}
static void call_void(JNIEnv *env, jobject object, jmethodID method, ...) {
    active->jni_calls++;
}

int __real_pthread_key_create(pthread_key_t *, void (*)(void *));
int __wrap_pthread_key_create(pthread_key_t *key, void (*destroy)(void *)) {
    if (!active) return __real_pthread_key_create(key, destroy);
    active->key_attempts++;
    active->bad_arguments |= key == NULL || destroy == NULL;
    return EAGAIN;
}

// Progress delivery does not use either dependency. Unexpected use fails the
// contract without linking decoder or network implementations into this unit.
void audio_engine_decode(AudioEngine *engine, const uint8_t *data, int len, int ct, int64_t pts) {
    if (!active) abort();
    active->unexpected++;
}
void raop_remove_hls_connections(raop_t *raop) {
    if (!active) abort();
    active->unexpected++;
}

int callbackThreadKeyCreationFailureStatus(void) {
    // Match the production C JNI types, preserving UBSan indirect-call checks.
    // This standalone process owns its once state; no ART/APK TLS is reset.
    KeyFault fault = {0};
    fault.env = &fault.functions;
    fault.vm = &fault.invocation;
    fault.invocation.GetEnv = query_env;
    fault.invocation.AttachCurrentThread = attach_thread;
    fault.functions.ExceptionCheck = check_exception;
    fault.functions.CallVoidMethod = call_void;
    active = &fault;
    android_callback_ctx_t context = {0};
    context.jvm = &fault.vm;
    context.callback_obj = (jobject)(uintptr_t)1;
    raop_callbacks_t callbacks = {0};
    android_callbacks_fill(&callbacks, &context);
    int status = 0;
#define KEY_REQUIRE(condition) do { if (!(condition)) { status = __LINE__; goto cleanup; } } while (false)
    KEY_REQUIRE(callbacks.audio_set_progress != NULL);
    uint32_t start = 1, current = 2, end = 3;
    callbacks.audio_set_progress(&context, &start, &current, &end);
    callbacks.audio_set_progress(&context, &start, &current, &end);
    KEY_REQUIRE(fault.queries == 2);
    KEY_REQUIRE(fault.key_attempts == 1);
    KEY_REQUIRE(fault.attaches == 0 && fault.jni_calls == 0);
    KEY_REQUIRE(fault.unexpected == 0 && !fault.bad_arguments);
cleanup:
    active = NULL;
    return status;
#undef KEY_REQUIRE
}
