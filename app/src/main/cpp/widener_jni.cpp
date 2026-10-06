#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include "ProfessionalStereoWidenerDSP_v10.h"

namespace {

inline ProfessionalDSP::StereoWidenerDSP* engineFrom(jlong h) noexcept {
    return reinterpret_cast<ProfessionalDSP::StereoWidenerDSP*>(h);
}

inline float s16ToFloat(std::int16_t x) noexcept {
    return static_cast<float>(x) / 32768.0f;
}

inline std::int16_t floatToS16(float x) noexcept {
    if (!std::isfinite(x)) x = 0.0f;
    x = std::max(-1.0f, std::min(1.0f, x));
    const float scaled = x * 32767.0f;
    if (scaled >= 0.0f) {
        return static_cast<std::int16_t>(std::lrintf(scaled));
    }
    return static_cast<std::int16_t>(std::lrintf(scaled));
}

inline bool validateBuffer(JNIEnv* env, jobject buffer,
                           jint frames, jint channels,
                           jint bytesPerSample) noexcept {
    if (!buffer || frames <= 0 || channels <= 0 || bytesPerSample <= 0)
        return false;
    const jlong required =
        static_cast<jlong>(frames) *
        static_cast<jlong>(channels) *
        static_cast<jlong>(bytesPerSample);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    return capacity >= required && env->GetDirectBufferAddress(buffer) != nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_peq_WidenerNative_create(JNIEnv*, jclass) {
    try {
        return reinterpret_cast<jlong>(new ProfessionalDSP::StereoWidenerDSP());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_destroy(JNIEnv*, jclass, jlong h) {
    delete engineFrom(h);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_prepare(JNIEnv*, jclass, jlong h, jint sampleRate) {
    if (h) engineFrom(h)->prepare(static_cast<double>(sampleRate));
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_reset(JNIEnv*, jclass, jlong h) {
    if (h) engineFrom(h)->reset();
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setEnabled(JNIEnv*, jclass, jlong h, jboolean v) {
    if (h) engineFrom(h)->setEnabled(v == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setWidth(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setWidth(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setLowCrossoverHz(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setLowCrossoverHz(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setHighCrossoverHz(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setHighCrossoverHz(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setBassMonoFrequencyHz(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setBassMonoFrequencyHz(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setHaasDelayMs(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setHaasDelayMs(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setHaasMix(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setHaasMix(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setDryWet(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setDryWet(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setOutputGainDb(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setOutputGainDb(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setOutputCeilingDb(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setOutputCeilingDb(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setLimiterSafetyMarginDb(JNIEnv*, jclass, jlong h, jfloat v) {
    if (h) engineFrom(h)->setLimiterSafetyMarginDb(v);
}

JNIEXPORT void JNICALL
Java_com_example_peq_WidenerNative_setAutoLevel(JNIEnv*, jclass, jlong h, jboolean v) {
    if (h) engineFrom(h)->setAutoLevel(v == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_example_peq_WidenerNative_latencyFrames(JNIEnv*, jclass, jlong h) {
    if (!h) return 0;
    return static_cast<jint>(engineFrom(h)->latencySamples());
}

JNIEXPORT jint JNICALL
Java_com_example_peq_WidenerNative_process(
        JNIEnv* env, jclass, jlong h, jobject buffer,
        jint frames, jint channels, jboolean isFloat) {
    if (!h || channels != 2) {
        // The Kotlin processor handles non-stereo formats as pass-through.
        return frames > 0 ? frames : 0;
    }

    const jint bytesPerSample = isFloat == JNI_TRUE ? 4 : 2;
    if (!validateBuffer(env, buffer, frames, channels, bytesPerSample)) return 0;

    void* addr = env->GetDirectBufferAddress(buffer);
    if (isFloat == JNI_TRUE) {
        auto* samples = static_cast<float*>(addr);
        for (jint i = 0; i < frames; ++i) {
            float left = samples[i * 2];
            float right = samples[i * 2 + 1];
            engineFrom(h)->process(left, right);
            samples[i * 2] = left;
            samples[i * 2 + 1] = right;
        }
    } else {
        auto* samples = static_cast<std::int16_t*>(addr);
        for (jint i = 0; i < frames; ++i) {
            float left = s16ToFloat(samples[i * 2]);
            float right = s16ToFloat(samples[i * 2 + 1]);
            engineFrom(h)->process(left, right);
            samples[i * 2] = floatToS16(left);
            samples[i * 2 + 1] = floatToS16(right);
        }
    }

    return frames;
}

JNIEXPORT jint JNICALL
Java_com_example_peq_WidenerNative_pendingFrames(JNIEnv*, jclass, jlong h) {
    if (!h) return 0;
    return static_cast<jint>(engineFrom(h)->latencySamples());
}

JNIEXPORT jint JNICALL
Java_com_example_peq_WidenerNative_drain(
        JNIEnv* env, jclass, jlong h, jobject buffer,
        jint maxFrames, jint channels, jboolean isFloat) {
    if (!h || channels != 2 || maxFrames <= 0) return 0;

    const jint bytesPerSample = isFloat == JNI_TRUE ? 4 : 2;
    if (!validateBuffer(env, buffer, maxFrames, channels, bytesPerSample)) return 0;

    const jint pending = std::min(
        maxFrames,
        static_cast<jint>(engineFrom(h)->latencySamples()));

    void* addr = env->GetDirectBufferAddress(buffer);
    if (isFloat == JNI_TRUE) {
        auto* samples = static_cast<float*>(addr);
        for (jint i = 0; i < pending; ++i) {
            float left = 0.0f;
            float right = 0.0f;
            engineFrom(h)->process(left, right);
            samples[i * 2] = left;
            samples[i * 2 + 1] = right;
        }
    } else {
        auto* samples = static_cast<std::int16_t*>(addr);
        for (jint i = 0; i < pending; ++i) {
            float left = 0.0f;
            float right = 0.0f;
            engineFrom(h)->process(left, right);
            samples[i * 2] = floatToS16(left);
            samples[i * 2 + 1] = floatToS16(right);
        }
    }

    return pending;
}

}
