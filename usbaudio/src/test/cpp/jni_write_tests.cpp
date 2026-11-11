// Evanna tests of the compiled JNI entry point with a minimal fake JNI buffer API.
// The real UsbStreamer stays unopened: no USB interfaces, transfers or audio.
#include <jni.h>
#include <cstdio>
#include "../../main/cpp/UsbStreamer.h"

extern "C" jint Java_com_truesample_usbaudio_NativeBridge_nativeWrite(JNIEnv*, jobject, jlong, jobject, jint);
extern "C" jintArray Java_com_truesample_usbaudio_NativeBridge_nativeStart(
    JNIEnv*, jobject, jlong, jint, jint, jint, jint, jint, jint);
extern "C" jintArray Java_com_truesample_usbaudio_NativeBridge_nativePlan(
    JNIEnv*, jobject, jlong, jint, jint, jint, jint, jint);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeStop(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeDrain(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeFeedbackReady(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeVolumeVerified(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeClockConfigured(JNIEnv*, jobject, jlong);
extern "C" jboolean Java_com_truesample_usbaudio_NativeBridge_nativeSetVolume(JNIEnv*, jobject, jlong, jint);

struct Buffer { jlong capacity; void* address; };
static int exceptions = 0, addressReads = 0, failures = 0, checks = 0;
#define CHECK(value) do { ++checks; if (!(value)) { ++failures; std::printf("FAIL line %d: %s\n", __LINE__, #value); } } while (0)

int main() {
    JNINativeInterface api{};
    api.FindClass = [](JNIEnv*, const char*) { return reinterpret_cast<jclass>(1); };
    api.ThrowNew = [](JNIEnv*, jclass, const char*) { ++exceptions; return jint(0); };
    api.GetDirectBufferCapacity = [](JNIEnv*, jobject buffer) { return reinterpret_cast<Buffer*>(buffer)->capacity; };
    api.GetDirectBufferAddress = [](JNIEnv*, jobject buffer) {
        ++addressReads;
        return reinterpret_cast<Buffer*>(buffer)->address;
    };
    JNIEnv env{&api};
    UsbStreamer output;
    const jlong handle = reinterpret_cast<jlong>(&output);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeDrain(&env, nullptr, 0) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeDrain(&env, nullptr, handle) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeClockConfigured(&env, nullptr, handle) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeClockConfigured(&env, nullptr, 0) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeFeedbackReady(&env, nullptr, handle) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeFeedbackReady(&env, nullptr, 0) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeVolumeVerified(&env, nullptr, handle) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeVolumeVerified(&env, nullptr, 0) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeSetVolume(&env, nullptr, 0, -7680) == JNI_FALSE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeSetVolume(&env, nullptr, handle, 32768) == JNI_FALSE);
    int32_t samples[2]{};
    Buffer direct{sizeof samples, samples}, empty{0, nullptr}, heap{-1, nullptr};
    auto write = [&](jlong owner, Buffer* buffer, jint frames) {
        exceptions = addressReads = 0;
        return Java_com_truesample_usbaudio_NativeBridge_nativeWrite(&env, nullptr, owner,
            reinterpret_cast<jobject>(buffer), frames);
    };
    CHECK(write(handle, &empty, 0) == 0);
    CHECK(exceptions == 0 && addressReads == 0);
    CHECK(write(handle, &direct, 1) == -1);
    CHECK(exceptions == 0 && addressReads == 0);  // unopened/stopped never reads PCM
    CHECK(write(handle, &direct, -1) == -1);
    CHECK(exceptions == 1 && addressReads == 0);
    CHECK(write(handle, &heap, 1) == -1);
    CHECK(exceptions == 1);
    CHECK(write(handle, &heap, 0) == -1);
    CHECK(exceptions == 1);
    CHECK(write(handle, nullptr, 0) == -1);
    CHECK(exceptions == 1);
    CHECK(write(0, &direct, 0) == -1);
    CHECK(exceptions == 1 && addressReads == 0);
    exceptions = 0;
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeStart(&env, nullptr, handle, -1, 24, 2, 0, -1, 0) == nullptr);
    CHECK(exceptions == 1);
    exceptions = 0;
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativePlan(&env, nullptr, handle, 48000, 33, 2, -1, 0) == nullptr);
    CHECK(exceptions == 1);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeStop(&env, nullptr, handle) == JNI_TRUE);
    CHECK(Java_com_truesample_usbaudio_NativeBridge_nativeStop(&env, nullptr, 0) == JNI_TRUE);
    CHECK(write(handle, &direct, 1) == -1 && exceptions == 0);
    CHECK(write(handle, &empty, 0) == 0 && exceptions == 0);
    std::printf("JNI: %d checks, %d failures (DAC unopened)\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
