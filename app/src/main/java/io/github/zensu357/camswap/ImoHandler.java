package io.github.zensu357.camswap;

import android.hardware.Camera;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.HookUtils;
import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
* IMO (com.imo.android.imoim) dedicated handler.
*
* Why: IMO's video call uses its proprietary "macaw" AV engine
* (com.imo.android.imoim.av.macaw.VideoCapturer / VideoCapturer2).
* The generic Camera1Handler frame replacement is unreliable for IMO's
* call path (shared decoder state, buffer recycling, timing), while
* IMO's QR scanner (plain camera path) works fine.
*
* Strategy: wrap IMO's PreviewCallback with our own delegating callback.
* Our wrapper overwrites every preview byte[] with fake NV21 frames pumped
* by a DEDICATED VideoToFrames decoder sized to IMO's preview size, then
* delegates to IMO's original callback so its pipeline keeps working.
*/
public class ImoHandler implements ICameraHandler {

private static final String IMO_PACKAGE = "com.imo.android.imoim";
private static final String TAG = "";

// Dedicated frame pump for IMO (independent from the shared HookMain.data_buffer).
private static volatile VideoToFrames imoDecoder;
private static final LinkedBlockingQueue<byte[]> imoFrameQueue = new LinkedBlockingQueue<>(4);
private static volatile byte[] lastFakeFrame;
private static volatile int imoWidth;
private static volatile int imoHeight;
private static volatile long frameCount;
private static volatile long overwriteCount;

public static boolean isImoPackage(String packageName) {
if (packageName == null || packageName.isEmpty()) {
return false;
}
String normalized = packageName.toLowerCase(Locale.ROOT);
return normalized.equals(IMO_PACKAGE) || normalized.startsWith(IMO_PACKAGE + ":");
}

@Override
public void init(final Api101PackageContext packageContext) {
if (!isImoPackage(packageContext.hostPackageName)) {
return;
}
final ClassLoader classLoader = packageContext.classLoader;
LogUtil.log(TAG + "IMO detected, installing dedicated call-path hooks...");

hookVideoCapturerConstructors(classLoader);
hookSetPreviewCallbackWithBuffer(classLoader);
}

/**
* Diagnostic: log which macaw capturer IMO instantiates (Camera1 vs Camera2 path).
*/
private void hookVideoCapturerConstructors(ClassLoader classLoader) {
String[] capturers = new String[] {
"com.imo.android.imoim.av.macaw.VideoCapturer",
"com.imo.android.imoim.av.macaw.VideoCapturer2"
};
for (String className: capturers) {
try {
final Class<?> clazz = Class.forName(className, false, classLoader);
for (final java.lang.reflect.Constructor<?> ctor: clazz.getDeclaredConstructors()) {
ctor.setAccessible(true);
Api101Runtime.requireModule().hook(ctor).intercept(chain -> {
Object result = chain.proceed(HookUtils.toArgs(chain.getArgs()));
LogUtil.log(TAG + "macaw capturer instantiated: " + className);
return result;
});
}
LogUtil.log(TAG + "hooked capturer ctors: " + className);
} catch (Throwable t) {
LogUtil.log(TAG + "capturer not found (ok if other path active): " + className);
}
}
}

/**
* Wrap IMO's PreviewCallback when it is registered from the macaw VideoCapturer.
*/
private void hookSetPreviewCallbackWithBuffer(ClassLoader classLoader) {
try {
Method method = HookUtils.resolveMethod(classLoader, "android.hardware.Camera",
"setPreviewCallbackWithBuffer", Camera.PreviewCallback.class);
Api101Runtime.requireModule().hook(method).intercept(chain -> {
Object[] args = HookUtils.toArgs(chain.getArgs());
try {
if (args.length > 0 && args[0] instanceof Camera.PreviewCallback
&& isCalledFromMacawCapturer()) {
Camera.PreviewCallback original = (Camera.PreviewCallback) args[0];
Camera camera = (Camera) chain.getThisObject();
LogUtil.log(TAG + "wrapping macaw PreviewCallback: "
+ original.getClass().getName());
args[0] = new ImoFrameCallback(original, camera);
}
} catch (Throwable t) {
LogUtil.log(TAG + "wrap failed: " + t.getMessage());
}
return chain.proceed(args);
});
LogUtil.log(TAG + "hooked Camera.setPreviewCallbackWithBuffer");
} catch (Throwable t) {
LogUtil.log(TAG + "hook setPreviewCallbackWithBuffer failed: " + t);
}
}

private static boolean isCalledFromMacawCapturer() {
try {
StackTraceElement[] stack = Thread.currentThread().getStackTrace();
for (StackTraceElement el: stack) {
String cn = el.getClassName();
if (cn!= null && cn.startsWith("com.imo.android.imoim.av.macaw.")) {
return true;
}
}
} catch (Throwable ignored) {
}
return false;
}

/**
* Delegating callback: overwrite frame with fake NV21, then call IMO's original.
*/
private static class ImoFrameCallback implements Camera.PreviewCallback {
private final Camera.PreviewCallback original;
private final Camera camera;

ImoFrameCallback(Camera.PreviewCallback original, Camera camera) {
this.original = original;
this.camera = camera;
}

@Override
public void onPreviewFrame(byte[] data, Camera cam) {
try {
ensureDecoder(cam, data!= null? data.length: 0);
byte[] fake = pollLatestFake();
if (fake!= null && data!= null) {
System.arraycopy(fake, 0, data, 0, Math.min(fake.length, data.length));
overwriteCount++;
if ((overwriteCount % 150) == 1) {
LogUtil.log(TAG + "overwriting IMO call frames: " + overwriteCount
+ " frames, size=" + data.length + " (" + imoWidth + "x" + imoHeight + ")");
}
}
frameCount++;
} catch (Throwable t) {
LogUtil.log(TAG + "onPreviewFrame error: " + t.getMessage());
}
try {
original.onPreviewFrame(data, cam);
} catch (Throwable t) {
LogUtil.log(TAG + "delegating to IMO callback failed: " + t.getMessage());
}
}
}

private static synchronized void ensureDecoder(Camera camera, int bufferLen) {
try {
int w = 0, h = 0;
try {
Camera.Parameters params = camera.getParameters();
Camera.Size size = params!= null? params.getPreviewSize(): null;
if (size!= null) {
w = size.width;
h = size.height;
}
} catch (Throwable ignored) {
}
if (w <= 0 || h <= 0) {
// Fall back to NV21 size derived from the buffer length (assume 4:3-ish).
if (bufferLen > 0) {
int pixels = (bufferLen * 2) / 3;
w = 640;
h = pixels / 640;
} else {
w = 640;
h = 480;
}
}
if (imoDecoder == null || imoWidth!= w || imoHeight!= h) {
imoWidth = w;
imoHeight = h;
LogUtil.log(TAG + "starting dedicated frame pump: " + w + "x" + h);
if (imoDecoder!= null) {
try {
imoDecoder.stopDecode();
} catch (Throwable ignored) {
}
}
imoFrameQueue.clear();
lastFakeFrame = null;
VideoToFrames decoder = new VideoToFrames();
decoder.setTargetSize(w, h);
decoder.setEnqueue(imoFrameQueue);
// NOTE: do NOT call setSaveFrames -> keeps HookMain.data_buffer untouched.
String path = VideoManager.getCurrentVideoPath();
LogUtil.log(TAG + "decoding video: " + path);
decoder.decode(path);
imoDecoder = decoder;
}
} catch (Throwable t) {
LogUtil.log(TAG + "ensureDecoder failed: " + t);
}
}

private static byte[] pollLatestFake() {
try {
byte[] latest = null;
byte[] f;
while ((f = imoFrameQueue.poll())!= null) {
latest = f;
}
if (latest!= null) {
lastFakeFrame = latest;
return latest;
}
return lastFakeFrame;
} catch (Throwable ignored) {
return lastFakeFrame;
}
}

/** Called from HookMain when the module is being torn down / video switched. */
public static void resetPump() {
try {
if (imoDecoder!= null) {
imoDecoder.stopDecode();
}
} catch (Throwable ignored) {
}
imoDecoder = null;
imoFrameQueue.clear();
lastFakeFrame = null;
frameCount = 0;
overwriteCount = 0;
}
}
