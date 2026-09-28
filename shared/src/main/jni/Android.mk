LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

# Software acoustic echo canceller for calls (vendored speexdsp, BSD; see speexaec/COPYING).
include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_aec
LOCAL_SRC_FILES := speexaec/mdf.c speexaec/preprocess.c speexaec/filterbank.c speexaec/fftwrap.c speexaec/kiss_fft.c speexaec/kiss_fftr.c speexaec/speex_aec_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexaec $(LOCAL_PATH)/speexaec/include
LOCAL_CFLAGS := -DHAVE_CONFIG_H -O2 -Wno-unused-function -Wno-unused-variable -Wno-sign-compare
include $(BUILD_SHARED_LIBRARY)
