#include "native_audio_hook.h"
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include "dobby.h"

#define TAG "CamSwap_NativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// AAudioStream_read অরিজিনাল পয়েন্টার
static aaudio_result_t (*orig_AAudioStream_read)(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) = nullptr;

// AAudio রিড হুক - ইনপুট ডেটা সাইলেন্ট/জিরো করা
static aaudio_result_t fake_AAudioStream_read(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) {
    aaudio_result_t result = orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanoseconds);

    if (result > 0 && buffer != nullptr) {
        if (AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT) {
            int32_t channelCount = AAudioStream_getChannelCount(stream);
            aaudio_format_t format = AAudioStream_getFormat(stream);

            size_t bytesPerSample = (format == AAUDIO_FORMAT_PCM_FLOAT) ? 4 : 2;
            size_t totalBytes = (size_t)result * channelCount * bytesPerSample;

            memset(buffer, 0, totalBytes);
        }
    }
    return result;
}

// OpenSL ES Buffer Queue Callback হুকিং সাপোর্ট
typedef void (*slAndroidSimpleBufferQueueCallback)(void* caller, void* context);

void init_native_audio_hook() {
    LOGI("Initializing Native Audio Hooks...");

    // ১. AAudio Hook
    void* aaudio_handle = dlopen("libaaudio.so", RTLD_NOW);
    if (aaudio_handle) {
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

    // ২. OpenSL ES লাইব্রেরি হ্যান্ডেল চেক
    void* opensles_handle = dlopen("libOpenSLES.so", RTLD_NOW);
    if (opensles_handle) {
        LOGI("libOpenSLES.so loaded successfully");
    }
}
