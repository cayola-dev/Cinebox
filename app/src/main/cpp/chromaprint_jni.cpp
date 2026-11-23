#include <jni.h>
#include <android/log.h>
#include "chromaprint.h"

#define TAG "Cinebox.Chromaprint"

/**
 * Compressed AcoustID fingerprint of interleaved 16-bit PCM, or null on any Chromaprint failure.
 * Kotlin: com.ashe.cinemabox.music.Fingerprinter.nativeFingerprint(ShortArray, Int, Int): String?
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ashe_cinemabox_music_Fingerprinter_nativeFingerprint(
        JNIEnv *env, jobject /* this */, jshortArray samples, jint length, jint sampleRate, jint channels) {
    if (samples == nullptr || length <= 0 || sampleRate <= 0 || channels <= 0) return nullptr;
    if (length > env->GetArrayLength(samples)) return nullptr;

    ChromaprintContext *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (ctx == nullptr) return nullptr;

    jstring result = nullptr;
    jshort *data = env->GetShortArrayElements(samples, nullptr);
    if (data != nullptr) {
        char *fingerprint = nullptr;
        // chromaprint_feed takes the number of int16 values, all channels counted.
        if (chromaprint_start(ctx, sampleRate, channels)
                && chromaprint_feed(ctx, reinterpret_cast<const int16_t *>(data), length)
                && chromaprint_finish(ctx)
                && chromaprint_get_fingerprint(ctx, &fingerprint)) {
            result = env->NewStringUTF(fingerprint);
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Chromaprint could not fingerprint the audio");
        }
        if (fingerprint != nullptr) chromaprint_dealloc(fingerprint);
        // Read-only use: nothing to copy back.
        env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    }
    chromaprint_free(ctx);
    return result;
}
