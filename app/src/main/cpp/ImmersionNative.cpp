#include <jni.h>
#include <cstdint>
extern "C"{void* immersion_create();void immersion_destroy(void*);void immersion_prepare(void*,int);void immersion_reset(void*);void immersion_enabled(void*,int);void immersion_space(void*,float);void immersion_ambience(void*,float);void immersion_air(void*,float);void immersion_bass(void*,float);void immersion_profile(void*,float);void immersion_process(void*,const float*,float*,int);}
static void*p(jlong h){return reinterpret_cast<void*>(static_cast<uintptr_t>(h));}
extern "C" JNIEXPORT jlong JNICALL Java_com_example_audio_ImmersionNative_create(JNIEnv*,jclass){return (jlong)(uintptr_t)immersion_create();}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_destroy(JNIEnv*,jclass,jlong h){immersion_destroy(p(h));}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_prepare(JNIEnv*,jclass,jlong h,jint sr){immersion_prepare(p(h),sr);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_reset(JNIEnv*,jclass,jlong h){immersion_reset(p(h));}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setEnabled(JNIEnv*,jclass,jlong h,jboolean v){immersion_enabled(p(h),v?1:0);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setSpace(JNIEnv*,jclass,jlong h,jfloat v){immersion_space(p(h),v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setAmbience(JNIEnv*,jclass,jlong h,jfloat v){immersion_ambience(p(h),v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setAir(JNIEnv*,jclass,jlong h,jfloat v){immersion_air(p(h),v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setBass(JNIEnv*,jclass,jlong h,jfloat v){immersion_bass(p(h),v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_setProfile(JNIEnv*,jclass,jlong h,jfloat v){immersion_profile(p(h),v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_audio_ImmersionNative_process(JNIEnv*e,jclass,jlong h,jobject in,jobject out,jint n){auto*a=(float*)e->GetDirectBufferAddress(in);auto*b=(float*)e->GetDirectBufferAddress(out);if(a&&b)immersion_process(p(h),a,b,n);}
