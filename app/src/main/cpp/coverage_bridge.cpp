// Coverage-only JNI hook. Never linked in production builds.
#include <jni.h>
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
