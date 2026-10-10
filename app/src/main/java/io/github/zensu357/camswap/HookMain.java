package io.github.zensu357.camswap;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Arrays;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import io.github.libxposed.api.XposedInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;

import io.github.zensu357.camswap.utils.HookUtils;
import io.github.zensu357.camswap.utils.PermissionHelper;
import io.github.zensu357.camswap.utils.VideoManager;
import io.github.zensu357.camswap.utils.LogUtil;

public class HookMain {
    public static final MediaPlayerManager playerManager = new MediaPlayerManager();
    public static final Camera2SessionHook camera2Hook = new Camera2SessionHook(playerManager);
    private static volatile boolean activityLifecycleRegistered = false;
    private final ThreadLocal<Integer> imageReaderNewInstanceHookDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    // Camera1 shared state
    public static Surface mSurface;
    public static SurfaceTexture mSurfacetexture;
    public static SurfaceTexture fake_SurfaceTexture;
    public static Camera origin_preview_camera;
    public static Camera camera_onPreviewFrame;
    public static Camera start_preview_camera;
    public static volatile byte[] data_buffer;
    public static byte[] input;
    public static int mhight;
    public static int mwidth;
    public static boolean is_someone_playing;
    public static boolean is_hooked;
    public static VideoToFrames hw_decode_obj;
    public static SurfaceTexture c1_fake_texture;
    public static Surface c1_fake_surface;
    public static SurfaceHolder ori_holder;
    public static Camera mcamera1;
    public static volatile int mDisplayOrientation = 0;

    // Camera2 shared state
    public static int imageReaderFormat = 0;
    public static boolean need_to_show_toast = true;
    public static int c2_ori_width = 1280;
    public static int c2_ori_height = 720;
    public static Class c2_state_callback;
    public static CameraDevice.StateCallback c2_state_cb;
    public static Context toast_content;
    private static long lastImageReaderToastMs = 0L;
    private static String lastImageReaderToastMsg = "";

    // =====================================================================
    // Delegates (kept for backward compatibility with Camera1/2 Handlers)
    // =====================================================================

    public static android.os.ParcelFileDescriptor getVideoPFD() {
        return VideoManager.getVideoPFD();
    }

    private static void checkProviderAvailability() {
        VideoManager.checkProviderAvailability();
    }

    public static ConfigManager getConfig() {
        return VideoManager.getConfig();
    }

    public static void updateVideoPath(boolean forceRandom) {
        VideoManager.updateVideoPath(forceRandom);
    }

    public static String getCurrentVideoPath() {
        return VideoManager.getCurrentVideoPath();
    }

    public static void reloadRandomVideo() {
        VideoManager.updateVideoPath(true);
    }

    public static void updateAllRendererRotations(int degrees) {
        playerManager.updateRotation(degrees);
    }

    public static void releaseAllRenderers() {
        playerManager.releaseAllRenderers();
    }

    public static void process_camera2_play() {
        camera2Hook.startPlayback();
    }

    public static void process_camera2_init(Class hooked_class) {
        camera2Hook.hookStateCallback(hooked_class);
    }

    public static void showToast(final String message) {
        PermissionHelper.showToast(toast_content, message);
    }

    // =====================================================================
    // Configuration watching (delegated to ConfigWatcher)
    // =====================================================================

    private static ConfigWatcher configWatcher;

    private static void initContentObserver(final Context context) {
        if (configWatcher == null) {
            configWatcher = new ConfigWatcher(new ConfigWatcher.Callback() {
                @Override
                public void onMediaSourceChanged() {
                    playerManager.restartAll();
                    camera2Hook.restartYuvDecoderForSourceChange();
                }

                @Override
                public void onRotationChanged(int degrees) {
                    playerManager.updateRotation(degrees);
                    camera2Hook.restartYuvDecoderForSourceChange();
                }
            });
            configWatcher.init(context);
        }
    }

