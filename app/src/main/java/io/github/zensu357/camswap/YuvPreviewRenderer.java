package io.github.zensu357.camswap;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Surface;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Renders YUV frames from MediaCodecYuvDecoder to a preview Surface at 30fps.
 * Uses hardware-decoded YUV (same as call path) instead of MediaPlayer (which
 * may fall back to software for AV1, giving only 4fps).
 */
public class YuvPreviewRenderer {
    private static final String TAG = "CS-YuvPreview";
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 240;
    private static final long FRAME_INTERVAL_NS = 33_333_333L; // 30fps

    private final Surface targetSurface;
    private final MediaCodecYuvDecoder yuvDecoder;
    private volatile boolean running = false;
    private Thread renderThread;
    private final Bitmap bitmap;
    private final int[] rgbPixels;

    public YuvPreviewRenderer(Surface surface, MediaCodecYuvDecoder decoder) {
        this.targetSurface = surface;
        this.yuvDecoder = decoder;
        this.bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        this.rgbPixels = new int[PREVIEW_WIDTH * PREVIEW_HEIGHT];
    }

    public void start() {
        if (running) return;
        running = true;
        renderThread = new Thread(this::renderLoop, "YuvPreviewRenderer");
        renderThread.setDaemon(true);
        renderThread.start();
        LogUtil.log(TAG + " started for surface: " + targetSurface);
    }

    public void stop() {
        running = false;
        if (renderThread != null) {
            try {
                renderThread.join(500);
            } catch (InterruptedException ignored) {
            }
            renderThread = null;
        }
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        LogUtil.log(TAG + " stopped");
    }

    private void renderLoop() {
        long nextFrameTime = System.nanoTime();
        while (running) {
            try {
                MediaCodecYuvDecoder.YuvFrame frame = yuvDecoder.acquireLatestFrame();
                if (frame != null && frame.yPlane != null) {
                    convertYuvToRgb(frame);
                    drawToSurface();
                }
                // Pace to 30fps
                nextFrameTime += FRAME_INTERVAL_NS;
                long sleepNs = nextFrameTime - System.nanoTime();
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                } else {
                    nextFrameTime = System.nanoTime();
                }
            } catch (Throwable t) {
                LogUtil.e(TAG + " render error: " + t.getMessage(), t);
                try {
                    Thread.sleep(33);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * Convert YUV420 planar to RGB, downsampling to preview size.
     * Uses integer arithmetic for speed.
     */
    private void convertYuvToRgb(MediaCodecYuvDecoder.YuvFrame frame) {
        int srcW = frame.width;
        int srcH = frame.height;
        byte[] yPlane = frame.yPlane;
        byte[] uPlane = frame.uPlane;
        byte[] vPlane = frame.vPlane;

        // Downsampling factors
        float xRatio = (float) srcW / PREVIEW_WIDTH;
        float yRatio = (float) srcH / PREVIEW_HEIGHT;

        int uvWidth = srcW / 2;

        for (int dy = 0; dy < PREVIEW_HEIGHT; dy++) {
            int sy = (int) (dy * yRatio);
            int uvY = sy / 2;
            for (int dx = 0; dx < PREVIEW_WIDTH; dx++) {
                int sx = (int) (dx * xRatio);
                int uvX = sx / 2;

                int yIndex = sy * srcW + sx;
                int uvIndex = uvY * uvWidth + uvX;

                if (yIndex >= yPlane.length || uvIndex >= uPlane.length || uvIndex >= vPlane.length) {
                    continue;
                }

                int y = yPlane[yIndex] & 0xFF;
                int u = uPlane[uvIndex] & 0xFF;
                int v = vPlane[uvIndex] & 0xFF;

                // YUV to RGB conversion (BT.601)
                int c = y - 16;
                int d = u - 128;
                int e = v - 128;

                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;

                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);

                rgbPixels[dy * PREVIEW_WIDTH + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(rgbPixels, 0, PREVIEW_WIDTH, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT);
    }

    private void drawToSurface() {
        if (targetSurface == null || !targetSurface.isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = targetSurface.lockCanvas(null);
            if (canvas != null) {
                Rect src = new Rect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT);
                Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                canvas.drawBitmap(bitmap, src, dst, null);
            }
        } catch (Throwable t) {
            // Surface may be invalid, ignore
        } finally {
            if (canvas != null) {
                try {
                    targetSurface.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
