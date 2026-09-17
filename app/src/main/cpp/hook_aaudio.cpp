#include "hook_aaudio.h"
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include "dobby.h"

#define TAG "CamSwap_AudioHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static aaudio_result_t (*orig_AAudioStream_read)(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) = nullptr;

static aaudio_direction_t (*p_AAudioStream_getDirection)(AAudioStream* stream) = nullptr;
static int32_t (*p_AAudioStream_getChannelCount)(AAudioStream* stream) = nullptr;
static aaudio_format_t (*p_AAudioStream_getFormat)(AAudioStream* stream) = nullptr;

static aaudio_result_t fake_AAudioStream_read(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) {
    aaudio_result_t result = orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanoseconds);

    if (result > 0 && buffer != nullptr) {
        bool isInput = true;
        if (p_AAudioStream_getDirection != nullptr) {
            isInput = (p_AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT);
        }

        if (isInput) {
            int32_t channelCount = 1;
            if (p_AAudioStream_getChannelCount != nullptr) {
                channelCount = p_AAudioStream_getChannelCount(stream);
            }

            size_t bytesPerSample = 2;
            if (p_AAudioStream_getFormat != nullptr && p_AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_FLOAT) {
                bytesPerSample = 4;
            }

            size_t totalBytes = (size_t)result * (size_t)channelCount * bytesPerSample;
            memset(buffer, 0, totalBytes);
        }
    }
    return result;
}

void init_aaudio_hook() {
    LOGI("Initializing AAudio Hook via Dobby...");

    void* handle = dlopen("libaaudio.so", RTLD_NOW);
    if (!handle) {
        LOGE("Failed to open libaaudio.so");
        return;
    }

    p_AAudioStream_getDirection = (aaudio_direction_t (*)(AAudioStream*))dlsym(handle, "AAudioStream_getDirection");
    p_AAudioStream_getChannelCount = (int32_t (*)(AAudioStream*))dlsym(handle, "AAudioStream_getChannelCount");
    p_AAudioStream_getFormat = (aaudio_format_t (*)(AAudioStream*))dlsym(handle, "AAudioStream_getFormat");

    void* sym_read = dlsym(handle, "AAudioStream_read");
    if (sym_read) {
        DobbyHook(sym_read, (dobby_dummy_func_t)fake_AAudioStream_read, (dobby_dummy_func_t*)&orig_AAudioStream_read);
        LOGI("Successfully hooked AAudioStream_read with Dobby");
    } else {
        LOGE("Failed to locate AAudioStream_read symbol");
    }
}
