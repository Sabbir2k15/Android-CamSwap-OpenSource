package io.github.zensu357.camswap;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;

import io.github.zensu357.camswap.utils.HookUtils;
import io.github.zensu357.camswap.utils.AudioDataProvider;
import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * 麦克风 Hook 处理器 —— 支持三种模式
 * <ul>
 * <li><b>静音模式 (mute)</b>：方案 A，将音频数据替换为全零</li>
 * <li><b>替换模式 (replace)</b>：方案 B，注入本地音频文件的 PCM 数据</li>
 * <li><b>视频同步 (video_sync)</b>：方案 C，从当前视频提取音轨，与视频帧同步播放</li>
 * </ul>
 * <p>
 * 所有 Hook 在执行替换前都会实时检查 {@link ConfigManager#KEY_ENABLE_MIC_HOOK} 配置值，
 * 当配置为 {@code false} 时不做任何操作，保证运行时可热切换。
 */
public class MicrophoneHandler implements ICameraHandler {

    private static final String TAG = "【CS】[Mic]";

    // 方案 B 时长校验：是否已提醒过用户
    private static final AtomicBoolean durationWarningShown = new AtomicBoolean(false);

    // 诊断日志计数器：audioPath 为 null 时限制日志数量
    private static final AtomicInteger audioPathNullLogCount = new AtomicInteger(0);

    // 视频同步模式：记住上次已知的播放位置，避免播放器暂时不可用时回退到 0
    private static final AtomicLong lastKnownPlaybackPositionMs = new AtomicLong(0);

    // 异步加载标记：使用 AtomicBoolean 防止竞态条件导致重复提交加载任务
    private static final AtomicBoolean asyncLoadingInProgress = new AtomicBoolean(false);

    /**
     * 存储每个 AudioRecord 实例的构造参数
     * 使用 ConcurrentHashMap 防止 GC 过早回收导致参数丢失，
     * 在 AudioRecord.release() 时手动清理
     */
    private static final Map<Object, AudioRecordParams> recordParamsMap = new ConcurrentHashMap<>();

    /**
     * AudioRecord 构造参数
     */
    private static class AudioRecordParams {
        final int audioSource;
        final int sampleRate;
        final int channelConfig;
        final int audioFormat;
        final int bufferSize;
        final int channelCount;

        AudioRecordParams(int audioSource, int sampleRate, int channelConfig,
                int audioFormat, int bufferSize) {
            this.audioSource = audioSource;
            this.sampleRate = sampleRate;
            this.channelConfig = channelConfig;
            this.audioFormat = audioFormat;
            this.bufferSize = bufferSize;
            this.channelCount = getChannelCount(channelConfig);
        }

        private static int getChannelCount(int channelConfig) {
            switch (channelConfig) {
                case AudioFormat.CHANNEL_IN_MONO:
                    return 1;
                case AudioFormat.CHANNEL_IN_STEREO:
                    return 2;
                default:
                    return Integer.bitCount(channelConfig);
            }
        }
    }

    // ================================================================
    // 配置读取
    // ================================================================

    private static boolean isMicHookEnabled() {
        try {
            return VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getMicHookMode() {
        try {
            return VideoManager.getConfig().getString(
                    ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE);
        } catch (Exception e) {
            return ConfigManager.MIC_MODE_MUTE;
        }
    }

    // ================================================================
    // 模式判断 + 数据加载
    // ================================================================

    /**
     * 检查是否为替换模式，并确保音频数据已加载。
     * 如果数据尚未就绪，触发异步加载并返回 false（本次 read 用静音填充，下次再替换）。
     */
    private static boolean isReplaceMode() {
        if (!ConfigManager.MIC_MODE_REPLACE.equals(getMicHookMode())) {
            return false;
        }
        String audioPath = AudioDataProvider.getAudioFilePath();
        if (audioPath == null) {
            // 仅在前 3 次打印此日志，避免日志洪泛
            int count = audioPathNullLogCount.getAndIncrement();
            if (count < 3) {
                LogUtil.log(TAG + " ⚠ isReplaceMode: audioPath 为 null，音频文件未找到！"
                        + " video_path=" + VideoManager.video_path);
            }
            return false;
        }

        // 检查是否需要（重新）加载：未就绪 或 文件已切换
        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !audioPath.equals(loadedPath)) {
            // 异步加载，不阻塞音频线程
            final String pathToLoad = audioPath;
            preloadAudioFileAsync(pathToLoad);
            return false; // 数据还没准备好，本次用静音
        }
        return true;
    }

    /**
     * 检查是否为视频同步模式，并确保视频音轨数据已加载。
     * 如果数据尚未就绪，触发异步加载并返回 false。
     */
    private static boolean isVideoSyncMode() {
        if (!ConfigManager.MIC_MODE_VIDEO_SYNC.equals(getMicHookMode())) {
            return false;
        }
        // Stream mode: video_sync is not supported (no local FD to extract audio).
        // Degrade to mute and log.
        if (VideoManager.isStreamMode()) {
            LogUtil.log("【CS】流模式下 video_sync 不可用，自动降级为静音");
            return false;
        }
        String videoPath = VideoManager.getCurrentVideoPath();
        if (videoPath == null)
            return false;

        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !videoPath.equals(loadedPath)) {
            // 异步加载，不阻塞音频线程
            final String pathToLoad = videoPath;
            preloadAudioFileAsync(pathToLoad);
            return false;
        }
        return true;
    }

    /**
     * 异步预加载音频数据（根据当前模式决定加载什么文件）
     */
    private static void preloadAudioAsync() {
        if (!isMicHookEnabled())
            return;
        String mode = getMicHookMode();
        String pathToLoad = null;
        if (ConfigManager.MIC_MODE_REPLACE.equals(mode)) {
            pathToLoad = AudioDataProvider.getAudioFilePath();
        } else if (ConfigManager.MIC_MODE_VIDEO_SYNC.equals(mode)) {
            pathToLoad = VideoManager.getCurrentVideoPath();
        }
        if (pathToLoad != null) {
            preloadAudioFileAsync(pathToLoad);
        }
    }

    /**
     * 在后台线程中加载指定音频文件，避免阻塞音频回调线程。
     * 使用 asyncLoadingInProgress 标记防止重复提交。
     */
    private static void preloadAudioFileAsync(final String filePath) {
        if (filePath == null)
            return;
        // 已经加载了同一文件，无需重复
        if (filePath.equals(AudioDataProvider.getCurrentFilePath()) && AudioDataProvider.isReady()) {
            return;
        }
        if (!asyncLoadingInProgress.compareAndSet(false, true))
            return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LogUtil.log(TAG + " 异步加载音频: " + filePath);
                    AudioDataProvider.loadAudioFile(filePath);
                    durationWarningShown.set(false);
                    checkDurationMismatch();
                    LogUtil.log(TAG + " 异步加载完成: " + filePath
                            + " ready=" + AudioDataProvider.isReady());
                } catch (Exception e) {
                    LogUtil.log(TAG + " 异步加载失败: " + e);
                } finally {
                    asyncLoadingInProgress.set(false);
                }
            }
        }, "CS-AudioPreload").start();
    }

    /**
     * 获取当前视频 MediaPlayer 的播放位置（毫秒）
     * 按优先级尝试所有可能的 MediaPlayer 实例。
     * 当所有播放器都不在播放时，返回上次已知位置而非 0，防止同步失效。
     */
    private static long getVideoPlaybackPositionMs() {
        MediaPlayer[] players = {
                HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
        };
        for (MediaPlayer mp : players) {
            try {
                if (mp != null && mp.isPlaying()) {
                    long pos = mp.getCurrentPosition();
                    lastKnownPlaybackPositionMs.set(pos);
                    return pos;
                }
            } catch (Exception ignored) {
            }
        }
        // 返回上次已知位置，避免在播放器暂时不可用时音频跳回开头
        return lastKnownPlaybackPositionMs.get();
    }

    /**
     * 获取指定 AudioRecord 实例的参数
     */
    private static AudioRecordParams getParams(Object audioRecord) {
        AudioRecordParams params = recordParamsMap.get(audioRecord);
        if (params != null)
            return params;
        // 回退：尝试从 AudioRecord 实例动态获取参数
        try {
            AudioRecord typedRecord = (AudioRecord) audioRecord;
            int sampleRate = typedRecord.getSampleRate();
            int channelConfig = typedRecord.getChannelConfiguration();
            int audioFormat = typedRecord.getAudioFormat();
            params = new AudioRecordParams(0, sampleRate, channelConfig, audioFormat, 4096);
            recordParamsMap.put(audioRecord, params);
            LogUtil.log(TAG + " 动态获取 AudioRecord 参数: sampleRate=" + sampleRate
                    + " channelConfig=" + channelConfig + " audioFormat=" + audioFormat);
            return params;
        } catch (Exception e) {
            LogUtil.log(TAG + " 动态获取参数失败，使用默认值: " + e);
        }
        return new AudioRecordParams(0, 44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096);
    }

    /**
     * 方案 B 时长校验：对比音频文件与视频文件时长
     */
    private static void checkDurationMismatch() {
        if (durationWarningShown.get())
            return;

        try {
            long audioDuration = AudioDataProvider.getDurationMs();
            if (audioDuration <= 0)
                return;

            // 尝试获取视频时长
            long videoDuration = -1;
            MediaPlayer[] players = {
                    HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                    HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
            };
            for (MediaPlayer mp : players) {
                try {
                    if (mp != null) {
                        videoDuration = mp.getDuration();
                        if (videoDuration > 0)
                            break;
                    }
                } catch (Exception ignored) {
                }
            }

            if (videoDuration <= 0)
                return;

            // 超过 2 秒差异时警告
            long diff = Math.abs(audioDuration - videoDuration);
            if (diff > 2000) {
                String msg = "【CS】⚠ 音频文件时长(" + (audioDuration / 1000) + "s)与视频时长("
                        + (videoDuration / 1000) + "s)不一致，可能导致音画不同步";
                LogUtil.log(msg);
                VideoManager.showToast("音频与视频时长不一致: 音频 " + (audioDuration / 1000)
                        + "s, 视频 " + (videoDuration / 1000) + "s");
                durationWarningShown.set(true);
            }
        } catch (Exception e) {
            LogUtil.log(TAG + " 时长校验异常: " + e);
        }
    }

    // ================================================================
    // 供 NativeAudioHook 调用的 package-visible 静态方法
    // ================================================================

    static boolean isMicHookEnabledStatic() {
        return isMicHookEnabled();
    }

    static String getMicHookModeStatic() {
        return getMicHookMode();
    }

    static long getVideoPlaybackPositionMsStatic() {
        return getVideoPlaybackPositionMs();
    }

    static void preloadAudioAsyncStatic() {
        preloadAudioAsync();
    }

    private static void replaceByteArrayResult(Object audioRecord, byte[] buffer, int offset, int result,
            String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        if (isVideoSyncMode()) {
            long posMs = getVideoPlaybackPositionMs();
            int filled = AudioDataProvider.fillBytesAtPosition(buffer, offset, result,
                    p.sampleRate, p.channelCount, posMs);
            if (filled <= 0) {
                // No replacement audio available — block real mic with silence.
                Arrays.fill(buffer, offset, offset + result, (byte) 0);
            }
        } else if (isReplaceMode()) {
            int filled = AudioDataProvider.fillBytes(buffer, offset, result,
                    p.sampleRate, p.channelCount);
            if (filled <= 0) {
                // No replacement audio available — block real mic with silence.
                Arrays.fill(buffer, offset, offset + result, (byte) 0);
            }
        } else {
            Arrays.fill(buffer, offset, offset + result, (byte) 0);
        }
    }

    private static void replaceShortArrayResult(Object audioRecord, short[] buffer, int offset, int result,
            String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        if (isVideoSyncMode()) {
            long posMs = getVideoPlaybackPositionMs();
            int filled = AudioDataProvider.fillShortsAtPosition(buffer, offset, result,
                    p.sampleRate, p.channelCount, posMs);
            if (filled <= 0) {
                Arrays.fill(buffer, offset, offset + result, (short) 0);
            }
        } else if (isReplaceMode()) {
            int filled = AudioDataProvider.fillShorts(buffer, offset, result,
                    p.sampleRate, p.channelCount);
            if (filled <= 0) {
                Arrays.fill(buffer, offset, offset + result, (short) 0);
            }
        } else {
            Arrays.fill(buffer, offset, offset + result, (short) 0);
        }
    }

    // ================================================================
    // Hook 初始化
    // ================================================================

    @Override
    public void init(final Api101PackageContext packageContext) {
        final ClassLoader classLoader = packageContext.classLoader;
        LogUtil.log(TAG + " 初始化麦克风 Hook");

        hookAudioRecordConstructor(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookAudioRecordBuilderBuild(classLoader);
        }
        hookAudioRecordRelease(classLoader);
        hookAudioRecordStartRecording(classLoader);

        hookReadByteArray(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookReadByteArrayReadMode(classLoader);
        }
        hookReadShortArray(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookReadShortArrayReadMode(classLoader);
        }
        hookReadByteBuffer(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookReadFloatArray(classLoader);
            hookReadByteBufferReadMode(classLoader);
        }

        hookMediaRecorderSetAudioSource(classLoader);

        LogUtil.log(TAG + " 麦克风 Hook 初始化完成");
    }

    private void hookAudioRecordConstructor(ClassLoader classLoader) {
        hookConstructor(classLoader, "android.media.AudioRecord",
                new Class<?>[] { int.class, int.class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    try {
                        int audioSource = (int) args[0];
                        int sampleRate = (int) args[1];
                        int channelConfig = (int) args[2];
                        int audioFormat = (int) args[3];
                        int bufferSize = (int) args[4];

                        LogUtil.log(TAG + " AudioRecord 创建: audioSource=" + audioSource
                                + " sampleRate=" + sampleRate
                                + " channelConfig=" + channelConfig
                                + " audioFormat=" + audioFormat
                                + " bufferSize=" + bufferSize);

                        recordParamsMap.put(chain.getThisObject(),
                                new AudioRecordParams(audioSource, sampleRate, channelConfig, audioFormat, bufferSize));
                        preloadAudioAsync();
                    } catch (Throwable t) {
                        LogUtil.log(TAG + " AudioRecord 构造函数 after 异常: " + t);
                    }
                    return result;
                }, "AudioRecord 构造函数");
    }

    private void hookAudioRecordBuilderBuild(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord$Builder", "build", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            Object result = chain.proceed(args);
            if (!(result instanceof AudioRecord)) {
                return result;
            }

            try {
                AudioRecord typedRecord = (AudioRecord) result;
                int sampleRate = typedRecord.getSampleRate();
                int channelConfig = typedRecord.getChannelConfiguration();
                int audioFormat = typedRecord.getAudioFormat();
                int bufferSize = typedRecord.getBufferSizeInFrames();

                LogUtil.log(TAG + " AudioRecord.Builder.build(): sampleRate=" + sampleRate
                        + " channelConfig=" + channelConfig
                        + " audioFormat=" + audioFormat
                        + " bufferSize=" + bufferSize);

                recordParamsMap.put(result,
                        new AudioRecordParams(0, sampleRate, channelConfig, audioFormat, bufferSize));
                preloadAudioAsync();
            } catch (Throwable t) {
                LogUtil.log(TAG + " 获取 Builder 创建的 AudioRecord 参数失败: " + t);
            }
            return result;
        }, "AudioRecord.Builder.build()");
    }

    private void hookAudioRecordRelease(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "release", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            Object result = chain.proceed(args);
            recordParamsMap.remove(chain.getThisObject());
            return result;
        }, "AudioRecord.release()");
    }

    private void hookAudioRecordStartRecording(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "startRecording", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            try {
                LogUtil.log(TAG + " AudioRecord.startRecording() 被调用, micHook="
                        + isMicHookEnabled() + " mode=" + getMicHookMode());
                preloadAudioAsync();
            } catch (Throwable t) {
                LogUtil.log(TAG + " startRecording before 异常: " + t);
            }
            return chain.proceed(args);
        }, "AudioRecord.startRecording()");
    }

    private void hookReadByteArray(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { byte[].class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteArrayResult(chain.getThisObject(), (byte[]) args[0], (int) args[1], intResult(result),
                            "byte[]");
                    return result;
                }, "AudioRecord.read(byte[], int, int)");
    }

    private void hookReadByteArrayReadMode(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { byte[].class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteArrayResult(chain.getThisObject(), (byte[]) args[0], (int) args[1], intResult(result),
                            "byte[](readMode)");
                    return result;
                }, "AudioRecord.read(byte[], int, int, int)");
    }

    private void hookReadShortArray(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { short[].class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceShortArrayResult(chain.getThisObject(), (short[]) args[0], (int) args[1], intResult(result),
                            "short[]");
                    return result;
                }, "AudioRecord.read(short[], int, int)");
    }

    private void hookReadShortArrayReadMode(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { short[].class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceShortArrayResult(chain.getThisObject(), (short[]) args[0], (int) args[1], intResult(result),
                            "short[](readMode)");
                    return result;
                }, "AudioRecord.read(short[], int, int, int)");
    }

    private void hookReadByteBuffer(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { ByteBuffer.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteBufferResult(chain.getThisObject(), (ByteBuffer) args[0], intResult(result), "ByteBuffer");
                    return result;
                }, "AudioRecord.read(ByteBuffer, int)");
    }

    private void hookReadFloatArray(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { float[].class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceFloatArrayResult(chain.getThisObject(), (float[]) args[0], (int) args[1], intResult(result),
                            "float[]");
                    return result;
                }, "AudioRecord.read(float[], int, int, int)");
    }

    private void hookReadByteBufferReadMode(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { ByteBuffer.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteBufferResult(chain.getThisObject(), (ByteBuffer) args[0], intResult(result),
                            "ByteBuffer(int,int)");
                    return result;
                }, "AudioRecord.read(ByteBuffer, int, int)");
    }

    private void hookMediaRecorderSetAudioSource(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.MediaRecorder", "setAudioSource", new Class<?>[] { int.class },
                chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    try {
                        LogUtil.log(TAG + " MediaRecorder.setAudioSource: " + args[0]
                                + " (micHook=" + isMicHookEnabled()
                                + " mode=" + getMicHookMode() + ")");
                    } catch (Throwable t) {
                        LogUtil.log(TAG + " MediaRecorder.setAudioSource before 异常: " + t);
                    }
                    return chain.proceed(args);
                }, "MediaRecorder.setAudioSource(int)");
    }

    private static void replaceByteBufferResult(Object audioRecord, ByteBuffer buffer, int result, String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        int pos = buffer.position();
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        if (isVideoSyncMode()) {
            long posMs = getVideoPlaybackPositionMs();
            buffer.position(pos - result);
            int filled = AudioDataProvider.fillByteBufferAtPosition(buffer, result,
                    p.sampleRate, p.channelCount, posMs);
            if (filled <= 0) {
                buffer.position(pos - result);
                buffer.put(new byte[result]);
            }
            buffer.position(pos);
        } else if (isReplaceMode()) {
            buffer.position(pos - result);
            int filled = AudioDataProvider.fillByteBuffer(buffer, result,
                    p.sampleRate, p.channelCount);
            if (filled <= 0) {
                buffer.position(pos - result);
                buffer.put(new byte[result]);
            }
            buffer.position(pos);
        } else {
            byte[] zeros = new byte[result];
            buffer.position(pos - result);
            buffer.put(zeros);
            buffer.position(pos);
        }
    }

    private static void replaceFloatArrayResult(Object audioRecord, float[] buffer, int offset, int result,
            String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        if (isVideoSyncMode()) {
            long posMs = getVideoPlaybackPositionMs();
            int filled = AudioDataProvider.fillFloatsAtPosition(buffer, offset, result,
                    p.sampleRate, p.channelCount, posMs);
            if (filled <= 0) {
                Arrays.fill(buffer, offset, offset + result, 0.0f);
            }
        } else if (isReplaceMode()) {
            int filled = AudioDataProvider.fillFloats(buffer, offset, result,
                    p.sampleRate, p.channelCount);
            if (filled <= 0) {
                Arrays.fill(buffer, offset, offset + result, 0.0f);
            }
        } else {
            Arrays.fill(buffer, offset, offset + result, 0.0f);
        }
    }

    private void hookMethod(ClassLoader classLoader, String className, String methodName, Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker, String label) {
        try {
            Method method = resolveMethod(classLoader, className, methodName, parameterTypes);
            Api101Runtime.requireModule().hook(method).intercept(hooker);
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook " + label + " 失败: " + t);
        }
    }

    private void hookConstructor(ClassLoader classLoader, String className, Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker, String label) {
        try {
            Constructor<?> constructor = resolveConstructor(classLoader, className, parameterTypes);
            Api101Runtime.requireModule().hook(constructor).intercept(hooker);
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook " + label + " 失败: " + t);
        }
    }

    private static Method resolveMethod(ClassLoader classLoader, String className, String methodName,
            Class<?>... parameterTypes) throws Exception {
        return HookUtils.resolveMethod(classLoader, className, methodName, parameterTypes);
    }

    private static Constructor<?> resolveConstructor(ClassLoader classLoader, String className,
            Class<?>... parameterTypes) throws Exception {
        Class<?> clazz = Class.forName(className, false, classLoader);
        return HookUtils.resolveConstructor(clazz, parameterTypes);
    }

    private static Object[] toArgs(List<Object> args) {
        return HookUtils.toArgs(args);
    }

    private static int intResult(Object result) {
        return result instanceof Integer ? (Integer) result : 0;
    }

    private static volatile int readHookCount = 0;

    private static void logReadCall(int result, String method) {
        if (readHookCount < 10) {
            readHookCount++;
            LogUtil.log(TAG + " AudioRecord.read(" + method + ") 被调用 #" + readHookCount
                    + " result=" + result + " micHookEnabled=" + isMicHookEnabled()
                    + " mode=" + getMicHookMode());
            if (readHookCount == 10) {
                LogUtil.log(TAG + " AudioRecord.read 调用日志已达到 10 次上限，后续调用不再打印。");
            }
        }
    }
}
