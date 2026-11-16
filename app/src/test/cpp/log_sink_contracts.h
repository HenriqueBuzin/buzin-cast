#pragma once
#include "log_sink.h"
#include <cstring>
#include <string>
#include <vector>

// The JNI function tables are an external dependency. Model their documented
// failures without exhausting memory or aborting an actual Android VM.
struct LogJniFixture {
    JNINativeInterface functions{};
    JNIInvokeInterface invocation{};
    JNIEnv env{};
    JavaVM vm{};
    inline static LogJniFixture *active = nullptr;
    bool vmMissing = false, globalMissing = false, classMissing = false, methodMissing = false;
    bool stringMissing = false, failureThrows = true, callbackThrows = false, pending = false;
    bool envUsable = true, badArguments = false;
    int envStatus = JNI_OK, attachStatus = JNI_OK;
    int illegal = 0, globalsCreated = 0, globalsDeleted = 0, classQueries = 0, methodQueries = 0;
    int strings = 0, calls = 0, localDeletes = 0, attaches = 0, detaches = 0, clears = 0, descriptions = 0;
    std::string lastString;
    std::vector<std::string> messages;

    void access(bool pendingAllowed = false) { if (!envUsable || (pending && !pendingAllowed)) ++illegal; }
    static jobject object() { return reinterpret_cast<jobject>(1); }
    static jclass klass() { return reinterpret_cast<jclass>(2); }
    static jmethodID method() { return reinterpret_cast<jmethodID>(3); }
    static jstring string() { return reinterpret_cast<jstring>(4); }

    LogJniFixture() {
        active = this; env.functions = &functions; vm.functions = &invocation;
        functions.GetJavaVM = [](JNIEnv *, JavaVM **out) -> jint {
            auto &s = *active; s.access(); *out = s.vmMissing ? nullptr : &s.vm;
            return s.vmMissing ? JNI_ERR : JNI_OK;
        };
        functions.NewGlobalRef = [](JNIEnv *, jobject obj) -> jobject {
            auto &s = *active; s.access(); s.badArguments |= obj != object();
            if (s.globalMissing) { s.pending = s.failureThrows; return nullptr; }
            ++s.globalsCreated; return obj;
        };
        functions.DeleteGlobalRef = [](JNIEnv *, jobject obj) {
            auto &s = *active; s.access(true); s.badArguments |= obj != object(); ++s.globalsDeleted;
        };
        functions.GetObjectClass = [](JNIEnv *, jobject obj) -> jclass {
            auto &s = *active; s.access(); ++s.classQueries; s.badArguments |= obj != object();
            if (s.classMissing) { s.pending = s.failureThrows; return nullptr; }
            return klass();
        };
        functions.GetMethodID = [](JNIEnv *, jclass cls, const char *name, const char *signature) -> jmethodID {
            auto &s = *active; s.access(); ++s.methodQueries;
            s.badArguments |= cls != klass() || std::strcmp(name, "onLog") || std::strcmp(signature, "(Ljava/lang/String;)V");
            if (s.methodMissing) { s.pending = s.failureThrows; return nullptr; }
            return method();
        };
        functions.DeleteLocalRef = [](JNIEnv *, jobject obj) {
            auto &s = *active; s.access(true);
            s.badArguments |= obj && obj != reinterpret_cast<jobject>(klass()) && obj != reinterpret_cast<jobject>(string());
            if (obj) ++s.localDeletes;
        };
        functions.NewStringUTF = [](JNIEnv *, const char *text) -> jstring {
            auto &s = *active; s.access(); ++s.strings; s.lastString = text;
            if (s.stringMissing) { s.pending = s.failureThrows; return nullptr; }
            return string();
        };
        functions.CallVoidMethodV = [](JNIEnv *, jobject obj, jmethodID id, va_list args) {
            auto &s = *active; s.access(); ++s.calls;
            const auto text = va_arg(args, jstring);
            s.badArguments |= obj != object() || id != method() || text != string();
            if (text) s.messages.push_back(s.lastString);
            if (s.callbackThrows) s.pending = true;
        };
        functions.ExceptionCheck = [](JNIEnv *) -> jboolean { active->access(true); return active->pending ? JNI_TRUE : JNI_FALSE; };
        functions.ExceptionDescribe = [](JNIEnv *) { active->access(true); ++active->descriptions; };
        functions.ExceptionClear = [](JNIEnv *) { active->access(true); active->pending = false; ++active->clears; };
        invocation.GetEnv = [](JavaVM *, void **out, jint version) -> jint {
            auto &s = *active; s.badArguments |= version != JNI_VERSION_1_6;
            // A failed query may leave an unusable pointer. Callers must honor
            // its status, regardless of the output pointer's value.
            *out = &s.env; s.envUsable = s.envStatus == JNI_OK; return s.envStatus;
        };
        invocation.AttachCurrentThread = [](JavaVM *, JNIEnv **out, void *) -> jint {
            auto &s = *active; ++s.attaches; *out = &s.env;
            s.envUsable = s.attachStatus == JNI_OK; return s.attachStatus;
        };
        invocation.DetachCurrentThread = [](JavaVM *) -> jint {
            auto &s = *active; ++s.detaches; s.envUsable = false; return JNI_OK;
        };
    }
    ~LogJniFixture() { active = nullptr; }
};

