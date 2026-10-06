#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <exception>
#include <memory>
#include <stdexcept>

#include "PremiumVocalRemoverDSP.h"

namespace {

using ProfessionalDSP::PremiumVocalRemoverDSP;

struct Engine {
    PremiumVocalRemoverDSP dsp;
};

Engine* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<Engine*>(static_cast<std::uintptr_t>(handle));
}

void throwIllegalState(JNIEnv* env, const char* message) noexcept {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

void throwIllegalArg(JNIEnv* env, const char* message) noexcept {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls) env->ThrowNew(cls, message);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_nativeCreate(
    JNIEnv*, jobject, jdouble sampleRate) noexcept {
    try {
        auto* engine = new Engine();
        engine->dsp.prepare(sampleRate);
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(engine));
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_nativeDestroy(
    JNIEnv*, jobject, jlong handle) noexcept {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_nativeReset(
    JNIEnv* env, jobject, jlong handle) noexcept {
    auto* e = fromHandle(handle);
    if (!e) {
        throwIllegalState(env, "Invalid vocal-remover handle");
        return;
    }
    e->dsp.reset();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_nativeLatency(
    JNIEnv* env, jobject, jlong handle) noexcept {
    auto* e = fromHandle(handle);
    if (!e) {
        throwIllegalState(env, "Invalid vocal-remover handle");
        return 0;
    }
    return static_cast<jint>(e->dsp.latencySamples());
}

#define JNI_SETTER(name, method) \
extern "C" JNIEXPORT void JNICALL \
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_##name( \
    JNIEnv* env, jobject, jlong handle, jfloat value) noexcept { \
    auto* e = fromHandle(handle); \
    if (!e) { throwIllegalState(env, "Invalid vocal-remover handle"); return; } \
    e->dsp.method(value); \
}

JNI_SETTER(nativeSetDepth, setDepth)
JNI_SETTER(nativeSetFocus, setVocalFocus)
JNI_SETTER(nativeSetTransientProtection, setTransientProtection)
JNI_SETTER(nativeSetStemGainDb, setVocalStemGainDb)
JNI_SETTER(nativeSetDryWet, setDryWet)
JNI_SETTER(nativeSetOutputGainDb, setOutputGainDb)
JNI_SETTER(nativeSetCeilingDb, setOutputCeilingDb)

extern "C" JNIEXPORT void JNICALL
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_nativeSetNeuralMode(
    JNIEnv* env, jobject, jlong handle, jboolean enabled) noexcept {
    auto* e = fromHandle(handle);
    if (!e) {
        throwIllegalState(env, "Invalid vocal-remover handle");
        return;
    }
    e->dsp.setNeuralStemMode(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bmwanje_audiophile_vocalremover_NativeVocalRemover_nativeProcess(
    JNIEnv* env, jobject, jlong handle,
    jfloatArray mixL, jfloatArray mixR,
    jfloatArray vocalL, jfloatArray vocalR,
    jfloatArray outL, jfloatArray outR, jint n) noexcept {
    auto* e = fromHandle(handle);
    if (!e) {
        throwIllegalState(env, "Invalid vocal-remover handle");
        return;
    }
    if (n <= 0 || !mixL || !mixR || !vocalL || !vocalR || !outL || !outR) {
        throwIllegalArg(env, "Invalid audio arrays");
        return;
    }

    const jsize needed = static_cast<jsize>(n);
    if (env->GetArrayLength(mixL) < needed || env->GetArrayLength(mixR) < needed ||
        env->GetArrayLength(vocalL) < needed || env->GetArrayLength(vocalR) < needed ||
        env->GetArrayLength(outL) < needed || env->GetArrayLength(outR) < needed) {
        throwIllegalArg(env, "Audio array shorter than n");
        return;
    }

    jboolean copies[6] = {};
    float* a = env->GetFloatArrayElements(mixL, &copies[0]);
    float* b = env->GetFloatArrayElements(mixR, &copies[1]);
    float* c = env->GetFloatArrayElements(vocalL, &copies[2]);
    float* d = env->GetFloatArrayElements(vocalR, &copies[3]);
    float* oL = env->GetFloatArrayElements(outL, &copies[4]);
    float* oR = env->GetFloatArrayElements(outR, &copies[5]);

    if (!a || !b || !c || !d || !oL || !oR) {
        if (a) env->ReleaseFloatArrayElements(mixL, a, JNI_ABORT);
        if (b) env->ReleaseFloatArrayElements(mixR, b, JNI_ABORT);
        if (c) env->ReleaseFloatArrayElements(vocalL, c, JNI_ABORT);
        if (d) env->ReleaseFloatArrayElements(vocalR, d, JNI_ABORT);
        if (oL) env->ReleaseFloatArrayElements(outL, oL, 0);
        if (oR) env->ReleaseFloatArrayElements(outR, oR, 0);
        throwIllegalState(env, "Could not access audio arrays");
        return;
    }

    e->dsp.processBlock(a, b, c, d, oL, oR, static_cast<std::size_t>(n));

    env->ReleaseFloatArrayElements(mixL, a, JNI_ABORT);
    env->ReleaseFloatArrayElements(mixR, b, JNI_ABORT);
    env->ReleaseFloatArrayElements(vocalL, c, JNI_ABORT);
    env->ReleaseFloatArrayElements(vocalR, d, JNI_ABORT);
    env->ReleaseFloatArrayElements(outL, oL, 0);
    env->ReleaseFloatArrayElements(outR, oR, 0);
}
