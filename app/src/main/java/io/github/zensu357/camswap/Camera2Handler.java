package io.github.zensu357.camswap;

import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.HookUtils;
import io.github.zensu357.camswap.utils.VideoManager;
import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Camera2 Hook 处理器 —— 已原生化为 API 101 interceptor chain 风格。
 * <p>
 * 不再依赖 {@code api101.compat.*} 兼容层，直接使用
 * {@code Api101Runtime.requireModule().hook(method).intercept(chain -> ...)}。
 */
public class Camera2Handler implements ICameraHandler {

    @Override
    public void init(final Api101PackageContext packageContext) {
        final ClassLoader classLoader = packageContext.classLoader;
        final String packageName = packageContext.packageName;
        hookOpenCamera3Arg(classLoader, packageName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hookOpenCameraExecutor(classLoader, packageName);
        }
        hookAddTarget(classLoader, packageName);
        hookRemoveTarget(classLoader, packageName);
        hookBuild(classLoader, packageName);

        // Pre-install session hooks on CameraDeviceImpl (framework internal class).
        // This ensures createCaptureSession interception works even if
        // the onOpened hook on obfuscated StateCallback classes doesn't fire
        // (due to ART optimization preventing hook on app-level classes).
        try {
            Class<?> deviceImplClass = Class.forName(
                    "android.hardware.camera2.impl.CameraDeviceImpl", false, classLoader);
            HookMain.camera2Hook.hookAllCreateSessionVariants(deviceImplClass);
            LogUtil.log("【CS】已在 CameraDeviceImpl 上预装 session hooks");
        } catch (Throwable t) {
            LogUtil.log("【CS】预装 session hooks 失败: " + t);
        }
    }

