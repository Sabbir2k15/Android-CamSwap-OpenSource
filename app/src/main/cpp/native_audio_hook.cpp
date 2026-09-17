#include "native_audio_hook.h"
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include "dobby.h"

#define TAG "CamSwap_NativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// pcm_bridge.cpp-এর জন্য get_java_vm ডিফাইন করা
static JavaVM* g_jvm = nullptr;

extern "C" JavaVM* get_java_vm() {
    return g_jvm;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    init_native_audio_hook();
    return JNI_VERSION_1_6;
}

static aaudio_result_t (*orig_AAudioStream_read)(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) = nullptr;

static aaudio_direction_t (*native_getDirection)(AAudioStream* stream) = nullptr;
static int32_t (*native_getChannelCount)(AAudioStream* stream) = nullptr;
static aaudio_format_t (*native_getFormat)(AAudioStream* stream) = nullptr;

static aaudio_result_t fake_AAudioStream_read(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) {
    aaudio_result_t result = orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanoseconds);

    if (result > 0 && buffer != nullptr) {
        bool isInput = true;
        if (native_getDirection) {
            isInput = (native_getDirection(stream) == AAUDIO_DIRECTION_INPUT);
        }

        if (isInput) {
            int32_t channelCount = 1;
            if (native_getChannelCount) {
                channelCount = native_getChannelCount(stream);
            }

            size_t bytesPerSample = 2;
            if (native_getFormat && native_getFormat(stream) == AAUDIO_FORMAT_PCM_FLOAT) {
                bytesPerSample = 4;
            }

            size_t totalBytes = (size_t)result * channelCount * bytesPerSample;
            memset(buffer, 0, totalBytes);
        }
    }
    return result;
}

void init_native_audio_hook() {
    LOGI("Initializing Native Audio Hooks...");

    void* aaudio_handle = dlopen("libaaudio.so", RTLD_NOW);
    if (aaudio_handle) {
        native_getDirection = (aaudio_direction_t (*)(AAudioStream*))dlsym(aaudio_handle, "AAudioStream_getDirection");
        native_getChannelCount = (int32_t (*)(AAudioStream*))dlsym(aaudio_handle, "AAudioStream_getChannelCount");
        native_getFormat = (aaudio_format_t (*)(AAudioStream*))dlsym(aaudio_handle, "AAudioStream_getFormat");

        void* sym_read = dlsym(aaudio_handle, "AAudioStream_read");
        if (sym_read) {
            DobbyHook(sym_read, (dobby_dummy_func_t)fake_AAudioStream_read, (dobby_dummy_func_t*)&orig_AAudioStream_read);
            LOGI("AAudioStream_read hooked successfully");
        } else {
            LOGE("Symbol AAudioStream_read not found");
        }
    } else {
        LOGE("libaaudio.so could not be opened");
    }
}