static void logSinkFormatsAndForwards() {
    LogJniFixture jni;
    {
        LogSink sink; sink.bind(&jni.env, LogJniFixture::object());
        sink.info("message %d", 7); sink.warn("warning %s", "value"); sink.error("error %d", 9);
        const std::string longMessage(400, 'x'); sink.info("%s", longMessage.c_str());
        CHECK(jni.messages.size() == 4 && jni.messages[0] == "message 7" && jni.messages[1] == "warning value");
        CHECK(jni.messages[2] == "error 9" && jni.messages[3] == std::string(255, 'x'));
    }
    CHECK(jni.globalsCreated == 1 && jni.globalsDeleted == 1 && jni.localDeletes == 5);
    CHECK(jni.attaches == 0 && jni.detaches == 0 && jni.illegal == 0 && !jni.badArguments);
    { LogSink unbound; unbound.info("unbound"); unbound.warn("unbound"); unbound.error("unbound"); }
    CHECK(jni.calls == 4);
}

static void logSinkVmFailuresAndAttachment() {
    {
        LogJniFixture jni; jni.envStatus = JNI_EDETACHED;
        { LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.info("worker"); }
        CHECK(jni.calls == 1 && jni.attaches == 2 && jni.detaches == 2 && jni.globalsDeleted == 1);
        CHECK(jni.illegal == 0 && !jni.badArguments);
    }
    {
        LogJniFixture jni; jni.envStatus = JNI_EDETACHED; jni.attachStatus = JNI_ERR;
        { LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.info("unavailable"); }
        CHECK(jni.calls == 0 && jni.attaches == 2 && jni.detaches == 0 && jni.globalsDeleted == 0 && jni.illegal == 0);
    }
    for (const int status : {JNI_ERR, JNI_EVERSION}) {
        LogJniFixture jni; jni.envStatus = status;
        { LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.info("invalid VM state"); }
        CHECK(jni.calls == 0 && jni.attaches == 0 && jni.globalsDeleted == 0 && jni.illegal == 0);
    }
}

static void logSinkBindingFailures() {
    for (int failure = 0; failure < 5; ++failure) {
        LogJniFixture jni;
        jni.vmMissing = failure == 0;
        jni.globalMissing = failure == 1 || failure == 2;
        jni.failureThrows = failure != 2;
        jni.classMissing = failure == 3; jni.methodMissing = failure == 4;
        {
            LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.warn("logcat fallback");
            CHECK(!jni.pending && jni.calls == 0 && jni.illegal == 0);
        }
        CHECK(jni.globalsCreated == jni.globalsDeleted && !jni.badArguments);
        if (jni.globalMissing || jni.vmMissing) CHECK(jni.classQueries == 0);
    }
}

static void logSinkAllocationAndCallbackFailures() {
    for (bool throwing : {false, true}) {
        LogJniFixture jni; jni.stringMissing = true; jni.failureThrows = throwing; jni.envStatus = JNI_EDETACHED;
        {
            LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.error("allocation failure");
            CHECK(jni.calls == 0 && jni.illegal == 0 && !jni.pending);
        }
        CHECK(jni.attaches == jni.detaches && jni.globalsDeleted == 1);
    }
    {
        LogJniFixture jni; jni.callbackThrows = true;
        { LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.warn("throwing callback"); }
        CHECK(jni.calls == 1 && jni.descriptions == 1 && jni.clears == 1 && !jni.pending);
        CHECK(jni.illegal == 0 && jni.globalsCreated == jni.globalsDeleted);
    }
}

static void logSinkRebindingReleasesReferences() {
    LogJniFixture jni;
    {
        LogSink sink; sink.bind(&jni.env, LogJniFixture::object()); sink.bind(&jni.env, LogJniFixture::object());
        sink.info("rebound");
    }
    CHECK(jni.globalsCreated == 2 && jni.globalsDeleted == 2 && jni.calls == 1 && jni.illegal == 0);
}