    // ================================================================
    // 1. CameraManager.openCamera(String, StateCallback, Handler)
    //    before-only: 记录 state callback 类 + 触发初始化
    // ================================================================
    private void hookOpenCamera3Arg(ClassLoader classLoader, String packageName) {
        try {
            Method method = resolveMethod(classLoader,
                    "android.hardware.camera2.CameraManager",
                    "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args[1] != null) {
                        HookMain.c2_state_cb = (CameraDevice.StateCallback) args[1];
                        HookMain.c2_state_callback = args[1].getClass();
                        File file = HookGuards.resolveVideoFile(true);
                        if (!HookGuards.shouldBypass(packageName, file)) {
                            LogUtil.log("【CS】1位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                            HookMain.camera2Hook.isFirstHookBuild = true;
                            HookMain.process_camera2_init(HookMain.c2_state_callback);
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】openCamera(3-arg) before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook openCamera(3-arg) 失败: " + t);
        }
    }

    // ================================================================
    // 2. CameraManager.openCamera(String, Executor, StateCallback)
    //    before-only: 记录 state callback 类 (API 28+)
    // ================================================================
    private void hookOpenCameraExecutor(ClassLoader classLoader, String packageName) {
        try {
            Method method = resolveMethod(classLoader,
                    "android.hardware.camera2.CameraManager",
                    "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args[2] != null) {
                        HookMain.c2_state_cb = (CameraDevice.StateCallback) args[2];
                        HookMain.c2_state_callback = args[2].getClass();
                        File file = HookGuards.resolveVideoFile(true);
                        if (!HookGuards.shouldBypass(packageName, file)) {
                            LogUtil.log("【CS】2位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                            HookMain.camera2Hook.isFirstHookBuild = true;
                            HookMain.process_camera2_init(HookMain.c2_state_callback);
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】openCamera(executor) before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook openCamera(executor) 失败: " + t);
        }
    }

    // ================================================================
    // 3. CaptureRequest.Builder.addTarget(Surface)
    //    before-only: surface 替换逻辑
    // ================================================================
    private void hookAddTarget(ClassLoader classLoader, String packageName) {
        try {
            Method method = resolveMethod(classLoader,
                    "android.hardware.camera2.CaptureRequest$Builder",
                    "addTarget", Surface.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args[0] == null || chain.getThisObject() == null) {
                        return chain.proceed(args);
                    }
                    Surface targetSurface = (Surface) args[0];
                    if (HookMain.camera2Hook.isVirtualSurface(targetSurface)) {
                        return chain.proceed(args);
                    }
                    if (HookGuards.shouldBypass(packageName, HookGuards.getCurrentVideoFile())) {
                        LogUtil.log("【CS】【addTarget】【旁路】HookGuards 判定旁路包名/文件: " + packageName);
                        return chain.proceed(args);
                    }
                    if (HookMain.camera2Hook.isCurrentSessionBypassed()) {
                        LogUtil.log("【CS】【addTarget】当前会话已旁路，保留原始目标: " + targetSurface);
                        return chain.proceed(args);
                    }

                    // Dynamic defense for Photo Fake
                    if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)
                            && HookMain.camera2Hook.isTrackedReaderSurface(targetSurface)) {
                        LogUtil.log("【CS】【addTarget】检测到 ImageReader Surface: " + targetSurface);
                        if (HookMain.camera2Hook.isJpegReaderSurface(targetSurface)) {
                            HookMain.camera2Hook.markPendingJpegCapture(targetSurface);
                            LogUtil.log("【CS】【addTarget】保留 JPEG ImageReader 目标用于拍照: " + targetSurface);
                            return chain.proceed(args);
                        }
                    }

                    boolean isSurfaceTexture = HookMain.camera2Hook.isSurfaceTextureSurface(targetSurface);
                    boolean isJpeg = HookMain.camera2Hook.isJpegReaderSurface(targetSurface);
                    boolean isYuv = HookMain.camera2Hook.isYuvReaderSurface(targetSurface);

                    if (isSurfaceTexture || (!isJpeg && !HookMain.camera2Hook.isTrackedReaderSurface(targetSurface))) {
                        HookMain.camera2Hook.rememberPreviewSurface(targetSurface);
                        Surface vSurface = HookMain.camera2Hook.getVirtualSurfaceFor(targetSurface);
                        if (vSurface == null || !vSurface.isValid()) {
                            vSurface = HookMain.camera2Hook.getVirtualSurface();
                        }
                        LogUtil.log("【CS】【addTarget】重定向主预览目标 -> 原始Surface: " + targetSurface
                                + " -> 虚拟Surface: " + vSurface);
                        args[0] = vSurface;
                    } else if (isYuv) {
                        HookMain.camera2Hook.pumpDirectYuvFrame(targetSurface);
                        Surface vSurface = HookMain.camera2Hook.getVirtualSurfaceFor(targetSurface);
                        if (vSurface == null || !vSurface.isValid()) {
                            vSurface = HookMain.camera2Hook.getVirtualSurface();
                        }
                        LogUtil.log("【CS】【addTarget】重定向 YUV Reader 目标 -> 原始Surface: " + targetSurface
                                + " -> 虚拟Surface: " + vSurface);
                        args[0] = vSurface;
                    } else {
                        LogUtil.log("【CS】【addTarget】保留真实辅助流/JPEG目标: " + targetSurface);
                    }
                } catch (Throwable t) {
                    LogUtil.e("【CS】【addTarget】执行异常: " + t.getMessage(), t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.e("【CS】Hook addTarget 失败: " + t.getMessage(), t);
        }
    }

    // ================================================================
    // 4. CaptureRequest.Builder.removeTarget(Surface)
    //    before-only: 清理 surface 引用
    // ================================================================
    private void hookRemoveTarget(ClassLoader classLoader, String packageName) {
        try {
            Method method = resolveMethod(classLoader,
                    "android.hardware.camera2.CaptureRequest$Builder",
                    "removeTarget", Surface.class);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    if (args[0] != null && chain.getThisObject() != null
                            && !HookGuards.shouldBypass(packageName, HookGuards.getCurrentVideoFile())
                            && !HookMain.camera2Hook.isCurrentSessionBypassed()) {
                        LogUtil.log("【CS】【removeTarget】移除目标Surface: " + args[0]);
                        HookMain.camera2Hook.onTargetRemoved((Surface) args[0]);
                    }
                } catch (Throwable t) {
                    LogUtil.e("【CS】【removeTarget】执行异常: " + t.getMessage(), t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.e("【CS】Hook removeTarget 失败: " + t.getMessage(), t);
        }
    }

    // ================================================================
    // 5. CaptureRequest.Builder.build()
    //    before-only: 触发播放
    // ================================================================
    private void hookBuild(ClassLoader classLoader, String packageName) {
        try {
            Method method = resolveMethod(classLoader,
                    "android.hardware.camera2.CaptureRequest$Builder",
                    "build");
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                try {
                    Object thisObject = chain.getThisObject();
                    boolean isNewBuilder = thisObject != null
                            && !thisObject.equals(HookMain.camera2Hook.captureBuilder);
                    boolean hasPending = HookMain.camera2Hook.pendingPlayback;
                    if (thisObject != null && (isNewBuilder || hasPending)) {
                        HookMain.camera2Hook.captureBuilder = (CaptureRequest.Builder) thisObject;
                        if (!HookGuards.shouldBypass(packageName, HookGuards.getCurrentVideoFile())) {
                            if (HookMain.camera2Hook.isCurrentSessionBypassed()) {
                                LogUtil.log("【CS】【build】当前会话已旁路，跳过虚拟播放启动");
                            } else {
                                LogUtil.log("【CS】【build】开始构建捕获请求"
                                        + (hasPending ? " (延迟重试触发播放)" : " (触发播放)"));
                                if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)
                                        && HookMain.camera2Hook.pendingPhotoSurface != null
                                        && HookMain.camera2Hook.isJpegReaderSurface(
                                                HookMain.camera2Hook.pendingPhotoSurface)) {
                                    LogUtil.log("【CS】【build】已标记等待 JPEG acquire 替换: "
                                            + HookMain.camera2Hook.pendingPhotoSurface);
                                }
                                HookMain.process_camera2_play();
                            }
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.e("【CS】【build】执行异常: " + t.getMessage(), t);
                }
                return chain.proceed(toArgs(chain.getArgs()));
            });
        } catch (Throwable t) {
            LogUtil.e("【CS】Hook build 失败: " + t.getMessage(), t);
        }
    }

    // ================================================================
    // Utilities
    // ================================================================

    private static Method resolveMethod(ClassLoader classLoader, String className,
            String methodName, Class<?>... parameterTypes) throws Exception {
        return HookUtils.resolveMethod(classLoader, className, methodName, parameterTypes);
    }

    private static Object[] toArgs(List<Object> args) {
        return HookUtils.toArgs(args);
    }
}