    private static void switchVideo(boolean next) {
        if (VideoManager.switchVideo(next)) {
            playerManager.restartAll();
        }
    }

    // =====================================================================
    // Entry point
    // =====================================================================

    public void handleLoadPackage(final Api101PackageContext packageContext) throws Exception {
        final ClassLoader classLoader = packageContext.classLoader;
        final String packageName = packageContext.hostPackageName;
        // Check if module is disabled
        if (getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            LogUtil.log("【CS】模块已被配置禁用");
            return;
        }

        Set<String> targetPackages = getConfig().getTargetPackages();
        if (!targetPackages.isEmpty() && !targetPackages.contains(packageName)) {
            return;
        }

        camera2Hook.setCurrentPackageName(packageName);
        playerManager.setPackageName(packageName);

        // Initialize Camera Handlers
        new Camera1Handler().init(packageContext);
        new Camera2Handler().init(packageContext);
        new LineElsaHandler().init(packageContext);
        new ImoHandler().init(packageContext);

        // Initialize Microphone Handler
        new MicrophoneHandler().init(packageContext);

        hookMediaRecorderSetCamera(classLoader, packageName, packageContext);
        hookCallApplicationOnCreate(classLoader, packageName);
        hookImageReaderNewInstance(classLoader);
        hookImageReaderAcquireMethods(classLoader);
        hookImageReaderListener(classLoader);
        hookCaptureFailed(classLoader);
    }

    private void hookMediaRecorderSetCamera(ClassLoader classLoader, String packageName,
            Api101PackageContext packageContext) {
        try {
            Method method = resolveMethod(classLoader, "android.media.MediaRecorder", "setCamera", Camera.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                    LogUtil.log("【CS】[record]" + packageName);
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            showToast("应用：" + packageContext.appInfo.name + "(" + packageName + ")"
                                    + "触发了录像，但目前无法拦截");
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + Arrays.toString(ee.getStackTrace()));
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】MediaRecorder.setCamera before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook MediaRecorder.setCamera 失败: " + t);
        }
    }

