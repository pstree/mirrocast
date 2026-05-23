#include <jni.h>
#include <string>

// JNI entrypoint matching NativeBridge.version() in
// io.github.tffinder.mirrocast.NativeBridge. Phase 0 stub; will be
// replaced by real protocol receivers in later phases.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tffinder_mirrocast_NativeBridge_version(
        JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("0.0.1-stub");
}
