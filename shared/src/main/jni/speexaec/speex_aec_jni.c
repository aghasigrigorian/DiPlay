// JNI bridge to the speexdsp acoustic echo canceller and its residual-echo/noise preprocessor
// (BSD-licensed, see COPYING in this directory).
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

typedef struct {
    SpeexEchoState *echo;
    SpeexPreprocessState *preprocess;
} aec_handle;

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeCreate(JNIEnv *env, jclass cls,
                                                                 jint frameSize, jint filterLength,
                                                                 jint sampleRate) {
    (void) env; (void) cls;
    if (frameSize <= 0 || filterLength <= frameSize || sampleRate <= 0) return 0;
    aec_handle *h = (aec_handle *) calloc(1, sizeof(aec_handle));
    if (h == NULL) return 0;
    h->echo = speex_echo_state_init(frameSize, filterLength);
    if (h->echo == NULL) { free(h); return 0; }
    int rate = sampleRate;
    speex_echo_ctl(h->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
    h->preprocess = speex_preprocess_state_init(frameSize, sampleRate);
    if (h->preprocess != NULL) {
        int on = 1, suppress = -60, suppressActive = -40;
        speex_preprocess_ctl(h->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, h->echo);
        speex_preprocess_ctl(h->preprocess, SPEEX_PREPROCESS_SET_DENOISE, &on);
        speex_preprocess_ctl(h->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &suppress);
        speex_preprocess_ctl(h->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &suppressActive);
    }
    return (jlong) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeProcess(JNIEnv *env, jclass cls, jlong handle,
                                                                  jshortArray near, jshortArray far,
                                                                  jshortArray out) {
    (void) cls;
    aec_handle *h = (aec_handle *) (intptr_t) handle;
    if (h == NULL || h->echo == NULL) return;
    jshort *n = (*env)->GetShortArrayElements(env, near, NULL);
    jshort *f = (*env)->GetShortArrayElements(env, far, NULL);
    jshort *o = (*env)->GetShortArrayElements(env, out, NULL);
    if (n != NULL && f != NULL && o != NULL) {
        speex_echo_cancellation(h->echo, (const spx_int16_t *) n, (const spx_int16_t *) f, (spx_int16_t *) o);
        if (h->preprocess != NULL) speex_preprocess_run(h->preprocess, (spx_int16_t *) o);
    }
    if (n != NULL) (*env)->ReleaseShortArrayElements(env, near, n, JNI_ABORT);
    if (f != NULL) (*env)->ReleaseShortArrayElements(env, far, f, JNI_ABORT);
    if (o != NULL) (*env)->ReleaseShortArrayElements(env, out, o, 0);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    aec_handle *h = (aec_handle *) (intptr_t) handle;
    if (h == NULL) return;
    if (h->preprocess != NULL) speex_preprocess_state_destroy(h->preprocess);
    if (h->echo != NULL) speex_echo_state_destroy(h->echo);
    free(h);
}