    private void hookCallApplicationOnCreate(ClassLoader classLoader, String packageName) {
        try {
            Method method = resolveMethod(classLoader, "android.app.Instrumentation", "callApplicationOnCreate",
                    Application.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                Object result = chain.proceed(args);
                try {
                    if (args.length > 0 && args[0] instanceof Application) {
                        Application application = (Application) args[0];
                        registerActivityLifecycleCallbacks(application);
                        toast_content = application.getApplicationContext();
                        VideoManager.setContext(toast_content);
                        getConfig().setContext(toast_content);

                        new Thread(() -> {
                            try {
                                checkProviderAvailability();
                                getConfig().forceReload();
                                VideoManager.updateVideoPath(false);
                                initContentObserver(toast_content);
                                NativeAudioHook.init();
                                PermissionHelper.checkAndSetupPaths(toast_content, packageName);
                                LogUtil.log("【CS】后台预热完成：配置与视频路径已就绪 (" + packageName + ")");
                            } catch (Throwable t) {
                                LogUtil.log("【CS】后台预热异常: " + t);
                            }
                        }, "CS-AppWarmup").start();
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】callApplicationOnCreate after 异常: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook callApplicationOnCreate 失败: " + t);
        }
    }

    private void registerActivityLifecycleCallbacks(Application application) {
        if (application == null || activityLifecycleRegistered) {
            return;
        }
        activityLifecycleRegistered = true;
        try {
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    if (activity != null) {
                        String activityName = activity.getClass().getName();
                        camera2Hook.setCurrentActivityClassName(activityName);
                        LogUtil.log("【CS】当前 Activity: " + activityName);
                    }
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
        } catch (Throwable t) {
            activityLifecycleRegistered = false;
            LogUtil.log("【CS】注册 ActivityLifecycleCallbacks 失败: " + t);
        }
    }

    private void hookImageReaderNewInstance(ClassLoader classLoader) {
        // Some CameraX builds still invoke the 4-param overload directly on Android Q+.
        // Hook both overloads and dedupe nested 4 -> 5 delegation with a depth guard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hookImageReaderNewInstance(classLoader, int.class, int.class, int.class, int.class);
            hookImageReaderNewInstance(classLoader, int.class, int.class, int.class, int.class, long.class);
        } else {
            hookImageReaderNewInstance(classLoader, int.class, int.class, int.class, int.class);
        }
        // Android T+ (API 33): CameraX may use ImageReader.Builder instead of newInstance.
        // Hook Builder.build() to ensure all ImageReader surfaces are tracked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hookImageReaderBuilderBuild(classLoader);
        }
    }

    private void hookImageReaderBuilderBuild(ClassLoader classLoader) {
        try {
            Class<?> builderClass = Class.forName("android.media.ImageReader$Builder", false, classLoader);
            Method buildMethod = builderClass.getDeclaredMethod("build");
            Api101Runtime.requireModule().hook(buildMethod).intercept(chain -> {
                Object result = chain.proceed(toArgs(chain.getArgs()));
                try {
                    if (result instanceof ImageReader) {
                        ImageReader reader = (ImageReader) result;
                        Surface surface = reader.getSurface();
                        if (surface != null && !camera2Hook.shouldSkipImageReaderTracking()) {
                            int w = reader.getWidth();
                            int h = reader.getHeight();
                            int fmt = reader.getImageFormat();
                            boolean alreadyTracked = camera2Hook.isTrackedReaderSurface(surface);
                            camera2Hook.registerImageReaderSurface(surface, fmt, w, h);
                            if (!alreadyTracked) {
                                LogUtil.log("【CS】已记录 ImageReader.Builder Surface: "
                                        + surface + " format=" + fmt + " " + w + "x" + h);
                            }
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】ImageReader.Builder.build after 异常: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook ImageReader.Builder.build 失败 (可能低于 API 33): " + t);
        }
    }

    private void hookImageReaderNewInstance(ClassLoader classLoader, Class<?>... parameterTypes) {
        try {
            Method method = resolveMethod(classLoader, "android.media.ImageReader", "newInstance", parameterTypes);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                boolean isOutermostHook = enterImageReaderNewInstanceHook();
                try {
                    if (isOutermostHook) {
                        onImageReaderNewInstanceBefore(args);
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】ImageReader.newInstance before 异常: " + t);
                }

                try {
                    Object result = chain.proceed(args);

                    try {
                        if (isOutermostHook) {
                            onImageReaderNewInstanceAfter(args, result);
                        }
                    } catch (Throwable t) {
                        LogUtil.log("【CS】ImageReader.newInstance after 异常: " + t);
                    }
                    return result;
                } finally {
                    exitImageReaderNewInstanceHook();
                }
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook ImageReader.newInstance 失败: " + t);
        }
    }

    private boolean enterImageReaderNewInstanceHook() {
        int depth = imageReaderNewInstanceHookDepth.get();
        imageReaderNewInstanceHookDepth.set(depth + 1);
        return depth == 0;
    }

    private void exitImageReaderNewInstanceHook() {
        int depth = imageReaderNewInstanceHookDepth.get();
        if (depth <= 1) {
            imageReaderNewInstanceHookDepth.remove();
        } else {
            imageReaderNewInstanceHookDepth.set(depth - 1);
        }
    }

    private void hookImageReaderAcquireMethods(ClassLoader classLoader) {
        hookImageReaderAcquireMethod(classLoader, "acquireNextImage");
        hookImageReaderAcquireMethod(classLoader, "acquireLatestImage");
    }

    private void hookImageReaderAcquireMethod(ClassLoader classLoader, String methodName) {
        try {
            Method method = resolveMethod(classLoader, "android.media.ImageReader", methodName);
            XposedInterface.Invoker<?, Method> originInvoker = Api101Runtime.requireModule()
                    .getInvoker(method)
                    .setType(XposedInterface.Invoker.Type.ORIGIN);
            Api101Runtime.requireModule().hook(method).intercept(chain -> interceptImageReaderAcquire(chain, originInvoker));
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook ImageReader." + methodName + " 失败: " + t);
        }
    }

    private Object interceptImageReaderAcquire(XposedInterface.Chain chain,
            XposedInterface.Invoker<?, Method> originInvoker) throws Throwable {
        Object[] args = toArgs(chain.getArgs());
        Object thisObject = chain.getThisObject();
        if (!(thisObject instanceof ImageReader)) {
            return chain.proceed(args);
        }

        ImageReader imageReader = (ImageReader) thisObject;
        Surface surface = imageReader.getSurface();
        Object result;
        boolean isYuvReader = false;
        try {
            isYuvReader = imageReader.getImageFormat() == android.graphics.ImageFormat.YUV_420_888;
        } catch (Throwable ignored) {
        }
        String pkg = camera2Hook.getCurrentPackageName();
        boolean isLineOrWa = Camera2SessionHook.isWhatsAppPackage(pkg) || Camera2SessionHook.isLinePackage(pkg);
        boolean shouldInterceptYuv = isYuvReader && isLineOrWa && !camera2Hook.shouldBypassYuvAcquireHook(surface);

        if (!shouldInterceptYuv) {
            try {
                result = chain.proceed(args);
            } catch (UnsupportedOperationException e) {
                // GL renderer produces RGBA but ImageReader expects YUV — return null
                // to prevent crash (CameraX handles null from acquireLatestImage).
                LogUtil.log("【CS】ImageReader acquire 格式不匹配，返回 null: " + e.getMessage());
                return null;
            }
        } else {
            try {
                result = acquireFakeWhatsAppYuvImage(imageReader, surface, args, originInvoker);
                if (result == null) {
                    result = chain.proceed(args);
                }
            } catch (Throwable t) {
                LogUtil.log("【CS】YUV ImageReader 兼容处理失败: " + t);
                result = chain.proceed(args);
            }
        }

        return maybeReplaceJpegImage(imageReader, surface, result);
    }

    private Object acquireFakeWhatsAppYuvImage(ImageReader imageReader, Surface surface, Object[] args,
            XposedInterface.Invoker<?, Method> originInvoker) throws Throwable {
        try {
            Object result = camera2Hook.acquireFakeWhatsAppYuvImage(imageReader, surface);
            drainOriginImage(originInvoker, imageReader, args);
            return result;
        } catch (Throwable ignored) {
            Object fallback = camera2Hook.acquireFakeWhatsAppYuvImage(imageReader, surface);
            drainOriginImage(originInvoker, imageReader, args);
            return fallback;
        }
    }

    private void drainOriginImage(XposedInterface.Invoker<?, Method> originInvoker, ImageReader imageReader,
            Object[] args) {
        try {
            Image realImage = (Image) invokeOrigin(originInvoker, imageReader, args);
            if (realImage != null) {
                realImage.close();
            }
        } catch (Throwable ignored) {
            // drain 失败（例如无帧可取或格式异常），安全忽略
        }
    }

    private Object maybeReplaceJpegImage(ImageReader imageReader, Surface surface, Object result) {
        if (!(result instanceof Image)) {
            return result;
        }
        try {
            Image image = (Image) result;
            if (image.getFormat() == android.graphics.ImageFormat.JPEG || camera2Hook.isJpegReaderSurface(surface)) {
                camera2Hook.replaceJpegImageIfNeeded(imageReader, image);
            }
        } catch (Exception e) {
            LogUtil.log("【CS】处理 ImageReader 结果失败: " + e);
        }
        return result;
    }

    private void onImageReaderNewInstanceBefore(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        LogUtil.log("【CS】ImageReader: " + args[0] + "x" + args[1] + " fmt=" + args[2]);
        c2_ori_width = (int) args[0];
        c2_ori_height = (int) args[1];
        imageReaderFormat = (int) args[2];
        need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        if (toast_content != null && need_to_show_toast) {
            String msg = "渲染器: 宽 " + args[0] + "px  高 " + args[1] + "px";
            long now = SystemClock.elapsedRealtime();
            if (now - lastImageReaderToastMs > 4000L || !msg.equals(lastImageReaderToastMsg)) {
                lastImageReaderToastMs = now;
                lastImageReaderToastMsg = msg;
                try {
                    showToast(msg);
                } catch (Exception e) {
                    LogUtil.log("【CS】[toast]" + e.toString());
                }
            }
        }
    }

    private void onImageReaderNewInstanceAfter(Object[] args, Object result) {
        if (camera2Hook.shouldSkipImageReaderTracking()) {
            return;
        }
        if (!(result instanceof ImageReader) || args == null || args.length < 3) {
            return;
        }
        Surface surface = ((ImageReader) result).getSurface();
        if (surface == null) {
            return;
        }
        int width = (int) args[0];
        int height = (int) args[1];
        int format = (int) args[2];
        camera2Hook.registerImageReaderSurface(surface, format, width, height);
    }

    private void hookImageReaderListener(ClassLoader classLoader) {
        try {
            Method method = resolveMethod(classLoader, "android.media.ImageReader", "setOnImageAvailableListener",
                    android.media.ImageReader.OnImageAvailableListener.class, android.os.Handler.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                Object result = chain.proceed(args);
                try {
                    camera2Hook.updateImageReaderListener(chain.getThisObject(), args[0],
                            (android.os.Handler) args[1]);
                } catch (Throwable t) {
                    LogUtil.log("【CS】更新 YUV listener 失败: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook ImageReader.setOnImageAvailableListener 失败: " + t);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Method method = resolveMethod(classLoader, "android.media.ImageReader", "setOnImageAvailableListenerWithExecutor",
                        android.media.ImageReader.OnImageAvailableListener.class, Executor.class);
                Api101Runtime.requireModule().hook(method).intercept(chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    try {
                        camera2Hook.updateImageReaderListener(chain.getThisObject(), args[0], null);
                    } catch (Throwable t) {
                        LogUtil.log("【CS】更新 Executor YUV listener 失败: " + t);
                    }
                    return result;
                });
            } catch (Throwable t) {
                LogUtil.log("【CS】Hook ImageReader.setOnImageAvailableListenerWithExecutor 失败: " + t);
            }
        }
    }

    private void hookCaptureFailed(ClassLoader classLoader) {
        try {
            Method method = resolveMethod(CameraCaptureSession.CaptureCallback.class, "onCaptureFailed",
                    CameraCaptureSession.class, CaptureRequest.class, CaptureFailure.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args.length > 2 && args[2] instanceof CaptureFailure) {
                        LogUtil.log("【CS】onCaptureFailed原因：" + ((CaptureFailure) args[2]).getReason());
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】onCaptureFailed before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook CaptureCallback.onCaptureFailed 失败: " + t);
        }
    }

    private static Object invokeOrigin(XposedInterface.Invoker<?, Method> originInvoker, Object thisObject,
            Object[] args) throws Throwable {
        try {
            return originInvoker.invoke(thisObject, args);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target != null) {
                throw target;
            }
            throw e;
        }
    }

    private static Method resolveMethod(ClassLoader classLoader, String className,
            String methodName, Class<?>... parameterTypes) throws Exception {
        return HookUtils.resolveMethod(classLoader, className, methodName, parameterTypes);
    }

    private static Method resolveMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return HookUtils.resolveMethod(clazz, methodName, parameterTypes);
    }

    private static Object[] toArgs(List<Object> args) {
        return HookUtils.toArgs(args);
    }
}
