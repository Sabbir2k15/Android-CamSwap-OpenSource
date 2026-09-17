#include "hook_aaudio.h"
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include "dobby.h"

#define TAG "CamSwap_AudioHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// মূল AAudioStream_read ফাংশন পয়েন্টার
static aaudio_result_t (*orig_AAudioStream_read)(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) = nullptr;

// রিপ্লেসমেন্ট ফাংশন (মাইক্রোফোন ইনপুট ডেটা জিরো/মিউট করা)
static aaudio_result_t fake_AAudioStream_read(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
) {
    aaudio_result_t result = orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanoseconds);

    if (result > 0 && buffer != nullptr) {
        // শুধুমাত্র মাইক্রোফোন ইনপুট হলে ডেটা মিউট করা হবে
        if (AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT) {
            int32_t channelCount = AAudioStream_getChannelCount(stream);
            aaudio_format_t format = AAudioStream_getFormat(stream);

            size_t bytesPerSample = (format == AAUDIO_FORMAT_PCM_FLOAT) ? 4 : 2;
            size_t totalBytes = (size_t)result * channelCount * bytesPerSample;
