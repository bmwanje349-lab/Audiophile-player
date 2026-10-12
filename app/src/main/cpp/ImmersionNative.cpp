#include <jni.h>
#include <algorithm>
#include <cstddef>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <cstdint>
#include <vector>

extern "C" {
int immersion_load_data(const uint8_t* hrir, std::size_t hrirSize,
                        const uint8_t* brir, std::size_t brirSize);
void* immersion_create();
void immersion_destroy(void*);
void immersion_prepare(void*, int);
void immersion_reset(void*);
void immersion_enabled(void*, int);
void immersion_space(void*, float);
void immersion_ambience(void*, float);
void immersion_air(void*, float);
void immersion_bass(void*, float);
void immersion_profile(void*, float);
void immersion_process(void*, const float*, float*, int);
}

namespace {
void* handle(jlong value) noexcept {
    return reinterpret_cast<void*>(static_cast<uintptr_t>(value));
}

bool readAsset(AAssetManager* manager, const char* name, std::vector<uint8_t>& bytes) {
    AAsset* asset = AAssetManager_open(manager, name, AASSET_MODE_STREAMING);
    if (!asset) return false;
    const off_t length = AAsset_getLength(asset);
    if (length <= 0 || static_cast<uint64_t>(length) > (64ULL * 1024ULL * 1024ULL)) {
        AAsset_close(asset);
        return false;
    }
    try {
        bytes.resize(static_cast<std::size_t>(length));
    } catch (...) {
        AAsset_close(asset);
        return false;
    }
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const int chunk = static_cast<int>(std::min<std::size_t>(bytes.size() - offset, 1024 * 1024));
        const int got = AAsset_read(asset, bytes.data() + offset, static_cast<size_t>(chunk));
        if (got <= 0) {
            AAsset_close(asset);
            bytes.clear();
            return false;
        }
        offset += static_cast<std::size_t>(got);
    }
    AAsset_close(asset);
    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_audio_ImmersionNative_loadAssets(JNIEnv* env, jclass, jobject assetManagerObject) {
    AAssetManager* manager = AAssetManager_fromJava(env, assetManagerObject);
    if (!manager) return JNI_FALSE;
    std::vector<uint8_t> hrir;
    std::vector<uint8_t> brir;
    if (!readAsset(manager, "immersion/hrir_d1_48k_256.bin", hrir) ||
        !readAsset(manager, "immersion/brir_d1_48k_16384.bin", brir)) return JNI_FALSE;
    return immersion_load_data(hrir.data(), hrir.size(), brir.data(), brir.size()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_audio_ImmersionNative_create(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(immersion_create()));
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_destroy(JNIEnv*, jclass, jlong h) {
    immersion_destroy(handle(h));
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_prepare(JNIEnv*, jclass, jlong h, jint sr) {
    immersion_prepare(handle(h), sr);
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_reset(JNIEnv*, jclass, jlong h) {
    immersion_reset(handle(h));
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setEnabled(JNIEnv*, jclass, jlong h, jboolean v) {
    immersion_enabled(handle(h), v ? 1 : 0);
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setSpace(JNIEnv*, jclass, jlong h, jfloat v) {
    immersion_space(handle(h), v);
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setAmbience(JNIEnv*, jclass, jlong h, jfloat v) {
    immersion_ambience(handle(h), v);
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setAir(JNIEnv*, jclass, jlong h, jfloat v) {
    immersion_air(handle(h), v);
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setBass(JNIEnv*, jclass, jlong h, jfloat v) {
    immersion_bass(handle(h), v);
}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setProfile(JNIEnv*, jclass, jlong h, jfloat v) {
    immersion_profile(handle(h), v);
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_audio_ImmersionNative_process(JNIEnv* env, jclass, jlong h, jobject in, jobject out, jint frames) {
    auto* input = static_cast<float*>(env->GetDirectBufferAddress(in));
    auto* output = static_cast<float*>(env->GetDirectBufferAddress(out));
    if (input && output) immersion_process(handle(h), input, output, frames);
}
