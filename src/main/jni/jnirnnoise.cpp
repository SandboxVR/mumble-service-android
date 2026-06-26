#include <jni.h>
#include <stdint.h>

#include <algorithm>

extern "C" {
#include <rnnoise.h>
}

static DenoiseState *ptr_from_jlong(jlong value) {
    return reinterpret_cast<DenoiseState *>(static_cast<uintptr_t>(value));
}

static jlong ptr_to_jlong(DenoiseState *value) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(value));
}

static void throw_exception(JNIEnv *env, const char *class_name, const char *message) {
    jclass cls = env->FindClass(class_name);
    if (cls != NULL) {
        env->ThrowNew(cls, message);
    }
}

extern "C" {

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_RnNoise_getFrameSizeNative(JNIEnv *, jclass) {
    return rnnoise_get_frame_size();
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_RnNoise_createNative(JNIEnv *env, jclass) {
    DenoiseState *state = rnnoise_create(NULL);
    if (state == NULL) {
        throw_exception(env, "java/lang/IllegalStateException", "RNNoise initialization failed.");
        return 0;
    }
    return ptr_to_jlong(state);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_RnNoise_destroyNative(JNIEnv *, jclass, jlong state) {
    DenoiseState *denoise_state = ptr_from_jlong(state);
    if (denoise_state != NULL) {
        rnnoise_destroy(denoise_state);
    }
}

JNIEXPORT jfloat JNICALL
Java_se_lublin_humla_audio_RnNoise_processFrameNative(JNIEnv *env, jclass, jlong state,
                                                      jshortArray frame, jint frame_size) {
    DenoiseState *denoise_state = ptr_from_jlong(state);
    if (denoise_state == NULL) {
        throw_exception(env, "java/lang/IllegalStateException", "RNNoise state is destroyed.");
        return 0.0f;
    }

    const int rnnoise_frame_size = rnnoise_get_frame_size();
    if (frame_size != rnnoise_frame_size) {
        throw_exception(env, "java/lang/IllegalArgumentException",
                        "RNNoise requires 480-sample frames at 48000 Hz.");
        return 0.0f;
    }

    if (env->GetArrayLength(frame) < frame_size) {
        throw_exception(env, "java/lang/IllegalArgumentException",
                        "Audio frame is smaller than the requested frame size.");
        return 0.0f;
    }

    jshort *samples = env->GetShortArrayElements(frame, NULL);
    if (samples == NULL) {
        throw_exception(env, "java/lang/IllegalStateException", "Unable to access audio frame.");
        return 0.0f;
    }

    float pcm[480];
    for (int i = 0; i < rnnoise_frame_size; i++) {
        pcm[i] = static_cast<float>(samples[i]);
    }

    float voice_probability = rnnoise_process_frame(denoise_state, pcm, pcm);

    for (int i = 0; i < rnnoise_frame_size; i++) {
        float clamped = std::max(-32768.0f, std::min(32767.0f, pcm[i]));
        samples[i] = static_cast<jshort>(clamped);
    }

    env->ReleaseShortArrayElements(frame, samples, 0);
    return voice_probability;
}

}
