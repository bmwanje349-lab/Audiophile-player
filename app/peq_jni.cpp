#include <jni.h>
#include <vector>
#include <cstdint>
#include "peq_engine.h"

namespace {
inline peq::Engine* engineFrom(jlong h) {
    return reinterpret_cast<peq::Engine*>(h);
}
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_peq_PeqNative_create(JNIEnv*, jclass, jint numBands) {
    try {
        return reinterpret_cast<jlong>(new peq::Engine(static_cast<int>(numBands)));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_destroy(JNIEnv*, jclass, jlong h) {
    delete engineFrom(h);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setSampleRate(JNIEnv*, jclass, jlong h, jint sr) {
    if (h) engineFrom(h)->setSampleRate(sr);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setBand(
        JNIEnv*, jclass, jlong h, jint index, jint type,
        jfloat freq, jfloat gainDb, jfloat q, jboolean enabled) {
    if (!h) return;
    peq::BandParams p;
    p.type = static_cast<int>(type);
    p.freq = freq;
    p.gainDb = gainDb;
    p.q = q;
    p.enabled = (enabled == JNI_TRUE);
    engineFrom(h)->setBand(static_cast<int>(index), p);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setPeqEnabled(JNIEnv*, jclass, jlong h, jboolean on) {
    if (h) engineFrom(h)->setPeqEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setGraphicEqEnabled(JNIEnv*, jclass, jlong h, jboolean on) {
    if (h) engineFrom(h)->setGraphicEqEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setGraphicBand(
        JNIEnv*, jclass, jlong h, jint index, jfloat gainDb,
        jfloat q, jboolean enabled) {
    if (h) engineFrom(h)->setGraphicBand(
        static_cast<int>(index), gainDb, q, enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setGraphicBands(
        JNIEnv* env, jclass, jlong h, jfloatArray gains, jfloat q) {
    if (!h || !gains) return;
    const jsize n = env->GetArrayLength(gains);
    if (n <= 0) return;
    std::vector<jfloat> values(static_cast<size_t>(n));
    env->GetFloatArrayRegion(gains, 0, n, values.data());
    if (env->ExceptionCheck()) return;
    engineFrom(h)->setGraphicBands(values.data(), static_cast<int>(n), q);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setInputGainDb(JNIEnv*, jclass, jlong h, jfloat db) {
    if (h) engineFrom(h)->setInputGainDb(db);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setOutputGainDb(JNIEnv*, jclass, jlong h, jfloat db) {
    if (h) engineFrom(h)->setOutputGainDb(db);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setBypass(JNIEnv*, jclass, jlong h, jboolean on) {
    if (h) engineFrom(h)->setBypass(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLimiter(JNIEnv*, jclass, jlong h, jboolean on) {
    if (h) engineFrom(h)->setLimiterEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLimiterCeilingDb(JNIEnv*, jclass, jlong h, jfloat db) {
    if (h) engineFrom(h)->setLimiterCeilingDb(db);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLimiterSafetyDb(JNIEnv*, jclass, jlong h, jfloat db) {
    if (h) engineFrom(h)->setLimiterSafetyDb(db);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLimiterLookaheadMs(JNIEnv*, jclass, jlong h, jfloat ms) {
    if (h) engineFrom(h)->setLimiterLookaheadMs(ms);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLimiterReleaseMs(JNIEnv*, jclass, jlong h, jfloat ms) {
    if (h) engineFrom(h)->setLimiterReleaseMs(ms);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setFilterSmoothingMs(JNIEnv*, jclass, jlong h, jfloat ms) {
    if (h) engineFrom(h)->setFilterSmoothingMs(ms);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setGainSmoothingMs(JNIEnv*, jclass, jlong h, jfloat ms) {
    if (h) engineFrom(h)->setGainSmoothingMs(ms);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setAutoGainEnabled(JNIEnv*, jclass, jlong h, jboolean on) {
    if (h) engineFrom(h)->setAutoGainEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setAutoGainAmount(JNIEnv*, jclass, jlong h, jfloat amount) {
    if (h) engineFrom(h)->setAutoGainAmount(amount);
}

JNIEXPORT jfloat JNICALL
Java_com_example_peq_PeqNative_autoMakeupGainDb(JNIEnv*, jclass, jlong h) {
    if (!h) return 0.0f;
    return engineFrom(h)->autoMakeupGainDb();
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLinearPhaseEnabled(JNIEnv*, jclass, jlong h, jboolean on) {
    if (h) engineFrom(h)->setLinearPhaseEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_setLinearPhaseTaps(JNIEnv*, jclass, jlong h, jint taps) {
    if (h) engineFrom(h)->setLinearPhaseTaps(static_cast<int>(taps));
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_reset(JNIEnv*, jclass, jlong h) {
    if (h) engineFrom(h)->reset();
}

JNIEXPORT jint JNICALL
Java_com_example_peq_PeqNative_process(
        JNIEnv* env, jclass, jlong h, jobject buffer,
        jint frames, jint channels, jboolean isFloat) {
    if (!h || !buffer || frames <= 0 || channels <= 0) return 0;

    void* addr = env->GetDirectBufferAddress(buffer);
    if (!addr) return 0;

    const jlong bytesPerSample = (isFloat == JNI_TRUE) ? 4LL : 2LL;
    const jlong requiredBytes = static_cast<jlong>(frames) *
                                static_cast<jlong>(channels) *
                                bytesPerSample;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || capacity < requiredBytes) return 0;

    if (isFloat == JNI_TRUE) {
        return engineFrom(h)->processFloat(static_cast<float*>(addr), frames, channels);
    }
    return engineFrom(h)->processS16(static_cast<int16_t*>(addr), frames, channels);
}

JNIEXPORT jint JNICALL
Java_com_example_peq_PeqNative_pendingFrames(JNIEnv*, jclass, jlong h) {
    if (!h) return 0;
    return static_cast<jint>(engineFrom(h)->pendingFrames());
}

JNIEXPORT jint JNICALL
Java_com_example_peq_PeqNative_latencyFrames(JNIEnv*, jclass, jlong h) {
    if (!h) return 0;
    return static_cast<jint>(engineFrom(h)->latencyFrames());
}

JNIEXPORT jint JNICALL
Java_com_example_peq_PeqNative_drain(
        JNIEnv* env, jclass, jlong h, jobject buffer,
        jint maxFrames, jint channels, jboolean isFloat) {
    if (!h || !buffer || maxFrames <= 0 || channels <= 0) return 0;

    void* addr = env->GetDirectBufferAddress(buffer);
    if (!addr) return 0;

    const jlong bytesPerSample = (isFloat == JNI_TRUE) ? 4LL : 2LL;
    const jlong requiredBytes = static_cast<jlong>(maxFrames) *
                                static_cast<jlong>(channels) *
                                bytesPerSample;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || capacity < requiredBytes) return 0;

    if (isFloat == JNI_TRUE) {
        return engineFrom(h)->drainFloat(static_cast<float*>(addr), maxFrames, channels);
    }
    return engineFrom(h)->drainS16(static_cast<int16_t*>(addr), maxFrames, channels);
}

JNIEXPORT void JNICALL
Java_com_example_peq_PeqNative_responseDb(
        JNIEnv* env, jclass, jlong h, jfloatArray freqs, jfloatArray out) {
    if (!h || !freqs || !out) return;

    const jsize n = env->GetArrayLength(freqs);
    if (n <= 0 || env->GetArrayLength(out) < n) return;

    std::vector<float> f(static_cast<size_t>(n));
    std::vector<float> r(static_cast<size_t>(n));

    env->GetFloatArrayRegion(freqs, 0, n, f.data());
    if (env->ExceptionCheck()) return;

    engineFrom(h)->responseDb(f.data(), r.data(), n);
    env->SetFloatArrayRegion(out, 0, n, r.data());
}

} // extern "C"
