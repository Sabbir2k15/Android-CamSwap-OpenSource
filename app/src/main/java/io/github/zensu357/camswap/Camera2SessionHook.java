package io.github.zensu357.camswap;

import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.ArrayList;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Map;
import android.media.ImageWriter;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

import io.github.zensu357.camswap.api101.Api101Runtime;

/**
 * Handles Camera2 session interception: replaces real camera surfaces with
 * a virtual surface, then starts video playback via {@link MediaPlayerManager}.
 */
public final class Camera2SessionHook {
    private static final int WHATSAPP_YUV_BRIDGE_MAX_IMAGES = 8;

    /**
     * YUV 帧缓存刷新间隔（毫秒）。
     * GL 渲染器可用时使用较短间隔以保持画面流畅；
     * 回退到 MediaMetadataRetriever 时自动使用较长间隔，避免阻塞泵线程。
     * 高分辨率（>720p）使用更长间隔以避免 CPU 过载。
     */
    private static final long YUV_CACHE_REFRESH_GL_MS = 66L;
    private static final long YUV_CACHE_REFRESH_GL_HIRES_MS = 133L;
    private static final long YUV_CACHE_REFRESH_FALLBACK_MS = 200L;
    /** MediaCodec 直出路径刷新间隔：无 RGB 转换，可以更频繁 */
    private static final long YUV_CACHE_REFRESH_CODEC_MS = 33L;
    /** 高分辨率阈值：超过此像素数时使用低帧率泵 */
    private static final int YUV_HIRES_PIXEL_THRESHOLD = 1280 * 720;

    private final MediaPlayerManager playerManager;
    private volatile String currentPackageName;
    private volatile String currentActivityClassName;

    // Camera2 surfaces
    Surface previewSurface;
    Surface previewSurface1;
    Surface readerSurface;
    Surface readerSurface1;

    // Tracker for Photo Fake
    public final Set<Surface> trackedReaderSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    public final Set<Long> trackedReaderNativeHandles = Collections
            .newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    public final Map<Surface, ImageWriter> imageWriterMap = new ConcurrentHashMap<>();
    public final Map<Surface, Integer> surfaceFormatMap = new ConcurrentHashMap<>();
    public final Map<Long, Integer> surfaceNativeFormatMap = new ConcurrentHashMap<>();
    public final Map<Surface, int[]> surfaceSizeMap = new ConcurrentHashMap<>();
    public final Map<Long, int[]> surfaceNativeSizeMap = new ConcurrentHashMap<>();
    private final Map<Surface, CachedYuvFrame> cachedYuvFrameMap = new ConcurrentHashMap<>();
    private final Map<Surface, FakeYuvBridge> fakeYuvBridgeMap = new ConcurrentHashMap<>();
    private final Set<Surface> internalFakeYuvReaderSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    public final Set<Surface> pendingJpegSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    private final Set<String> hookedStateCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> hookedDeviceClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> hookedSessionCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile long lastWhatsAppYuvPlaceholderLogMs = 0L;
    private volatile long lastNoGlRendererLogMs = 0L;
    private volatile long lastGlBlackFallbackLogMs = 0L;
    private volatile long lastGlNullFallbackLogMs = 0L;
    private volatile long lastYuvKeepLogMs = 0L;
    /** 上一次 YUV 帧是否通过回退路径（视频文件截帧）生成 */
    private volatile boolean lastYuvFrameWasFallback = true;
    /** 上一次 YUV 帧是否通过 MediaCodec 直出路径生成 */
    private volatile boolean lastYuvFrameWasCodec = false;
    /** YUV 帧率统计 */
    private volatile int yuvFrameCount = 0;
    private volatile long yuvFpsWindowStartMs = 0L;
    private final Map<ImageReader, YuvCallbackPump> whatsappYuvPumpMap = new ConcurrentHashMap<>();
    private HandlerThread whatsappYuvPumpThread;
    private Handler whatsappYuvPumpHandler;

