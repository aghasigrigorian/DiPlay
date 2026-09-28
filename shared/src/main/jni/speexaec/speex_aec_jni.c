// JNI bridge to the speexdsp acoustic echo canceller (BSD-licensed, see COPYING in this directory).
#include <jni.h>
#include <stdint.h>
#include "speex/speex_echo.h"

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeCreate(JNIEnv *env, jclass cls,
                                                                 jint frameSize, jint filterLength,
                                                                 jint sampleRate) {
    (void) env; (void) cls;
    if (frameSize <= 0 || filterLength <= frameSize || sampleRate <= 0) return 0;
    SpeexEchoState *state = speex_echo_state_init(frameSize, filterLength);
    if (state == NULL) return 0;
    int rate = sampleRate;
    speex_echo_ctl(state, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
    return (jlong) (intptr_t) state;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeProcess(JNIEnv *env, jclass cls, jlong handle,
                                                                  jshortArray near, jshortArray far,
                                                                  jshortArray out) {
    (void) cls;
    SpeexEchoState *state = (SpeexEchoState *) (intptr_t) handle;
    if (state == NULL) return;
    jshort *n = (*env)->GetShortArrayElements(env, near, NULL);
    jshort *f = (*env)->GetShortArrayElements(env, far, NULL);
    jshort *o = (*env)->GetShortArrayElements(env, out, NULL);
    if (n != NULL && f != NULL && o != NULL) {
        speex_echo_cancellation(state, (const spx_int16_t *) n, (const spx_int16_t *) f, (spx_int16_t *) o);
    }
    if (n != NULL) (*env)->ReleaseShortArrayElements(env, near, n, JNI_ABORT);
    if (f != NULL) (*env)->ReleaseShortArrayElements(env, far, f, JNI_ABORT);
    if (o != NULL) (*env)->ReleaseShortArrayElements(env, out, o, 0);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    SpeexEchoState *state = (SpeexEchoState *) (intptr_t) handle;
    if (state != NULL) speex_echo_state_destroy(state);
}