    // Photo Fake: 等待 build() 时触发
    /** YUV surfaces that were kept (not replaced) in the current session's OutputConfigurations. */
    private final Set<Surface> sessionKeptYuvSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());

    public volatile Surface pendingPhotoSurface;
    private volatile boolean bypassCurrentSession = false;
    /** 标记正在释放资源，防止释放期间竞态创建新桥接 */
    private volatile boolean isReleasing = false;

    // Deferred playback: set when build() fires before addTarget()
    volatile boolean pendingPlayback = false;
    private volatile boolean yuvBridgeSessionReady = false;
    /** MediaCodec 直出 YUV 解码器，绕过 GL→Bitmap→RGB→YUV 转换链 */
    public volatile MediaCodecYuvDecoder yuvDecoder;

    // Surface-change tracking: skip redundant initCamera2Players when surfaces unchanged
    private Surface lastInitReader, lastInitReader1, lastInitPreview, lastInitPreview1;

    // Virtual surface pool for session hijacking (supports multiple OutputConfigurations)
    private final List<Surface> virtualSurfaces = new ArrayList<>();
    private final List<SurfaceTexture> virtualTextures = new ArrayList<>();
    private final Map<Surface, Surface> originalToVirtualMap = new ConcurrentHashMap<>();
    private HandlerThread virtualSurfaceDrainThread;
    private Handler virtualSurfaceDrainHandler;
    private boolean needRecreate;
    private final ThreadLocal<Integer> fakeYuvAcquireDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };
    private final ThreadLocal<Integer> internalBridgeCreationDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    /** Check if a surface is one of CamSwap's virtual surfaces. */
    public synchronized boolean isVirtualSurface(Surface surface) {
        return surface != null && virtualSurfaces.contains(surface);
    }

    /** Public accessor for Camera2Handler to check/redirect surfaces. */
    public Surface getVirtualSurface() {
        return createVirtualSurface(0);
    }

    public final Map<Long, Surface> originalNativeToVirtualMap = new ConcurrentHashMap<>();

    /** Get the matched virtual surface for the original target surface. */
    public Surface getVirtualSurfaceFor(Surface originalSurface) {
        if (originalSurface != null) {
            Surface vs = originalToVirtualMap.get(originalSurface);
            if (vs != null && vs.isValid()) {
                return vs;
            }
            long handle = getSurfaceNativeHandle(originalSurface);
            if (handle != 0L) {
                vs = originalNativeToVirtualMap.get(handle);
                if (vs != null && vs.isValid()) {
                    return vs;
                }
            }
            // 如果会话中已经有明确的映射规则，但该 Surface 未被重定向（说明被保留为真实会话目标），
            // 则绝不能强行返回 virtualSurface，必须返回 null 以保持原始输出！
            if (!originalToVirtualMap.isEmpty() || !originalNativeToVirtualMap.isEmpty()) {
                return null;
            }
            // 若会话尚未建立或无映射且该 Surface 是明显的预览 SurfaceTexture，才兜底生成 virtualSurface
            if (isSurfaceTextureSurface(originalSurface)) {
                return getVirtualSurface();
            }
        }
        return null;
    }

    /** Mark virtual surface for recreation on next session creation. */
    public synchronized void invalidateVirtualSurface() {
        needRecreate = true;
    }

    /**
     * Ensure the virtual surface exists. Called lazily from addTarget/session hooks
     * in case onOpened hook didn't fire (ART optimization on obfuscated classes).
     */
    public Surface ensureVirtualSurface() {
        return createVirtualSurface(0);
    }

    // Session config
    CaptureRequest.Builder captureBuilder;
    SessionConfiguration fakeSessionConfig;
    SessionConfiguration realSessionConfig;
    OutputConfiguration outputConfig;
    boolean isFirstHookBuild = true;

    public Camera2SessionHook(MediaPlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    public void setCurrentPackageName(String packageName) {
        currentPackageName = packageName;
        playerManager.setPackageName(packageName);
    }

    public void setCurrentActivityClassName(String activityClassName) {
        currentActivityClassName = activityClassName;
    }

    public static boolean isWhatsAppPackage(String packageName) {
        return packageName != null && packageName.toLowerCase(Locale.ROOT).contains("whatsapp");
    }

    public static boolean isLinePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.equals("jp.naver.line.android")
                || normalized.startsWith("jp.naver.line.android:");
    }

    public static boolean shouldUseFakeYuvBridgeForPackage(String packageName) {
        return isWhatsAppPackage(packageName) || isLinePackage(packageName);
    }

    private boolean isVoipActivity() {
        if (currentActivityClassName == null) {
            return false;
        }
        String lower = currentActivityClassName.toLowerCase(Locale.ROOT);
        return lower.contains("voipserviceactivity")
                || lower.contains("freecall")
                || lower.contains("groupcall")
                || lower.contains("voip2");
    }

    private boolean isLineScannerActivity() {
        return currentActivityClassName != null
                && currentActivityClassName.contains("CameraScannerActivity");
    }

    private boolean shouldUseYuvPumpForSurface(Surface surface) {
        if (surface == null || !isYuvReaderSurface(surface)) {
            return false;
        }
        if (shouldUseFakeYuvBridgeForPackage(getCurrentPackageName())) {
            return true;
        }
        return isCurrentSessionYuvReaderSurface(surface);
    }

    public String getCurrentPackageName() {
        if (currentPackageName != null && !currentPackageName.isEmpty()) {
            return currentPackageName;
        }
        return HookMain.toast_content != null ? HookMain.toast_content.getPackageName() : null;
    }

    public boolean isCurrentSessionBypassed() {
        return bypassCurrentSession;
    }

    private void setBypassCurrentSession(boolean bypass) {
        bypassCurrentSession = bypass;
    }

    /** Called by Camera2Handler when onOpened fires on the state callback class. */
    public void hookStateCallback(Class<?> hookedClass) {
        if (hookedClass == null) {
            return;
        }
        if (!hookedStateCallbackClasses.add(hookedClass.getName())) {
            return;
        }

        // onOpened
        try {
            Method m = resolveMethodOnClass(hookedClass, "onOpened", CameraDevice.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    isReleasing = false;
                    needRecreate = true;
                    createVirtualSurface();
                    playerManager.releaseCamera2Resources();
                    releaseImageWriters(false);
                    previewSurface1 = null;
                    readerSurface1 = null;
                    readerSurface = null;
                    previewSurface = null;
                    pendingPlayback = false;
                    captureBuilder = null;
                    setBypassCurrentSession(false);
                    yuvBridgeSessionReady = false;
                    isFirstHookBuild = true;
                    lastInitReader = null;
                    lastInitReader1 = null;
                    lastInitPreview = null;
                    lastInitPreview1 = null;
                    LogUtil.log("【CS】打开相机C2");

                    File file = HookGuards.getCurrentVideoFile();
                    // If video not found, provider may have started since init —
                    // retry once before giving up
                    if (!file.exists() && !VideoManager.isUsingProviderBackedVideo()) {
                        VideoManager.checkProviderAvailability();
                        if (VideoManager.isProviderAvailable()) {
                            VideoManager.getConfig().forceReload();
                            VideoManager.updateVideoPath(false);
                            file = HookGuards.getCurrentVideoFile();
                            LogUtil.log("【CS】onOpened 延迟获取 Provider 成功");
                        }
                    }
                    if (HookGuards.shouldBypass(getCurrentPackageName(), file)) {
                        return chain.proceed(args);
                    }

                    hookAllCreateSessionVariants(args[0].getClass());
                } catch (Throwable t) {
                    LogUtil.log("【CS】onOpened before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onOpened 失败: " + t);
        }

        // onClosed
        try {
            Method m = resolveMethodOnClass(hookedClass, "onClosed", CameraDevice.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】相机关闭 onClosed，释放播放器资源");
                    setBypassCurrentSession(false);
                    yuvBridgeSessionReady = false;
                    stopAllWhatsAppYuvPumps();
                    playerManager.releaseCamera2Resources();
                    releaseImageWriters();
                } catch (Throwable t) {
                    LogUtil.log("【CS】onClosed before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onClosed 失败: " + t);
        }

        // onError
        try {
            Method m = resolveMethodOnClass(hookedClass, "onError", CameraDevice.class, int.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】相机错误onerror：" + (int) args[1]);
                } catch (Throwable t) {
                    LogUtil.log("【CS】onError before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onError 失败: " + t);
        }

        // onDisconnected
        try {
            Method m = resolveMethodOnClass(hookedClass, "onDisconnected", CameraDevice.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】相机断开 onDisconnected，释放播放器资源");
                    setBypassCurrentSession(false);
                    yuvBridgeSessionReady = false;
                    stopAllWhatsAppYuvPumps();
                    playerManager.releaseCamera2Resources();
                    releaseImageWriters();
                } catch (Throwable t) {
                    LogUtil.log("【CS】onDisconnected before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onDisconnected 失败: " + t);
        }
    }

    /** Start video playback on all current surfaces. */
    public void startPlayback() {
        if (readerSurface == null && readerSurface1 == null
                && previewSurface == null && previewSurface1 == null) {
            LogUtil.w("【CS】【SessionHook】【延迟播放】所有播放目标 Surface 均为 null，等待 addTarget 分发..."
                    + " [preview=" + previewSurface + ", preview1=" + previewSurface1
                    + ", reader=" + readerSurface + ", reader1=" + readerSurface1 + "]");
            pendingPlayback = true;
            return;
        }
        // Skip redundant init when surfaces haven't changed since last call
        if (readerSurface == lastInitReader && readerSurface1 == lastInitReader1
                && previewSurface == lastInitPreview && previewSurface1 == lastInitPreview1) {
            LogUtil.log("【CS】【SessionHook】跳过重复 startPlayback：所有 Surface 均未发生改变");
            pendingPlayback = false;
            return;
        }

        LogUtil.log("【CS】【SessionHook】[触发播放] Camera2 播放器装载 ->"
                + " preview: " + previewSurface + " (isValid=" + (previewSurface != null && previewSurface.isValid()) + ")"
                + ", preview1: " + previewSurface1 + " (isValid=" + (previewSurface1 != null && previewSurface1.isValid()) + ")"
                + ", reader: " + readerSurface + " (isValid=" + (readerSurface != null && readerSurface.isValid()) + ")"
                + ", reader1: " + readerSurface1 + " (isValid=" + (readerSurface1 != null && readerSurface1.isValid()) + ")");

        lastInitReader = readerSurface;
        lastInitReader1 = readerSurface1;
        lastInitPreview = previewSurface;
        lastInitPreview1 = previewSurface1;
        pendingPlayback = false;
        playerManager.initCamera2Players(readerSurface, readerSurface1,
                previewSurface, previewSurface1);
        markYuvBridgeSessionReadyIfPossible();
        startContinuousYuvPumper();

        int userRotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        MediaPlayerManager pm = HookMain.playerManager;
        if (pm.c2_renderer != null) pm.c2_renderer.setRotation(userRotation);
        if (pm.c2_renderer_1 != null) pm.c2_renderer_1.setRotation(userRotation);
        LogUtil.log("【CS】【SessionHook】渲染器旋转角度已应用: " + userRotation + "°");
    }

    public static long getSurfaceNativeHandle(Surface surface) {
        if (surface == null) return 0L;
        try {
            java.lang.reflect.Field field = Surface.class.getDeclaredField("mNativeObject");
            field.setAccessible(true);
            return field.getLong(surface);
        } catch (Throwable t) {
            try {
                String str = surface.toString();
                int idx = str.indexOf("mNativeObject=");
                if (idx != -1) {
                    int end = str.indexOf(")", idx);
                    if (end != -1) {
                        return Long.parseLong(str.substring(idx + 14, end).trim());
                    }
                }
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    public void registerImageReaderSurface(Surface surface, int format, int width, int height) {
        if (surface == null) {
            return;
        }
        trackedReaderSurfaces.add(surface);
        surfaceFormatMap.put(surface, format);
        surfaceSizeMap.put(surface, new int[] { width, height });

        long handle = getSurfaceNativeHandle(surface);
        if (handle != 0L) {
            trackedReaderNativeHandles.add(handle);
            surfaceNativeFormatMap.put(handle, format);
            surfaceNativeSizeMap.put(handle, new int[] { width, height });
        }
    }

    public boolean isTrackedReaderSurface(Surface surface) {
        if (surface == null) return false;
        if (trackedReaderSurfaces.contains(surface)) return true;
        long handle = getSurfaceNativeHandle(surface);
        return handle != 0L && trackedReaderNativeHandles.contains(handle);
    }

    public boolean isJpegReaderSurface(Surface surface) {
        if (surface == null) return false;
        Integer format = surfaceFormatMap.get(surface);
        if (format == null) {
            long handle = getSurfaceNativeHandle(surface);
            if (handle != 0L) format = surfaceNativeFormatMap.get(handle);
        }
        return format != null && format == ImageFormat.JPEG;
    }

    public boolean isYuvReaderSurface(Surface surface) {
        if (surface == null) return false;
        Integer format = surfaceFormatMap.get(surface);
        if (format == null) {
            long handle = getSurfaceNativeHandle(surface);
            if (handle != 0L) format = surfaceNativeFormatMap.get(handle);
        }
        return format != null && format == ImageFormat.YUV_420_888;
    }

    public boolean shouldKeepRealReaderSurface(Surface surface) {
        return isJpegReaderSurface(surface);
    }

    public boolean shouldKeepRealReaderSurfaceForPackage(Surface surface, String packageName) {
        if (!isTrackedReaderSurface(surface)) {
            return false;
        }
        return isJpegReaderSurface(surface);
    }

    public boolean shouldKeepRealReaderSurfaceForCurrentPackage(Surface surface) {
        return shouldKeepRealReaderSurfaceForPackage(surface, getCurrentPackageName());
    }

    private boolean shouldKeepYuvReaderSurfaceForPackage(Surface surface, String packageName) {
        if (!shouldUseFakeYuvBridgeForPackage(packageName)) {
            return false;
        }
        return isYuvReaderSurface(surface)
                && !isInternalFakeYuvReaderSurface(surface);
    }

    private boolean isCurrentSessionYuvReaderSurface(Surface surface) {
        if (surface == null) {
            return false;
        }
        return surface.equals(readerSurface) || surface.equals(readerSurface1)
                || sessionKeptYuvSurfaces.contains(surface);
    }

    private boolean shouldDriveYuvBridgeNow() {
        return !isReleasing && yuvBridgeSessionReady;
    }

    public boolean shouldKeepYuvReaderSurfaceForCurrentPackage(Surface surface) {
        boolean keep = shouldKeepYuvReaderSurfaceForPackage(surface, getCurrentPackageName());
        if (surface != null && isYuvReaderSurface(surface)) {
            maybeLogYuvKeepDecision(surface, keep);
        }
        return keep;
    }

    private void maybeLogYuvKeepDecision(Surface surface, boolean keep) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastYuvKeepLogMs < 5000L) {
            return;
        }
        lastYuvKeepLogMs = now;
        LogUtil.log("【CS】YUV keep: " + keep + " surface=" + surface);
    }

    public boolean shouldSkipImageReaderTracking() {
        return internalBridgeCreationDepth.get() > 0;
    }

    public boolean shouldBypassYuvAcquireHook(Surface surface) {
        return fakeYuvAcquireDepth.get() > 0 || isInternalFakeYuvReaderSurface(surface);
    }

    public boolean isInternalFakeYuvReaderSurface(Surface surface) {
        return surface != null && internalFakeYuvReaderSurfaces.contains(surface);
    }

    private void enterInternalBridgeCreation() {
        internalBridgeCreationDepth.set(internalBridgeCreationDepth.get() + 1);
    }

    private void exitInternalBridgeCreation() {
        int depth = internalBridgeCreationDepth.get();
        if (depth <= 1) {
            internalBridgeCreationDepth.remove();
        } else {
            internalBridgeCreationDepth.set(depth - 1);
        }
    }

    private void enterFakeYuvAcquire() {
        fakeYuvAcquireDepth.set(fakeYuvAcquireDepth.get() + 1);
    }

    private void exitFakeYuvAcquire() {
        int depth = fakeYuvAcquireDepth.get();
        if (depth <= 1) {
            fakeYuvAcquireDepth.remove();
        } else {
            fakeYuvAcquireDepth.set(depth - 1);
        }
    }

    public boolean shouldUseReaderPlaybackSurfaceForPackage(String packageName) {
        return !shouldUseFakeYuvBridgeForPackage(packageName);
    }

    private Surface getOutputSurface(Object output) {
        if (output instanceof Surface) {
            return (Surface) output;
        }
        if (output instanceof OutputConfiguration) {
            return ((OutputConfiguration) output).getSurface();
        }
        return null;
    }

    private boolean shouldBypassWhatsAppYuvSession(List<?> outputs, String packageName) {
        // 不再需要整体旁路 session：YUV reader surface 现在直接保留在 session 输出中，
        // preview surface 仍然替换为虚拟 surface。这样 Camera HAL 会为 YUV reader
        // 产出正确格式的帧，同时 preview 仍然显示我们的替换视频。
        return false;
    }

    private void enableSessionBypass(String reason) {
        if (!bypassCurrentSession) {
            LogUtil.log("【CS】启用 YUV 会话旁路: " + reason);
        }
        setBypassCurrentSession(true);
        pendingPlayback = false;
        previewSurface = null;
        previewSurface1 = null;
        readerSurface = null;
        readerSurface1 = null;
        sessionKeptYuvSurfaces.clear();
        playerManager.releaseCamera2Resources();
    }

    private void disableSessionBypass() {
        setBypassCurrentSession(false);
    }

    public void rememberPreviewSurface(Surface surface) {
        if (surface == null) {
            return;
        }
        // Note: Do NOT check isTrackedReaderSurface here — Camera2Handler already
        // filters out YUV/JPEG before calling this. Emma's preview surfaces have
        // null names and may be tracked, but they still need fake frames.
        boolean isNew = false;
        if (previewSurface == null) {
            previewSurface = surface;
            isNew = true;
        } else if (!previewSurface.equals(surface) && previewSurface1 == null) {
            previewSurface1 = surface;
            isNew = true;
        }
        if (isNew || pendingPlayback) {
            LogUtil.log("【CS】addTarget 触发播放 (preview: " + surface + ")");
            startPlayback();
        }
    }

    public void rememberReaderPlaybackSurface(Surface surface) {
        if (!shouldUseReaderPlaybackSurfaceForPackage(getCurrentPackageName())) {
            return;
        }
        if (surface == null || shouldKeepRealReaderSurface(surface)) {
            return;
        }
        // Skip YUV ImageReader surfaces for packages without fake YUV bridge support:
        // GLVideoRenderer outputs RGBA which causes format mismatch crash
        // when CameraX acquires images from a YUV_420_888 ImageReader.
        if (isYuvReaderSurface(surface) && !shouldUseFakeYuvBridgeForPackage(getCurrentPackageName())) {
            LogUtil.log("【CS】跳过 YUV reader surface 作为播放目标 (无 YUV 兼容): " + surface);
            return;
        }
        boolean isNew = false;
        if (readerSurface == null) {
            readerSurface = surface;
            isNew = true;
        } else if (!readerSurface.equals(surface) && readerSurface1 == null) {
            readerSurface1 = surface;
            isNew = true;
        }
        if (isNew || pendingPlayback) {
            LogUtil.log("【CS】addTarget 触发播放 (reader: " + surface + ")");
            startPlayback();
        }
    }

    public void onTargetRemoved(Surface surface) {
        if (surface == null) {
            return;
        }
        pendingJpegSurfaces.remove(surface);
        if (surface.equals(previewSurface)) {
            previewSurface = null;
        }
        if (surface.equals(previewSurface1)) {
            previewSurface1 = null;
        }
        if (surface.equals(readerSurface)) {
            readerSurface = null;
        }
        if (surface.equals(readerSurface1)) {
            readerSurface1 = null;
        }
    }

    public void markPendingJpegCapture(Surface surface) {
        if (shouldKeepRealReaderSurface(surface)) {
            pendingJpegSurfaces.add(surface);
            pendingPhotoSurface = surface;
        }
    }

    // =====================================================================
    // Virtual surface management
    // =====================================================================

    private synchronized void ensureVirtualDrainThread() {
        if (virtualSurfaceDrainThread == null || !virtualSurfaceDrainThread.isAlive()) {
            virtualSurfaceDrainThread = new HandlerThread("CS-VirtualDrain");
            virtualSurfaceDrainThread.start();
            virtualSurfaceDrainHandler = new Handler(virtualSurfaceDrainThread.getLooper());
        }
    }

    private synchronized void releaseVirtualSurfaces() {
        for (Surface vs : virtualSurfaces) {
            if (vs != null) {
                try {
                    vs.release();
                } catch (Exception ignored) {
                }
            }
        }
        virtualSurfaces.clear();
        for (SurfaceTexture vt : virtualTextures) {
            if (vt != null) {
                try {
                    vt.release();
                } catch (Exception ignored) {
                }
            }
        }
        virtualTextures.clear();
        originalToVirtualMap.clear();
        originalNativeToVirtualMap.clear();
    }

    private synchronized Surface createVirtualSurface(int index) {
        if (needRecreate) {
            releaseVirtualSurfaces();
            needRecreate = false;
        }
        ensureVirtualDrainThread();
        while (virtualSurfaces.size() <= index) {
            int texIndex = 15 + virtualSurfaces.size();
            SurfaceTexture vt = new SurfaceTexture(texIndex);
            vt.setDefaultBufferSize(1920, 1080);
            vt.setOnFrameAvailableListener(st -> {
                try {
                    st.updateTexImage();
                } catch (Exception ignored) {
                }
            }, virtualSurfaceDrainHandler);
            Surface vs = new Surface(vt);
            virtualTextures.add(vt);
            virtualSurfaces.add(vs);
            LogUtil.log("【CS】创建虚拟Surface[" + (virtualSurfaces.size() - 1) + "]: " + vs);
        }
        return virtualSurfaces.get(index);
    }

    private Surface createVirtualSurface() {
        return createVirtualSurface(0);
    }

    private List<Surface> rewriteSessionSurfaces(List<?> outputs) {
        return rewriteSessionSurfaces(outputs, getCurrentPackageName());
    }

    private List<Surface> rewriteSessionSurfaces(List<?> outputs, String packageName) {
        LinkedHashSet<Surface> rewritten = new LinkedHashSet<>();
        sessionKeptYuvSurfaces.clear();
        int virtualIndex = 0;
        if (outputs != null) {
            for (Object output : outputs) {
                if (output instanceof Surface) {
                    Surface surface = (Surface) output;
                    boolean isJpeg = isJpegReaderSurface(surface);
                    boolean isYuv = isYuvReaderSurface(surface) || shouldKeepYuvReaderSurfaceForPackage(surface, packageName);
                    boolean isPreview = (virtualIndex == 0 && !isJpeg);

                    if (isPreview) {
                        Surface vSurface = createVirtualSurface(virtualIndex++);
                        originalToVirtualMap.put(surface, vSurface);
                        long handle = getSurfaceNativeHandle(surface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberPreviewSurface(surface);
                        rewritten.add(vSurface);
                        LogUtil.log("【CS】SessionSurfaces 替换主预览 Surface -> virtual[0]: " + surface);
                    } else if (isYuv) {
                        Surface vSurface = createVirtualSurface(virtualIndex++);
                        originalToVirtualMap.put(surface, vSurface);
                        long handle = getSurfaceNativeHandle(surface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberReaderPlaybackSurface(surface);
                        sessionKeptYuvSurfaces.add(surface);
                        rewritten.add(vSurface);
                        LogUtil.log("【CS】SessionSurfaces [方案一替身置换] 替换 YUV Reader Surface -> virtual[" + (virtualIndex - 1) + "]: " + surface);
                    } else {
                        rewritten.add(surface);
                        LogUtil.log("【CS】SessionSurfaces 保留真实辅助/JPEG Surface: " + surface);
                    }
                }
            }
        }
        if (rewritten.isEmpty()) {
            rewritten.add(createVirtualSurface(0));
        }
        return new ArrayList<>(rewritten);
    }

    public boolean isSurfaceTextureSurface(Surface surface) {
        if (surface == null) return false;
        String s = surface.toString();
        return s.contains("SurfaceTexture") || s.contains("SurfaceView") || s.contains("SurfaceHolder");
    }

    private List<OutputConfiguration> rewriteOutputConfigurations(List<?> outputs) {
        return rewriteOutputConfigurations(outputs, getCurrentPackageName());
    }

    private List<OutputConfiguration> rewriteOutputConfigurations(List<?> outputs, String packageName) {
        List<OutputConfiguration> rewritten = new ArrayList<>();
        sessionKeptYuvSurfaces.clear();
        int virtualIndex = 0;
        if (outputs != null) {
            for (Object output : outputs) {
                if (!(output instanceof OutputConfiguration)) {
                    continue;
                }
                OutputConfiguration config = (OutputConfiguration) output;
                Surface originalSurface = config.getSurface();
                boolean isJpeg = isJpegReaderSurface(originalSurface);
                boolean isYuv = isYuvReaderSurface(originalSurface) || shouldKeepYuvReaderSurfaceForPackage(originalSurface, packageName);
                boolean isPreview = (virtualIndex == 0 && !isJpeg);

                if (isPreview) {
                    Surface vSurface = createVirtualSurface(virtualIndex++);
                    if (originalSurface != null) {
                        originalToVirtualMap.put(originalSurface, vSurface);
                        long handle = getSurfaceNativeHandle(originalSurface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberPreviewSurface(originalSurface);
                    }
                    rewritten.add(createOutputConfiguration(config, vSurface, packageName));
                    LogUtil.log("【CS】OutputConfig 替换主预览 Surface -> virtual[0]: " + originalSurface);
                } else if (isYuv) {
                    Surface vSurface = createVirtualSurface(virtualIndex++);
                    if (originalSurface != null) {
                        originalToVirtualMap.put(originalSurface, vSurface);
                        long handle = getSurfaceNativeHandle(originalSurface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberReaderPlaybackSurface(originalSurface);
                        sessionKeptYuvSurfaces.add(originalSurface);
                    }
                    rewritten.add(createOutputConfiguration(config, vSurface, packageName));
                    LogUtil.log("【CS】OutputConfig [方案一替身置换] 替换 YUV Reader Surface -> virtual[" + (virtualIndex - 1) + "]: " + originalSurface);
                } else {
                    rewritten.add(createOutputConfiguration(config, originalSurface, packageName));
                    LogUtil.log("【CS】OutputConfig 保留真实辅助/JPEG Surface: " + originalSurface);
                }
            }
        }
        if (rewritten.isEmpty()) {
            rewritten.add(new OutputConfiguration(createVirtualSurface(0)));
        }
        return rewritten;
    }

    private OutputConfiguration createOutputConfiguration(OutputConfiguration original, Surface surface,
            String packageName) {
        OutputConfiguration rewritten = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                rewritten = new OutputConfiguration(original.getSurfaceGroupId(), surface);
            } catch (Throwable ignored) {
            }
        }
        if (rewritten == null) {
            rewritten = new OutputConfiguration(surface);
        }
        copyOutputConfigurationMetadata(original, rewritten);
        copySharedSurfaces(original, rewritten, surface, packageName);
        return rewritten;
    }

    private void copyOutputConfigurationMetadata(OutputConfiguration original,
            OutputConfiguration rewritten) {
        copyOutputConfigurationProperty(original, rewritten,
                "getPhysicalCameraId", "setPhysicalCameraId");
        copyOutputConfigurationProperty(original, rewritten,
                "getStreamUseCase", "setStreamUseCase");
        copyOutputConfigurationProperty(original, rewritten,
                "getDynamicRangeProfile", "setDynamicRangeProfile");
        copyOutputConfigurationProperty(original, rewritten,
                "getTimestampBase", "setTimestampBase");
        copyOutputConfigurationProperty(original, rewritten,
                "getMirrorMode", "setMirrorMode");
        copyOutputConfigurationProperty(original, rewritten,
                "getReadoutTimestampEnabled", "setReadoutTimestampEnabled");
        copyOutputConfigurationProperty(original, rewritten,
                "getColorSpace", "setColorSpace");
        copyOutputConfigurationSensorPixelModes(original, rewritten);
    }

    private void copyOutputConfigurationProperty(OutputConfiguration original,
            OutputConfiguration rewritten, String getterName, String setterName) {
        try {
            Object value = invokeNoArgMethod(original, getterName);
            if (value != null) {
                invokeCompatibleSingleArgMethod(rewritten, setterName, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private void copyOutputConfigurationSensorPixelModes(OutputConfiguration original,
            OutputConfiguration rewritten) {
        try {
            Object value = invokeNoArgMethod(original, "getSensorPixelModesUsed");
            if (value instanceof int[]) {
                for (int pixelMode : (int[]) value) {
                    invokeCompatibleSingleArgMethod(rewritten, "addSensorPixelModeUsed", pixelMode);
                }
            } else if (value instanceof Iterable) {
                for (Object pixelMode : (Iterable<?>) value) {
                    if (pixelMode != null) {
                        invokeCompatibleSingleArgMethod(rewritten, "addSensorPixelModeUsed", pixelMode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void copySharedSurfaces(OutputConfiguration original, OutputConfiguration rewritten,
            Surface rewrittenSurface, String packageName) {
        try {
            @SuppressWarnings("unchecked")
            List<Surface> originalSurfaces = (List<Surface>) invokeNoArgMethod(original, "getSurfaces");
            if (originalSurfaces == null || originalSurfaces.size() <= 1) {
                return;
            }
            invokeNoArgMethod(rewritten, "enableSurfaceSharing");
            Surface originalPrimarySurface = original.getSurface();
            boolean keptOriginalPrimary = rewrittenSurface != null && rewrittenSurface.equals(originalPrimarySurface);
            for (Surface extraSurface : originalSurfaces) {
                if (extraSurface == null || extraSurface.equals(originalPrimarySurface)) {
                    continue;
                }
                boolean keepExtraSurface = shouldKeepRealReaderSurfaceForPackage(extraSurface, packageName)
                        || shouldKeepYuvReaderSurfaceForPackage(extraSurface, packageName);
                if (!keepExtraSurface && !keptOriginalPrimary) {
                    continue;
                }
                invokeCompatibleSingleArgMethod(rewritten, "addSurface", extraSurface);
                if (shouldKeepYuvReaderSurfaceForPackage(extraSurface, packageName)) {
                    sessionKeptYuvSurfaces.add(extraSurface);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private SessionConfiguration rewriteSessionConfiguration(SessionConfiguration sessionConfiguration) {
        String packageName = getCurrentPackageName();
        List<OutputConfiguration> outputs = rewriteOutputConfigurations(sessionConfiguration.getOutputConfigurations(),
                packageName);
        SessionConfiguration rewritten = new SessionConfiguration(
                sessionConfiguration.getSessionType(),
                outputs,
                sessionConfiguration.getExecutor(),
                sessionConfiguration.getStateCallback());
        try {
            InputConfiguration inputConfiguration = sessionConfiguration.getInputConfiguration();
            if (inputConfiguration != null) {
                rewritten.setInputConfiguration(inputConfiguration);
            }
        } catch (Throwable ignored) {
        }
        try {
            CaptureRequest sessionParameters = sessionConfiguration.getSessionParameters();
            if (sessionParameters != null) {
                rewritten.setSessionParameters(sessionParameters);
            }
        } catch (Throwable ignored) {
        }
        return rewritten;
    }

    // =====================================================================
    // Hook all createCaptureSession variants
    // =====================================================================

    void hookAllCreateSessionVariants(Class<?> deviceClass) {
        if (deviceClass == null) {
            return;
        }
        if (!hookedDeviceClasses.add(deviceClass.getName())) {
            return;
        }

        // 1. createCaptureSession(List, StateCallback, Handler)
        try {
            Method m = resolveMethodOnClass(deviceClass, "createCaptureSession",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args[0] != null) {
                        if (HookGuards.shouldBypass(getCurrentPackageName(), HookGuards.getCurrentVideoFile())) {
                            yuvBridgeSessionReady = false;
                            return chain.proceed(args);
                        }
                        if (shouldBypassWhatsAppYuvSession((List<?>) args[0], getCurrentPackageName())) {
                            enableSessionBypass("createCaptureSession(List)");
                        } else {
                            disableSessionBypass();
                        }
                        LogUtil.log("【CS】createCaptureSession(List) outputs=" + ((List<?>) args[0]).size());
                        if (!isCurrentSessionBypassed()) {
                            args[0] = rewriteSessionSurfaces((List<?>) args[0]);
                        }
                        if (args[1] != null)
                            hookSessionCallback((CameraCaptureSession.StateCallback) args[1]);
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】createCaptureSession(List) before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook createCaptureSession(List) 失败: " + t);
        }

        // 2. createCaptureSessionByOutputConfigurations (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Method m = resolveMethodOnClass(deviceClass,
                        "createCaptureSessionByOutputConfigurations",
                        List.class, CameraCaptureSession.StateCallback.class, Handler.class);
                Api101Runtime.requireModule().hook(m).intercept(chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    try {
                        if (args[0] != null) {
                            if (HookGuards.shouldBypass(getCurrentPackageName(), HookGuards.getCurrentVideoFile())) {
                                yuvBridgeSessionReady = false;
                                return chain.proceed(args);
                            }
                            if (shouldBypassWhatsAppYuvSession((List<?>) args[0], getCurrentPackageName())) {
                                enableSessionBypass("createCaptureSessionByOutputConfigurations");
                            } else {
                                disableSessionBypass();
                            }
                            if (!isCurrentSessionBypassed()) {
                                args[0] = rewriteOutputConfigurations((List<?>) args[0]);
                            }
                            LogUtil.log("【CS】createCaptureSession(OutputConfigurations)");
                            if (args[1] != null)
                                hookSessionCallback((CameraCaptureSession.StateCallback) args[1]);
                        }
                    } catch (Throwable t) {
                        LogUtil.log("【CS】createCaptureSessionByOutputConfigurations before 异常: " + t);
                    }
                    return chain.proceed(args);
                });
            } catch (Throwable t) {
                LogUtil.log("【CS】Hook createCaptureSessionByOutputConfigurations 失败: " + t);
            }
        }

        // 3. createConstrainedHighSpeedCaptureSession
        try {
            Method m = resolveMethodOnClass(deviceClass, "createConstrainedHighSpeedCaptureSession",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args[0] != null) {
                        if (HookGuards.shouldBypass(getCurrentPackageName(), HookGuards.getCurrentVideoFile())) {
                            yuvBridgeSessionReady = false;
                            return chain.proceed(args);
                        }
                        if (shouldBypassWhatsAppYuvSession((List<?>) args[0], getCurrentPackageName())) {
                            enableSessionBypass("createConstrainedHighSpeedCaptureSession");
                        } else {
                            disableSessionBypass();
                        }
                        if (!isCurrentSessionBypassed()) {
                            args[0] = rewriteSessionSurfaces((List<?>) args[0]);
                        }
                        LogUtil.log("【CS】createCaptureSession(HighSpeed)");
                        if (args[1] != null)
                            hookSessionCallback((CameraCaptureSession.StateCallback) args[1]);
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】createConstrainedHighSpeedCaptureSession before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook createConstrainedHighSpeedCaptureSession 失败: " + t);
        }

        // 4. createReprocessableCaptureSession (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Method m = resolveMethodOnClass(deviceClass, "createReprocessableCaptureSession",
                        InputConfiguration.class, List.class,
                        CameraCaptureSession.StateCallback.class, Handler.class);
                Api101Runtime.requireModule().hook(m).intercept(chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    try {
                        if (args[1] != null) {
                            if (HookGuards.shouldBypass(getCurrentPackageName(), HookGuards.getCurrentVideoFile())) {
                                yuvBridgeSessionReady = false;
                                return chain.proceed(args);
                            }
                            if (shouldBypassWhatsAppYuvSession((List<?>) args[1], getCurrentPackageName())) {
                                enableSessionBypass("createReprocessableCaptureSession");
                            } else {
                                disableSessionBypass();
                            }
                            if (!isCurrentSessionBypassed()) {
                                args[1] = rewriteSessionSurfaces((List<?>) args[1]);
                            }
                            LogUtil.log("【CS】createCaptureSession(Reprocessable)");
                            if (args[2] != null)
                                hookSessionCallback((CameraCaptureSession.StateCallback) args[2]);
                        }
                    } catch (Throwable t) {
                        LogUtil.log("【CS】createReprocessableCaptureSession before 异常: " + t);
                    }
                    return chain.proceed(args);
                });
            } catch (Throwable t) {
                LogUtil.log("【CS】Hook createReprocessableCaptureSession 失败: " + t);
            }
        }

        // 5. createReprocessableCaptureSessionByConfigurations (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Method m = resolveMethodOnClass(deviceClass,
                        "createReprocessableCaptureSessionByConfigurations",
                        InputConfiguration.class, List.class,
                        CameraCaptureSession.StateCallback.class, Handler.class);
                Api101Runtime.requireModule().hook(m).intercept(chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    try {
                        if (args[1] != null) {
                            if (HookGuards.shouldBypass(getCurrentPackageName(), HookGuards.getCurrentVideoFile())) {
                                yuvBridgeSessionReady = false;
                                return chain.proceed(args);
                            }
                            if (shouldBypassWhatsAppYuvSession((List<?>) args[1], getCurrentPackageName())) {
                                enableSessionBypass("createReprocessableCaptureSessionByConfigurations");
                            } else {
                                disableSessionBypass();
                            }
                            if (!isCurrentSessionBypassed()) {
                                args[1] = rewriteOutputConfigurations((List<?>) args[1]);
                            }
                            LogUtil.log("【CS】createCaptureSession(ReprocessableByConfigs)");
                            if (args[2] != null)
                                hookSessionCallback((CameraCaptureSession.StateCallback) args[2]);
                        }
                    } catch (Throwable t) {
                        LogUtil.log("【CS】createReprocessableCaptureSessionByConfigurations before 异常: " + t);
                    }
                    return chain.proceed(args);
                });
            } catch (Throwable t) {
                LogUtil.log("【CS】Hook createReprocessableCaptureSessionByConfigurations 失败: " + t);
            }
        }

        // 6. createCaptureSession(SessionConfiguration) (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Method m = resolveMethodOnClass(deviceClass, "createCaptureSession",
                        SessionConfiguration.class);
                Api101Runtime.requireModule().hook(m).intercept(chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    try {
                        if (args[0] != null) {
                            if (HookGuards.shouldBypass(getCurrentPackageName(), HookGuards.getCurrentVideoFile())) {
                                yuvBridgeSessionReady = false;
                                return chain.proceed(args);
                            }
                            LogUtil.log("【CS】createCaptureSession(SessionConfig)");
                            realSessionConfig = (SessionConfiguration) args[0];
                            if (shouldBypassWhatsAppYuvSession(realSessionConfig.getOutputConfigurations(),
                                    getCurrentPackageName())) {
                                enableSessionBypass("createCaptureSession(SessionConfiguration)");
                            } else {
                                disableSessionBypass();
                            }
                            if (!isCurrentSessionBypassed()) {
                                fakeSessionConfig = rewriteSessionConfiguration(realSessionConfig);
                                args[0] = fakeSessionConfig;
                            }
                            hookSessionCallback(realSessionConfig.getStateCallback());
                        }
                    } catch (Throwable t) {
                        LogUtil.log("【CS】createCaptureSession(SessionConfiguration) before 异常: " + t);
                    }
                    return chain.proceed(args);
                });
            } catch (Throwable t) {
                LogUtil.log("【CS】Hook createCaptureSession(SessionConfiguration) 失败: " + t);
            }
        }
    }

    // =====================================================================
    // Session callback logging hooks
    // =====================================================================

    private void hookSessionCallback(CameraCaptureSession.StateCallback cb) {
        if (cb == null)
            return;
        if (!hookedSessionCallbackClasses.add(cb.getClass().getName())) {
            return;
        }
        Class<?> cbClass = cb.getClass();

        try {
            Method m = resolveMethodOnClass(cbClass, "onConfigureFailed", CameraCaptureSession.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】onConfigureFailed ：" + args[0]);
                } catch (Throwable t) {
                    LogUtil.log("【CS】onConfigureFailed before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onConfigureFailed 失败: " + t);
        }

        try {
            Method m = resolveMethodOnClass(cbClass, "onConfigured", CameraCaptureSession.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】onConfigured ：" + args[0]);
                    markYuvBridgeSessionReadyIfPossible();
                } catch (Throwable t) {
                    LogUtil.log("【CS】onConfigured before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onConfigured 失败: " + t);
        }

        try {
            Method m = resolveMethodOnClass(cbClass, "onClosed", CameraCaptureSession.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】onClosed ：" + args[0]);
                } catch (Throwable t) {
                    LogUtil.log("【CS】onClosed before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook session onClosed 失败: " + t);
        }
    }

    public void releaseImageWriters() {
        releaseImageWriters(true);
    }

    private void releaseImageWriters(boolean clearTrackedReaders) {
        if (clearTrackedReaders) {
            isReleasing = true;
            stopAllWhatsAppYuvPumps();
            stopContinuousYuvPumper();
        } else {
            stopAllWhatsAppYuvPumps();
            stopContinuousYuvPumper();
        }
        for (ImageWriter writer : imageWriterMap.values()) {
            try {
                writer.close();
            } catch (Exception e) {
                LogUtil.log("【CS】关闭 ImageWriter 失败: " + e);
            }
        }
        imageWriterMap.clear();
        pendingJpegSurfaces.clear();
        pendingPhotoSurface = null;
        bypassCurrentSession = false;
        closeFakeYuvBridges();
        internalFakeYuvReaderSurfaces.clear();
        sessionKeptYuvSurfaces.clear();
        cachedYuvFrameMap.clear();
        releaseCachedRetriever();
        lastYuvFrameWasFallback = true;
        lastYuvFrameWasCodec = false;
        if (clearTrackedReaders) {
            trackedReaderSurfaces.clear();
            surfaceFormatMap.clear();
            surfaceSizeMap.clear();
        }
        isReleasing = false;
    }

    public void updateImageReaderListener(Object imageReaderObj, Object listenerObj, Handler handler) {
        if (!(imageReaderObj instanceof ImageReader)) {
            return;
        }
        ImageReader imageReader = (ImageReader) imageReaderObj;
        Surface surface = imageReader.getSurface();
        boolean isYuv = false;
        try {
            isYuv = imageReader.getImageFormat() == ImageFormat.YUV_420_888;
        } catch (Throwable ignored) {
        }
        String pkg = getCurrentPackageName();
        boolean isTargetPkg = isWhatsAppPackage(pkg) || isLinePackage(pkg);
        if (!isYuv || !isTargetPkg) {
            stopWhatsAppYuvPump(imageReader);
            return;
        }
        if (listenerObj == null) {
            stopWhatsAppYuvPump(imageReader);
            return;
        }
        ensureWhatsAppYuvPumpHandler();
        YuvCallbackPump pump = whatsappYuvPumpMap.get(imageReader);
        if (pump == null) {
            pump = new YuvCallbackPump(imageReader);
            whatsappYuvPumpMap.put(imageReader, pump);
        }
        pump.listener = listenerObj;
        pump.onImageAvailableMethod = null;
        pump.targetHandler = handler;
        LogUtil.log("【CS】YUV 回调已登记: " + surface + " (ready=" + shouldDriveYuvBridgeNow() + ")");
        if (pump.running || !shouldDriveYuvBridgeNow()) {
            return;
        }
        pump.running = true;
        whatsappYuvPumpHandler.post(pump.notifyRunnable);
        LogUtil.log("【CS】YUV 回调泵已启动: " + surface);
    }

    private void startDeferredYuvPumpsIfReady() {
        if (!shouldDriveYuvBridgeNow()) {
            return;
        }
        ensureWhatsAppYuvPumpHandler();
        for (YuvCallbackPump pump : whatsappYuvPumpMap.values()) {
            if (pump == null || pump.listener == null || pump.running
                    || !shouldUseYuvPumpForSurface(pump.imageReader.getSurface())) {
                continue;
            }
            pump.running = true;
            whatsappYuvPumpHandler.post(pump.notifyRunnable);
            try {
                LogUtil.log("【CS】YUV 回调泵已启动: " + pump.imageReader.getSurface());
            } catch (Throwable ignored) {
            }
        }
    }

    private void ensureWhatsAppYuvPumpHandler() {
        if (whatsappYuvPumpThread != null && whatsappYuvPumpHandler != null) {
            return;
        }
        whatsappYuvPumpThread = new HandlerThread("CS-WhatsAppYuvPump");
        whatsappYuvPumpThread.start();
        whatsappYuvPumpHandler = new Handler(whatsappYuvPumpThread.getLooper());
    }

    private void stopWhatsAppYuvPump(ImageReader imageReader) {
        if (imageReader == null) {
            return;
        }
        YuvCallbackPump pump = whatsappYuvPumpMap.remove(imageReader);
        if (pump == null) {
            return;
        }
        pump.running = false;
        Handler handler = whatsappYuvPumpHandler;
        if (handler != null) {
            handler.removeCallbacks(pump.notifyRunnable);
        }
    }

    private void stopAllWhatsAppYuvPumps() {
        // 停止 MediaCodec YUV 解码器
        MediaCodecYuvDecoder dec = yuvDecoder;
        if (dec != null) {
            dec.stop();
            yuvDecoder = null;
        }
        Handler handler = whatsappYuvPumpHandler;
        if (handler != null) {
            for (YuvCallbackPump pump : whatsappYuvPumpMap.values()) {
                pump.running = false;
                handler.removeCallbacks(pump.notifyRunnable);
            }
        }
        whatsappYuvPumpMap.clear();
        if (whatsappYuvPumpThread != null) {
            try {
                whatsappYuvPumpThread.quitSafely();
            } catch (Throwable ignored) {
            }
            whatsappYuvPumpThread = null;
            whatsappYuvPumpHandler = null;
        }
    }

    /**
     * 在泵的后台线程上预刷新 YUV 缓存。
     * 将重计算（GL 截帧 + RGB→YUV 转换）从 WhatsApp 的 handler 线程移到泵线程，
     * 避免阻塞 WhatsApp UI 处理和挂断信号。
     */
    private void preRefreshYuvCache(YuvCallbackPump pump) {
        if (isReleasing || pump == null || !pump.running) {
            return;
        }
        if (!shouldDriveYuvBridgeNow()) {
            return;
        }
        try {
            Surface targetSurface = pump.imageReader.getSurface();
            if (targetSurface == null || !isCurrentSessionYuvReaderSurface(targetSurface)
                    || !shouldKeepYuvReaderSurfaceForCurrentPackage(targetSurface)
                    || !shouldUseYuvPumpForSurface(targetSurface)) {
                return;
            }
            int width = pump.imageReader.getWidth();
            int height = pump.imageReader.getHeight();
            if (width <= 0 || height <= 0) {
                int[] size = surfaceSizeMap.get(targetSurface);
                if (size != null && size.length >= 2) {
                    width = size[0];
                    height = size[1];
                }
            }
            if (width <= 0 || height <= 0) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            CachedYuvFrame cached = cachedYuvFrameMap.get(targetSurface);
            boolean needRefresh = false;
            if (cached == null || cached.width != width || cached.height != height) {
                cached = buildPlaceholderYuvFrame(width, height, now);
                cachedYuvFrameMap.put(targetSurface, cached);
                needRefresh = true;
            }
            long refreshInterval = computeYuvRefreshInterval(width, height);
            if (cached.isPlaceholder || now - cached.generatedAtMs >= refreshInterval || needRefresh) {
                CachedYuvFrame refreshed = buildCachedYuvFrame(targetSurface, width, height, now);
                if (refreshed != null) {
                    cachedYuvFrameMap.put(targetSurface, refreshed);
                }
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】预刷新 YUV 缓存异常: " + t);
        }
    }

    private void dispatchWhatsAppYuvCallback(YuvCallbackPump pump) {
        if (pump == null || !pump.running || pump.listener == null || isReleasing) {
            return;
        }
        if (!shouldDriveYuvBridgeNow()) {
            return;
        }
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                try {
                    if (pump.listener instanceof OnImageAvailableListener) {
                        ((OnImageAvailableListener) pump.listener).onImageAvailable(pump.imageReader);
                    } else {
                        pump.resolveOnImageAvailableMethod().invoke(pump.listener, pump.imageReader);
                    }
                } catch (Throwable t) {
                    pump.running = false;
                    LogUtil.log("【CS】YUV 回调分发失败: " + t);
                }
            }
        };
        if (pump.targetHandler != null) {
            pump.targetHandler.post(callback);
        } else {
            callback.run();
        }
    }

    public Image acquireFakeWhatsAppYuvImage(Object imageReader, Surface targetSurface) {
        if (isReleasing || targetSurface == null) {
            return null;
        }
        int width = 0;
        int height = 0;
        if (imageReader != null) {
            try {
                ImageReader typedReader = (ImageReader) imageReader;
                width = typedReader.getWidth();
                height = typedReader.getHeight();
            } catch (Throwable ignored) {
            }
        }
        if (width <= 0 || height <= 0) {
            int[] size = surfaceSizeMap.get(targetSurface);
            if (size != null && size.length >= 2) {
                width = size[0];
                height = size[1];
            }
        }
        if (width <= 0 || height <= 0) {
            width = 1920;
            height = 1080;
        }

        // 缓存由泵线程 preRefreshYuvCache 预先刷新，这里只读取
        CachedYuvFrame cached = cachedYuvFrameMap.get(targetSurface);
        long now = SystemClock.elapsedRealtime();
        long refreshInterval = computeYuvRefreshInterval(width, height);
        boolean needRefresh = cached == null
                || cached.width != width
                || cached.height != height
                || cached.isPlaceholder
                || now - cached.generatedAtMs >= refreshInterval;
        if (needRefresh) {
            CachedYuvFrame refreshed = buildCachedYuvFrame(targetSurface, width, height, now);
            if (refreshed != null) {
                cached = refreshed;
                cachedYuvFrameMap.put(targetSurface, refreshed);
            } else if (cached == null || cached.width != width || cached.height != height) {
                cached = buildPlaceholderYuvFrame(width, height, now);
                cachedYuvFrameMap.put(targetSurface, cached);
            }
        }
        if (cached == null) {
            return null;
        }
        try {
            FakeYuvBridge bridge = getOrCreateFakeYuvBridge(targetSurface, width, height);
            if (bridge == null) {
                return null;
            }
            enterFakeYuvAcquire();
            try {
                clearPendingFakeYuvBridgeImages(bridge);
                Image inputImage = bridge.writer.dequeueInputImage();
                if (inputImage == null) {
                    return null;
                }
                boolean queued = false;
                try {
                    copyCachedYuvFrameToImage(cached, inputImage);
                    inputImage.setCropRect(new Rect(0, 0, width, height));
                    inputImage.setTimestamp(SystemClock.elapsedRealtimeNanos());
                    bridge.writer.queueInputImage(inputImage);
                    queued = true;
                } finally {
                    if (!queued) {
                        inputImage.close();
                    }
                }
                // 使用 acquireNextImage 而非 acquireLatestImage：
                // acquireLatestImage 会自动 close 所有之前已 acquire 但未被消费方 close 的 Image，
                // WhatsApp VoipCameraManager.getLastCachedFrame() 会缓存旧 Image 引用，
                // 如果我们通过 acquireLatestImage 隐式关闭了它，WhatsApp 在后续调用
                // image.getPlanes() 时就会抛出 IllegalStateException: Image is already closed
                Image image = bridge.reader.acquireNextImage();
                if (image != null) {
                    if (cached.isPlaceholder) {
                        maybeLogWhatsAppYuvPlaceholder(width, height);
                    }
                    maybeLogWhatsAppYuvSuccess(width, height, image.getTimestamp());
                }
                return image;
            } finally {
                exitFakeYuvAcquire();
            }
        } catch (Exception e) {
            LogUtil.log("【CS】YUV 伪帧获取失败: " + e);
            return null;
        }
    }

    private void clearPendingFakeYuvBridgeImages(FakeYuvBridge bridge) {
        if (bridge == null) {
            return;
        }
        while (true) {
            Image pendingImage = null;
            try {
                pendingImage = bridge.reader.acquireNextImage();
            } catch (IllegalStateException e) {
                break;
            } catch (Exception e) {
                LogUtil.log("【CS】清理 YUV 桥旧帧失败: " + e);
                break;
            }
            if (pendingImage == null) {
                break;
            }
            try {
                pendingImage.close();
            } catch (Exception ignored) {
            }
        }
    }

    // Reusable buffers for YUV frame building — avoid per-frame allocation
    private int[] reusablePixelBuf;
    private int reusablePixelBufSize;
    private byte[] reusableYPlane;
    private byte[] reusableUPlane;
    private byte[] reusableVPlane;
    private int reusablePlaneW;
    private int reusablePlaneH;

    /** 根据当前解码路径计算 YUV 缓存刷新间隔 */
    private long computeYuvRefreshInterval(int width, int height) {
        if (lastYuvFrameWasCodec) {
            return YUV_CACHE_REFRESH_CODEC_MS;
        }
        if (lastYuvFrameWasFallback) {
            return YUV_CACHE_REFRESH_FALLBACK_MS;
        }
        if (width * height > YUV_HIRES_PIXEL_THRESHOLD) {
            return YUV_CACHE_REFRESH_GL_HIRES_MS;
        }
        return YUV_CACHE_REFRESH_GL_MS;
    }

    private void ensureReusableBuffers(int width, int height) {
        int pixelCount = width * height;
        if (reusablePixelBuf == null || reusablePixelBufSize < pixelCount) {
            reusablePixelBuf = new int[pixelCount];
            reusablePixelBufSize = pixelCount;
        }
        if (reusableYPlane == null || reusablePlaneW != width || reusablePlaneH != height) {
            reusableYPlane = new byte[pixelCount];
            reusableUPlane = new byte[(width / 2) * (height / 2)];
            reusableVPlane = new byte[(width / 2) * (height / 2)];
            reusablePlaneW = width;
            reusablePlaneH = height;
        }
    }

    private CachedYuvFrame buildCachedYuvFrame(Surface targetSurface, int width, int height, long nowMs) {
        // 优先使用 MediaCodec 直出 YUV，绕过 GL→Bitmap→RGB→YUV
        CachedYuvFrame codecFrame = tryBuildFromCodecDecoder(width, height, nowMs);
        if (codecFrame != null) {
            lastYuvFrameWasFallback = false;
            lastYuvFrameWasCodec = true;
            return codecFrame;
        }
        lastYuvFrameWasCodec = false;

        // 回退到 GL 截帧 + RGB→YUV 转换
        Bitmap frame = captureFrameForYuv(width, height);
        if (frame == null) {
            return null;
        }
        try {
            if (frame.getWidth() != width || frame.getHeight() != height) {
                Bitmap scaled = fitBitmapToTargetAspect(frame, width, height);
                if (scaled != frame) {
                    frame.recycle();
                    frame = scaled;
                }
            }
            ensureReusableBuffers(width, height);
            frame.getPixels(reusablePixelBuf, 0, width, 0, 0, width, height);

            int chromaWidth = width / 2;
            int chromaHeight = height / 2;

            for (int row = 0; row < height; row++) {
                int rowOffset = row * width;
                boolean isEvenRow = (row & 1) == 0;
                int chromaRowBase = isEvenRow ? (row >> 1) * chromaWidth : -1;

                for (int col = 0; col < width; col++) {
                    int pixel = reusablePixelBuf[rowOffset + col];
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;

                    int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    reusableYPlane[rowOffset + col] = (byte) (yVal < 0 ? 0 : (yVal > 255 ? 255 : yVal));

                    if (isEvenRow && (col & 1) == 0) {
                        int uVal = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        int vVal = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                        int chromaIdx = chromaRowBase + (col >> 1);
                        reusableUPlane[chromaIdx] = (byte) (uVal < 0 ? 0 : (uVal > 255 ? 255 : uVal));
                        reusableVPlane[chromaIdx] = (byte) (vVal < 0 ? 0 : (vVal > 255 ? 255 : vVal));
                    }
                }
            }

            // Copy to new arrays for the cached frame (buffer reuse across builds, not across cache reads)
            int yLen = width * height;
            int cLen = chromaWidth * chromaHeight;
            byte[] yOut = new byte[yLen];
            byte[] uOut = new byte[cLen];
            byte[] vOut = new byte[cLen];
            System.arraycopy(reusableYPlane, 0, yOut, 0, yLen);
            System.arraycopy(reusableUPlane, 0, uOut, 0, cLen);
            System.arraycopy(reusableVPlane, 0, vOut, 0, cLen);

            return new CachedYuvFrame(width, height, yOut, uOut, vOut, nowMs, System.nanoTime(), false);
        } finally {
            frame.recycle();
        }
    }

    /**
     * 从 MediaCodec YUV 解码器获取帧并缩放到目标尺寸。
     * 如果解码器未运行、尚无帧可用、或有旋转偏移，返回 null（回退到 GL 路径）。
     */
    private CachedYuvFrame tryBuildFromCodecDecoder(int width, int height, long nowMs) {
        // 有用户旋转偏移时回退到 GL 路径（GL 渲染器原生支持旋转）
        int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        if (rotation != 0) {
            return null;
        }
        MediaCodecYuvDecoder dec = yuvDecoder;
        if (dec == null || !dec.isRunning()) {
            return null;
        }
        MediaCodecYuvDecoder.YuvFrame decoded = dec.acquireLatestFrame();
        if (decoded == null) {
            return null;
        }

        // 尺寸匹配：直接使用
        if (decoded.width == width && decoded.height == height) {
            return new CachedYuvFrame(width, height,
                    decoded.yPlane, decoded.uPlane, decoded.vPlane,
                    nowMs, decoded.timestampNs, false);
        }

        // 尺寸不匹配：缩放 YUV 平面
        int srcW = decoded.width;
        int srcH = decoded.height;
        int dstW = width;
        int dstH = height;

        byte[] yOut = new byte[dstW * dstH];
        int cDstW = dstW / 2;
        int cDstH = dstH / 2;
        byte[] uOut = new byte[cDstW * cDstH];
        byte[] vOut = new byte[cDstW * cDstH];

        // 最近邻缩放 Y 平面
        for (int y = 0; y < dstH; y++) {
            int srcY = y * srcH / dstH;
            int srcRowOff = srcY * srcW;
            int dstRowOff = y * dstW;
            for (int x = 0; x < dstW; x++) {
                yOut[dstRowOff + x] = decoded.yPlane[srcRowOff + x * srcW / dstW];
            }
        }

        // 最近邻缩放 U/V 平面
        int cSrcW = srcW / 2;
        int cSrcH = srcH / 2;
        for (int y = 0; y < cDstH; y++) {
            int srcY = y * cSrcH / cDstH;
            int srcRowOff = srcY * cSrcW;
            int dstRowOff = y * cDstW;
            for (int x = 0; x < cDstW; x++) {
                int srcX = x * cSrcW / cDstW;
                uOut[dstRowOff + x] = decoded.uPlane[srcRowOff + srcX];
                vOut[dstRowOff + x] = decoded.vPlane[srcRowOff + srcX];
            }
        }

        return new CachedYuvFrame(dstW, dstH, yOut, uOut, vOut, nowMs, decoded.timestampNs, false);
    }

    private CachedYuvFrame buildPlaceholderYuvFrame(int width, int height, long nowMs) {
        byte[] yPlane = new byte[width * height];
        byte[] uPlane = new byte[(width / 2) * (height / 2)];
        byte[] vPlane = new byte[(width / 2) * (height / 2)];
        // 使用自然明亮白光/肤色灰度 (Y=180, U=115, V=140)，避免暗光测光算法将画面拉黑
        Arrays.fill(yPlane, (byte) 180);
        Arrays.fill(uPlane, (byte) 115);
        Arrays.fill(vPlane, (byte) 140);
        return new CachedYuvFrame(width, height, yPlane, uPlane, vPlane, nowMs, SystemClock.elapsedRealtimeNanos(), true);
    }

    private void copyCachedYuvFrameToImage(CachedYuvFrame cached, Image image) {
        if (image == null || image.getPlanes() == null || image.getPlanes().length < 3) {
            throw new IllegalStateException("YUV Image plane 不可用");
        }
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        if (yBuffer == null || uBuffer == null || vBuffer == null
                || yBuffer.isReadOnly() || uBuffer.isReadOnly() || vBuffer.isReadOnly()) {
            throw new IllegalStateException("YUV Image plane Buffer 不可写");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || width != cached.width || height != cached.height) {
            throw new IllegalStateException("YUV Image 尺寸不匹配");
        }

        yBuffer.clear();
        uBuffer.clear();
        vBuffer.clear();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        // Fast path: bulk copy when row stride matches width and pixel stride is 1
        // (common case for our internally-created ImageReader bridge)
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.put(cached.yPlane, 0, width * height);
        } else {
            for (int y = 0; y < height; y++) {
                int yRowOffset = y * yRowStride;
                int sourceRowOffset = y * width;
                if (yPixelStride == 1) {
                    yBuffer.position(yRowOffset);
                    yBuffer.put(cached.yPlane, sourceRowOffset, width);
                } else {
                    for (int x = 0; x < width; x++) {
                        yBuffer.put(yRowOffset + x * yPixelStride, cached.yPlane[sourceRowOffset + x]);
                    }
                }
            }
        }

        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        if (uPixelStride == 1 && uRowStride == chromaWidth
                && vPixelStride == 1 && vRowStride == chromaWidth) {
            uBuffer.put(cached.uPlane, 0, chromaWidth * chromaHeight);
            vBuffer.put(cached.vPlane, 0, chromaWidth * chromaHeight);
        } else {
            for (int y = 0; y < chromaHeight; y++) {
                int uRowOffset = y * uRowStride;
                int vRowOffset = y * vRowStride;
                int sourceRowOffset = y * chromaWidth;
                if (uPixelStride == 1 && vPixelStride == 1) {
                    uBuffer.position(uRowOffset);
                    uBuffer.put(cached.uPlane, sourceRowOffset, chromaWidth);
                    vBuffer.position(vRowOffset);
                    vBuffer.put(cached.vPlane, sourceRowOffset, chromaWidth);
                } else {
                    for (int x = 0; x < chromaWidth; x++) {
                        uBuffer.put(uRowOffset + x * uPixelStride, cached.uPlane[sourceRowOffset + x]);
                        vBuffer.put(vRowOffset + x * vPixelStride, cached.vPlane[sourceRowOffset + x]);
                    }
                }
            }
        }
    }

    private FakeYuvBridge getOrCreateFakeYuvBridge(Surface targetSurface, int width, int height) {
        FakeYuvBridge bridge = fakeYuvBridgeMap.get(targetSurface);
        if (bridge != null && bridge.width == width && bridge.height == height) {
            return bridge;
        }
        if (bridge != null) {
            closeFakeYuvBridge(bridge);
        }
        try {
            enterInternalBridgeCreation();
            ImageReader reader = null;
            try {
                // maxImages 设为 8：需要足够大以容纳 WhatsApp 缓存的旧帧 + 当前帧 + writer 队列
                // 如果太小，WhatsApp 持有旧 Image 引用时新帧就无法被 acquire
                reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888,
                        WHATSAPP_YUV_BRIDGE_MAX_IMAGES);
            } finally {
                exitInternalBridgeCreation();
            }
            ImageWriter writer = ImageWriter.newInstance(reader.getSurface(), WHATSAPP_YUV_BRIDGE_MAX_IMAGES);
            internalFakeYuvReaderSurfaces.add(reader.getSurface());
            bridge = new FakeYuvBridge(reader, writer, width, height);
            fakeYuvBridgeMap.put(targetSurface, bridge);
            return bridge;
        } catch (Exception e) {
            LogUtil.log("【CS】创建 YUV 桥失败: " + e);
            return null;
        }
    }

    private void closeFakeYuvBridges() {
        for (FakeYuvBridge bridge : fakeYuvBridgeMap.values()) {
            closeFakeYuvBridge(bridge);
        }
        fakeYuvBridgeMap.clear();
    }

    private void closeFakeYuvBridge(FakeYuvBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            internalFakeYuvReaderSurfaces.remove(bridge.reader.getSurface());
        } catch (Exception ignored) {
        }
        // 延迟关闭 bridge 的 reader/writer：
        // WhatsApp 可能仍持有之前通过 acquireNextImage() 获取的 Image 引用，
        // 如果立刻 close reader，这些 Image 会变为 invalid，WhatsApp 调用
        // getPlanes() 时就会抛出 IllegalStateException: Image is already closed。
        // 延迟 500ms 让 WhatsApp 有时间消费并 close 旧 Image。
        final ImageWriter w = bridge.writer;
        final ImageReader r = bridge.reader;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                }
                try {
                    w.close();
                } catch (Exception ignored) {
                }
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }, "CS-BridgeClose").start();
    }

    private void maybeLogWhatsAppYuvSuccess(int width, int height, long timestampNs) {
        long now = SystemClock.elapsedRealtime();
        yuvFrameCount++;
        if (yuvFpsWindowStartMs == 0L) {
            yuvFpsWindowStartMs = now;
            yuvFrameCount = 1;
        } else if (now - yuvFpsWindowStartMs >= 5000L) {
            float fps = yuvFrameCount * 1000f / (now - yuvFpsWindowStartMs);
            LogUtil.log("【CS】YUV " + width + "x" + height
                    + " " + String.format(Locale.US, "%.1f", fps) + "fps"
                    + (lastYuvFrameWasCodec ? " [Codec]" : lastYuvFrameWasFallback ? " [fallback]" : " [GL]"));
            yuvFrameCount = 0;
            yuvFpsWindowStartMs = now;
        }
    }

    private void maybeLogWhatsAppYuvPlaceholder(int width, int height) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastWhatsAppYuvPlaceholderLogMs < 5000L) {
            return;
        }
        lastWhatsAppYuvPlaceholderLogMs = now;
        LogUtil.log("【CS】YUV 占位帧: " + width + "x" + height);
    }

    private void maybeLogNoGlRendererFallback() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNoGlRendererLogMs < 5000L) {
            return;
        }
        lastNoGlRendererLogMs = now;
        LogUtil.log("【CS】YUV fallback: 无 GL 渲染器");
    }

    private void maybeLogGlNullFallback() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGlNullFallbackLogMs < 5000L) {
            return;
        }
        lastGlNullFallbackLogMs = now;
        LogUtil.log("【CS】YUV fallback: GL 截帧返回 null");
    }

    private void maybeLogGlBlackFallback() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGlBlackFallbackLogMs < 5000L) {
            return;
        }
        lastGlBlackFallbackLogMs = now;
        LogUtil.log("【CS】YUV fallback: GL 截帧过黑");
    }

    private static final class CachedYuvFrame {
        final int width;
        final int height;
        final byte[] yPlane;
        final byte[] uPlane;
        final byte[] vPlane;
        final long generatedAtMs;
        final long timestampNs;
        final boolean isPlaceholder;

        CachedYuvFrame(int width, int height, byte[] yPlane, byte[] uPlane, byte[] vPlane,
                long generatedAtMs, long timestampNs, boolean isPlaceholder) {
            this.width = width;
            this.height = height;
            this.yPlane = yPlane;
            this.uPlane = uPlane;
            this.vPlane = vPlane;
            this.generatedAtMs = generatedAtMs;
            this.timestampNs = timestampNs;
            this.isPlaceholder = isPlaceholder;
        }
    }

    private static final class FakeYuvBridge {
        final ImageReader reader;
        final ImageWriter writer;
        final int width;
        final int height;

        FakeYuvBridge(ImageReader reader, ImageWriter writer, int width, int height) {
            this.reader = reader;
            this.writer = writer;
            this.width = width;
            this.height = height;
        }
    }

    private final class YuvCallbackPump {
        final ImageReader imageReader;
        volatile Object listener;
        volatile Handler targetHandler;
        volatile boolean running;
        volatile Method onImageAvailableMethod;
        final Runnable notifyRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running || whatsappYuvPumpHandler == null) {
                    return;
                }
                long startMs = SystemClock.elapsedRealtime();
                try {
                    pumpDirectYuvFrame(imageReader.getSurface());
                } catch (Throwable ignored) {
                }
                preRefreshYuvCache(YuvCallbackPump.this);
                dispatchWhatsAppYuvCallback(YuvCallbackPump.this);
                long elapsed = SystemClock.elapsedRealtime() - startMs;
                int pixels = imageReader.getWidth() * imageReader.getHeight();
                long targetMs = lastYuvFrameWasCodec ? 33L
                        : (pixels > YUV_HIRES_PIXEL_THRESHOLD ? 133L : 66L);
                long delay = Math.max(16L, targetMs - elapsed);
                whatsappYuvPumpHandler.postDelayed(this, delay);
            }
        };

        YuvCallbackPump(ImageReader imageReader) {
            this.imageReader = imageReader;
        }

        Method resolveOnImageAvailableMethod() throws NoSuchMethodException {
            Method method = onImageAvailableMethod;
            if (method != null) {
                return method;
            }
            Object currentListener = listener;
            if (currentListener == null) {
                throw new NoSuchMethodException("Listener missing");
            }
            method = currentListener.getClass().getMethod("onImageAvailable", ImageReader.class);
            method.setAccessible(true);
            onImageAvailableMethod = method;
            return method;
        }
    }

    /** 获取当前活跃的 GLVideoRenderer（优先 preview，其次 reader） */
    public GLVideoRenderer getActiveRenderer() {
        MediaPlayerManager pm = HookMain.playerManager;
        if (pm.c2_renderer != null && pm.c2_renderer.isInitialized())
            return pm.c2_renderer;
        if (pm.c2_renderer_1 != null && pm.c2_renderer_1.isInitialized())
            return pm.c2_renderer_1;
        if (pm.c2_reader_renderer != null && pm.c2_reader_renderer.isInitialized())
            return pm.c2_reader_renderer;
        if (pm.c2_reader_renderer_1 != null && pm.c2_reader_renderer_1.isInitialized())
            return pm.c2_reader_renderer_1;
        return null;
    }

    private GLVideoRenderer getPreferredYuvRenderer() {
        MediaPlayerManager pm = HookMain.playerManager;
        if (pm.c2_renderer != null && pm.c2_renderer.isInitialized()) {
            return pm.c2_renderer;
        }
        if (pm.c2_renderer_1 != null && pm.c2_renderer_1.isInitialized()) {
            return pm.c2_renderer_1;
        }
        return getActiveRenderer();
    }

    private void markYuvBridgeSessionReadyIfPossible() {
        yuvBridgeSessionReady = getActiveRenderer() != null || VideoManager.hasUsableMediaSource();
        if (yuvBridgeSessionReady) {
            startOrRestartYuvDecoder();
            startDeferredYuvPumpsIfReady();
        }
    }

    /** 启动或重启 MediaCodec YUV 解码器 */
    private void startOrRestartYuvDecoder() {
        if (!VideoManager.hasUsableMediaSource()) {
            return;
        }
        MediaCodecYuvDecoder dec = yuvDecoder;
        if (dec != null && dec.isRunning()) {
            return; // 已在运行
        }
        if (dec != null) {
            dec.stop();
        }
        dec = new MediaCodecYuvDecoder();
        yuvDecoder = dec;
        dec.start();
        LogUtil.log("【CS】MediaCodec YUV 解码器已启动");
    }

    /** 媒体源变更时重启解码器（切换视频/旋转等） */
    public void restartYuvDecoderForSourceChange() {
        MediaCodecYuvDecoder dec = yuvDecoder;
        if (dec != null) {
            dec.stop();
            yuvDecoder = null;
        }
        lastYuvFrameWasCodec = false;
        if (yuvBridgeSessionReady) {
            startOrRestartYuvDecoder();
        }
    }

    private byte[] createFakeJpegBytes(Surface targetSurface, int maxBytes) {
        int targetWidth = HookMain.c2_ori_width;
        int targetHeight = HookMain.c2_ori_height;
        int[] size = surfaceSizeMap.get(targetSurface);
        if (size != null && size.length >= 2) {
            targetWidth = size[0];
            targetHeight = size[1];
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            targetWidth = 1280;
            targetHeight = 720;
        }

        Bitmap frame = captureFrameForStill(targetWidth, targetHeight);
        if (frame == null) {
            LogUtil.log("【CS】无法获取可用静态帧");
            return null;
        }

        return compressBitmapToJpeg(frame, maxBytes);
    }

    /**
     * 截取当前帧（使用渲染器当前旋转），用于 JPEG 拍照替换。
     */
    private Bitmap captureFrameForStill(int targetWidth, int targetHeight) {
        return captureFrameInternal(targetWidth, targetHeight, -1);
    }

    /**
     * 截取当前帧并强制应用 video_rotation_offset 旋转，用于 WhatsApp YUV 帧生成。
     * 预览渲染器旋转设为 0°（本机自拍画面方向正确），
     * 但 YUV 帧需要 video_rotation_offset 旋转才能让对方看到正确方向。
     */
    private Bitmap captureFrameForYuv(int targetWidth, int targetHeight) {
        int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        return captureFrameInternal(targetWidth, targetHeight, rotation);
    }

    /**
     * @param rotationOverride -1=使用渲染器当前旋转, >=0=临时覆盖指定角度
     */
    private Bitmap captureFrameInternal(int targetWidth, int targetHeight, int rotationOverride) {
        if (isReleasing) {
            return null;
        }
        if (!VideoManager.hasUsableMediaSource()) {
            lastYuvFrameWasFallback = true;
            return null;
        }
        GLVideoRenderer activeRenderer = getPreferredYuvRenderer();
        if (activeRenderer != null) {
            int captureWidth = activeRenderer.getSurfaceWidth();
            int captureHeight = activeRenderer.getSurfaceHeight();
            if (captureWidth <= 0 || captureHeight <= 0) {
                captureWidth = targetWidth;
                captureHeight = targetHeight;
            }

            Bitmap frame = activeRenderer.captureFrameWithRotation(
                    captureWidth, captureHeight, rotationOverride);
            if (frame != null) {
                frame = fitBitmapToTargetAspect(frame, targetWidth, targetHeight);
                if (!isBitmapMostlyBlack(frame)) {
                    lastYuvFrameWasFallback = false;
                    return frame;
                }
                maybeLogGlBlackFallback();
                frame.recycle();
            } else {
                maybeLogGlNullFallback();
            }
        } else {
            maybeLogNoGlRendererFallback();
        }

        lastYuvFrameWasFallback = true;

        return captureFrameFromVideoFile(targetWidth, targetHeight);
    }

    /** 缓存的 MediaMetadataRetriever 实例，避免每帧都重新创建和 setDataSource */
    private MediaMetadataRetriever cachedRetriever;
    private String cachedRetrieverPath;

    private Bitmap captureFrameFromVideoFile(int targetWidth, int targetHeight) {
        // Stream mode: MediaMetadataRetriever cannot work with network URLs.
        // Return null to let caller use GL capture or last-frame cache.
        if (VideoManager.isStreamMode()) {
            LogUtil.log("【CS】流模式下跳过 MediaMetadataRetriever 截帧");
            return null;
        }
        try {
            String currentPath = VideoManager.getCurrentVideoPath();
            // 复用已有 retriever，只在路径变化时重新创建
            if (cachedRetriever == null || cachedRetrieverPath == null
                    || !cachedRetrieverPath.equals(currentPath)) {
                releaseCachedRetriever();
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
                if (pfd != null) {
                    try {
                        retriever.setDataSource(pfd.getFileDescriptor());
                    } finally {
                        try {
                            pfd.close();
                        } catch (Exception ignored) {
                        }
                    }
                } else {
                    retriever.setDataSource(currentPath);
                }
                cachedRetriever = retriever;
                cachedRetrieverPath = currentPath;
            }

            long positionUs = HookMain.playerManager.getCamera2PlaybackPositionMs() * 1000L;
            if (positionUs <= 0) {
                positionUs = 33_000L;
            }
            // 使用 OPTION_CLOSEST_SYNC 而非 OPTION_CLOSEST：
            // OPTION_CLOSEST 需要精确 seek 到指定时间戳并解码到该帧，非常慢（~500-800ms）；
            // OPTION_CLOSEST_SYNC 只 seek 到最近的关键帧，通常 <100ms，
            // 对于预览目的足够用，牺牲精度换取帧率。
            Bitmap frame = cachedRetriever.getFrameAtTime(positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null && positionUs > 0) {
                frame = cachedRetriever.getFrameAtTime(-1,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                return null;
            }
            return fitBitmapToTargetAspect(frame, targetWidth, targetHeight);
        } catch (Exception e) {
            LogUtil.log("【CS】视频文件截帧失败: " + e);
            // 出错时释放缓存的 retriever，下次重试
            releaseCachedRetriever();
            return null;
        }
    }

    private void releaseCachedRetriever() {
        if (cachedRetriever != null) {
            try {
                cachedRetriever.release();
            } catch (Exception ignored) {
            }
            cachedRetriever = null;
            cachedRetrieverPath = null;
        }
    }

    private Bitmap fitBitmapToTargetAspect(Bitmap source, int targetWidth, int targetHeight) {
        if (source == null) {
            return null;
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            return source;
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return source;
        }

        float sourceAspect = (float) sourceWidth / (float) sourceHeight;
        float targetAspect = (float) targetWidth / (float) targetHeight;

        Rect cropRect;
        if (Math.abs(sourceAspect - targetAspect) < 0.001f) {
            cropRect = new Rect(0, 0, sourceWidth, sourceHeight);
        } else if (sourceAspect > targetAspect) {
            int croppedWidth = Math.max(1, Math.round(sourceHeight * targetAspect));
            int left = Math.max(0, (sourceWidth - croppedWidth) / 2);
            cropRect = new Rect(left, 0, Math.min(sourceWidth, left + croppedWidth), sourceHeight);
        } else {
            int croppedHeight = Math.max(1, Math.round(sourceWidth / targetAspect));
            int top = Math.max(0, (sourceHeight - croppedHeight) / 2);
            cropRect = new Rect(0, top, sourceWidth, Math.min(sourceHeight, top + croppedHeight));
        }

        Bitmap cropped = source;
        if (cropRect.left != 0 || cropRect.top != 0 || cropRect.width() != sourceWidth
                || cropRect.height() != sourceHeight) {
            cropped = Bitmap.createBitmap(source, cropRect.left, cropRect.top, cropRect.width(), cropRect.height());
            source.recycle();
        }

        if (cropped.getWidth() == targetWidth && cropped.getHeight() == targetHeight) {
            return cropped;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true);
        if (scaled != cropped) {
            cropped.recycle();
        }
        return scaled;
    }

    private byte[] compressBitmapToJpeg(Bitmap frame, int maxBytes) {
        if (frame == null) {
            return null;
        }

        int[] qualities = { 92, 80, 65, 50 };
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] jpegBytes = null;
        try {
            for (int quality : qualities) {
                baos.reset();
                frame.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                byte[] candidate = baos.toByteArray();
                if (maxBytes <= 0 || candidate.length <= maxBytes) {
                    jpegBytes = candidate;
                    break;
                }
            }
        } finally {
            frame.recycle();
        }

        if (jpegBytes == null) {
            LogUtil.log("【CS】照片压缩后依然大于 Buffer 容量: " + maxBytes);
        }
        return jpegBytes;
    }

    private boolean isBitmapMostlyBlack(Bitmap bitmap) {
        if (bitmap == null) {
            return true;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return true;
        }
        int stepX = Math.max(1, width / 8);
        int stepY = Math.max(1, height / 8);
        int samples = 0;
        int darkSamples = 0;
        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int brightness = (r + g + b) / 3;
                samples++;
                if (brightness < 16) {
                    darkSamples++;
                }
            }
        }
        return samples == 0 || darkSamples * 100 / samples >= 85;
    }

    public boolean replaceJpegImageIfNeeded(Object imageReader, Image image) {
        if (imageReader == null || image == null) {
            return false;
        }
        try {
            Surface surface = ((ImageReader) imageReader).getSurface();
            if (image.getPlanes() == null || image.getPlanes().length == 0) {
                LogUtil.log("【CS】JPEG Image 无可写 Plane，放弃替换");
                return false;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            if (buffer == null || buffer.isReadOnly()) {
                LogUtil.log("【CS】JPEG Plane Buffer 不可用或只读，放弃替换");
                return false;
            }

            byte[] jpegBytes = createFakeJpegBytes(surface, buffer.capacity());
            if (jpegBytes == null || jpegBytes.length == 0) {
                return false;
            }

            buffer.clear();
            buffer.put(jpegBytes);
            buffer.position(0);
            buffer.limit(jpegBytes.length);
            try {
                image.setCropRect(new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()));
            } catch (Throwable ignored) {
            }
            pendingJpegSurfaces.remove(surface);
            pendingPhotoSurface = null;
            LogUtil.log("【CS】成功替换 JPEG 拍照照片为当前虚拟画面: 大小=" + jpegBytes.length + " 字节");
            return true;
        } catch (Exception e) {
            LogUtil.log("【CS】替换 JPEG Image 失败: " + e);
            return false;
        }
    }

    private volatile boolean isYuvPumperRunning = false;
    private final Runnable yuvContinuousPumpRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isYuvPumperRunning || isReleasing) {
                return;
            }
            long startMs = SystemClock.elapsedRealtime();
            try {
                for (Surface surface : sessionKeptYuvSurfaces) {
                    if (surface != null && surface.isValid()) {
                        pumpDirectYuvFrame(surface);
                    }
                }
            } catch (Throwable ignored) {
            }
            long elapsed = SystemClock.elapsedRealtime() - startMs;
            long delay = Math.max(10L, 33L - elapsed);
            if (whatsappYuvPumpHandler != null && isYuvPumperRunning) {
                whatsappYuvPumpHandler.postDelayed(this, delay);
            }
        }
    };

    public void startContinuousYuvPumper() {
        if (isYuvPumperRunning) return;
        isYuvPumperRunning = true;
        ensureWhatsAppYuvPumpHandler();
        if (whatsappYuvPumpHandler != null) {
            whatsappYuvPumpHandler.post(yuvContinuousPumpRunnable);
            LogUtil.log("【CS】30fps 持续 YUV 推送定时器已启动");
        }
    }

    public void stopContinuousYuvPumper() {
        isYuvPumperRunning = false;
        if (whatsappYuvPumpHandler != null) {
            whatsappYuvPumpHandler.removeCallbacks(yuvContinuousPumpRunnable);
        }
    }

    /**
     * Register a YUV ImageReader surface for continuous fake-frame pumping.
     * Used when addTarget redirects a YUV surface (e.g. IMO's VideoCapturer2)
     * so the reader keeps receiving fake frames instead of starving.
     */
    public void keepYuvSurfaceForPumping(android.view.Surface surface) {
        if (surface != null && surface.isValid()) {
            sessionKeptYuvSurfaces.add(surface);
            LogUtil.log("【CS】YUV surface registered for continuous pumping: " + surface);
            startContinuousYuvPumper();
        }
    }

    private final java.util.concurrent.atomic.AtomicLong lastMonotonicPtsNs =
            new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtimeNanos());

    public long getNextMonotonicPtsNs() {
        long now = SystemClock.elapsedRealtimeNanos();
        while (true) {
            long last = lastMonotonicPtsNs.get();
            long next = Math.max(now, last + 33_333_333L);
            if (lastMonotonicPtsNs.compareAndSet(last, next)) {
                return next;
            }
        }
    }

    /**
     * 纳秒级单调递增 PTS + 严格 RowStride 内存跨距对齐的 Direct YUV 帧注入
     */
    public boolean pumpDirectYuvFrame(Surface targetSurface) {
        if (targetSurface == null || !targetSurface.isValid() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        MediaCodecYuvDecoder dec = yuvDecoder;
        if (dec == null) {
            return false;
        }
        MediaCodecYuvDecoder.YuvFrame yuv = dec.acquireLatestFrame();
        if (yuv == null || yuv.yPlane == null || yuv.uPlane == null || yuv.vPlane == null) {
            return false;
        }

        try {
            ImageWriter writer = imageWriterMap.get(targetSurface);
            if (writer == null) {
                writer = ImageWriter.newInstance(targetSurface, 2);
                imageWriterMap.put(targetSurface, writer);
            }
            Image image = writer.dequeueInputImage();
            if (image == null) {
                return false;
            }

            try {
                copyYuvFrameToImageWithStride(yuv, image);
                image.setTimestamp(getNextMonotonicPtsNs());
                writer.queueInputImage(image);
                return true;
            } catch (Throwable t) {
                try { image.close(); } catch (Throwable ignored) {}
                LogUtil.log("【CS】YUV 跨距对齐写入异常: " + t.getMessage());
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】Direct YUV 泵送异常: " + t.getMessage());
        }
        return false;
    }

    private void copyYuvFrameToImageWithStride(MediaCodecYuvDecoder.YuvFrame yuv, Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return;

        int userRotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0) % 360;
        if (userRotation < 0) userRotation += 360;

        int imgW = image.getWidth();
        int imgH = image.getHeight();
        int srcW = yuv.width;
        int srcH = yuv.height;

        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        byte[] ySrc = yuv.yPlane;
        yBuf.clear();

        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        byte[] uSrc = yuv.uPlane;
        byte[] vSrc = yuv.vPlane;
        uBuf.clear();
        vBuf.clear();

        int uvW = imgW / 2;
        int uvH = imgH / 2;
        int srcUvW = srcW / 2;
        int srcUvH = srcH / 2;

        if (userRotation == 0) {
            int width = Math.min(imgW, srcW);
            int height = Math.min(imgH, srcH);
            for (int r = 0; r < height; r++) {
                yBuf.position(r * yRowStride);
                int srcOffset = r * srcW;
                if (yPixelStride == 1) {
                    yBuf.put(ySrc, srcOffset, width);
                } else {
                    for (int c = 0; c < width; c++) {
                        yBuf.put(r * yRowStride + c * yPixelStride, ySrc[srcOffset + c]);
                    }
                }
            }
            int uvWidth = width / 2;
            int uvHeight = height / 2;
            for (int r = 0; r < uvHeight; r++) {
                int srcOffset = r * srcUvW;
                for (int c = 0; c < uvWidth; c++) {
                    uBuf.put(r * uRowStride + c * uPixelStride, uSrc[srcOffset + c]);
                    vBuf.put(r * vRowStride + c * vPixelStride, vSrc[srcOffset + c]);
                }
            }
        } else if (userRotation == 90) {
            int width = Math.min(imgW, srcH);
            int height = Math.min(imgH, srcW);
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    int srcR = srcH - 1 - c;
                    int srcC = r;
                    if (srcR >= 0 && srcR < srcH && srcC >= 0 && srcC < srcW) {
                        yBuf.put(r * yRowStride + c * yPixelStride, ySrc[srcR * srcW + srcC]);
                    }
                }
            }
            int uvWidth = width / 2;
            int uvHeight = height / 2;
            for (int r = 0; r < uvHeight; r++) {
                for (int c = 0; c < uvWidth; c++) {
                    int srcR = srcUvH - 1 - c;
                    int srcC = r;
                    if (srcR >= 0 && srcR < srcUvH && srcC >= 0 && srcC < srcUvW) {
                        uBuf.put(r * uRowStride + c * uPixelStride, uSrc[srcR * srcUvW + srcC]);
                        vBuf.put(r * vRowStride + c * vPixelStride, vSrc[srcR * srcUvW + srcC]);
                    }
                }
            }
        } else if (userRotation == 180) {
            int width = Math.min(imgW, srcW);
            int height = Math.min(imgH, srcH);
            for (int r = 0; r < height; r++) {
                int srcR = srcH - 1 - r;
                for (int c = 0; c < width; c++) {
                    int srcC = srcW - 1 - c;
                    if (srcR >= 0 && srcR < srcH && srcC >= 0 && srcC < srcW) {
                        yBuf.put(r * yRowStride + c * yPixelStride, ySrc[srcR * srcW + srcC]);
                    }
                }
            }
            int uvWidth = width / 2;
            int uvHeight = height / 2;
            for (int r = 0; r < uvHeight; r++) {
                int srcR = srcUvH - 1 - r;
                for (int c = 0; c < uvWidth; c++) {
                    int srcC = srcUvW - 1 - c;
                    if (srcR >= 0 && srcR < srcUvH && srcC >= 0 && srcC < srcUvW) {
                        uBuf.put(r * uRowStride + c * uPixelStride, uSrc[srcR * srcUvW + srcC]);
                        vBuf.put(r * vRowStride + c * vPixelStride, vSrc[srcR * srcUvW + srcC]);
                    }
                }
            }
        } else if (userRotation == 270) {
            int width = Math.min(imgW, srcH);
            int height = Math.min(imgH, srcW);
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    int srcR = c;
                    int srcC = srcW - 1 - r;
                    if (srcR >= 0 && srcR < srcH && srcC >= 0 && srcC < srcW) {
                        yBuf.put(r * yRowStride + c * yPixelStride, ySrc[srcR * srcW + srcC]);
                    }
                }
            }
            int uvWidth = width / 2;
            int uvHeight = height / 2;
            for (int r = 0; r < uvHeight; r++) {
                for (int c = 0; c < uvWidth; c++) {
                    int srcR = c;
                    int srcC = srcUvW - 1 - r;
                    if (srcR >= 0 && srcR < srcUvH && srcC >= 0 && srcC < srcUvW) {
                        uBuf.put(r * uRowStride + c * uPixelStride, uSrc[srcR * srcUvW + srcC]);
                        vBuf.put(r * vRowStride + c * vPixelStride, vSrc[srcR * srcUvW + srcC]);
                    }
                }
            }
        }
    }

    public void createOrPumpImage(Surface targetSurface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return;
        try {
            ImageWriter writer = imageWriterMap.get(targetSurface);
            if (writer == null) {
                writer = ImageWriter.newInstance(targetSurface, 2);
                imageWriterMap.put(targetSurface, writer);
            }
            Image image = writer.dequeueInputImage();
            if (image == null)
                return;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpegBytes = createFakeJpegBytes(targetSurface, buffer.capacity());
            if (jpegBytes == null) {
                image.close();
                return;
            }
            buffer.clear();
            buffer.put(jpegBytes);
            buffer.flip();
            image.setTimestamp(getNextMonotonicPtsNs());
            writer.queueInputImage(image);
            LogUtil.log("【CS】成功泵入一张伪造图片 (" + jpegBytes.length + " bytes)");
        } catch (Exception e) {
            LogUtil.log("【CS】照片注入失败: " + e);
        }
    }

    // =====================================================================
    // API 101 utilities
    // =====================================================================

    private static Object invokeNoArgMethod(Object target, String methodName) throws Exception {
        if (target == null) {
            return null;
        }
        Method method = resolveMethodOnClass(target.getClass(), methodName);
        return method.invoke(target);
    }

    private static void invokeCompatibleSingleArgMethod(Object target, String methodName,
            Object value) throws Exception {
        if (target == null || value == null) {
            return;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            Method[] methods = current.getDeclaredMethods();
            for (Method method : methods) {
                if (!method.getName().equals(methodName) || method.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> parameterType = method.getParameterTypes()[0];
                if (!isParameterCompatible(parameterType, value.getClass())) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(target, value);
                return;
            }
            current = current.getSuperclass();
        }
    }

    private static boolean isParameterCompatible(Class<?> parameterType, Class<?> valueClass) {
        if (parameterType.isPrimitive()) {
            return (parameterType == int.class && Integer.class.isAssignableFrom(valueClass))
                    || (parameterType == long.class && Long.class.isAssignableFrom(valueClass))
                    || (parameterType == boolean.class && Boolean.class.isAssignableFrom(valueClass))
                    || (parameterType == float.class && Float.class.isAssignableFrom(valueClass))
                    || (parameterType == double.class && Double.class.isAssignableFrom(valueClass))
                    || (parameterType == byte.class && Byte.class.isAssignableFrom(valueClass))
                    || (parameterType == short.class && Short.class.isAssignableFrom(valueClass))
                    || (parameterType == char.class && Character.class.isAssignableFrom(valueClass));
        }
        return parameterType.isAssignableFrom(valueClass);
    }

    private static Method resolveMethodOnClass(Class<?> clazz, String methodName,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + methodName);
    }

    private static Object[] toArgs(List<Object> args) {
        return args.toArray(new Object[0]);
    }
}
