package com.qrscan.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import java.util.List;

/**
 * 相机控制（Camera1 API）。
 *
 * 之所以不用 CameraX：本项目走无依赖构建路线，Camera1 在所有 minSdk 23+ 设备上可用，
 * 并且能直接拿到 NV21 预览帧交给 ZXing，省掉一次 YUV->RGB 转换。
 */
@SuppressWarnings("deprecation")
public class CameraController implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final int DEFAULT_CAMERA_ID = 0;

    /** 目标预览尺寸：960x540 对二维码识别足够，帧缓冲比 1280x720 少 40%。 */
    private static final int TARGET_PREVIEW_WIDTH = 960;
    private static final int TARGET_PREVIEW_HEIGHT = 540;
    private static final String TAG = "QRScanCam";

    public interface FrameCallback {
        void onPreviewFrame(byte[] data, int width, int height, int frameRotation);
    }

    public interface ErrorCallback {
        void onCameraError(String message);
    }

    private final Context context;
    private SurfaceHolder holder;
    private Camera camera;
    private FrameCallback frameCallback;
    private ErrorCallback errorCallback;

    private int cameraId = DEFAULT_CAMERA_ID;
    private int previewWidth;
    private int previewHeight;
    private int frameRotation;
    private boolean wantOpen;
    private boolean flashOn;
    private int frameCount;

    public CameraController(Context context) {
        this.context = context;
    }

    public void setFrameCallback(FrameCallback cb) {
        this.frameCallback = cb;
    }

    public void setErrorCallback(ErrorCallback cb) {
        this.errorCallback = cb;
    }

    public void attach(SurfaceHolder h) {
        holder = h;
        holder.addCallback(this);
    }

    public int getPreviewWidth() {
        return previewWidth;
    }

    public int getPreviewHeight() {
        return previewHeight;
    }

    public boolean isFlashOn() {
        return flashOn;
    }

    public boolean hasFlash() {
        if (camera == null) {
            return false;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            return p.getFlashMode() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public int cameraCount() {
        try {
            return Camera.getNumberOfCameras();
        } catch (Throwable t) {
            return 0;
        }
    }

    public int getCameraId() {
        return cameraId;
    }

    /** 主动开始预览（权限已授予、界面可见时调用）。 */
    public void start() {
        wantOpen = true;
        tryOpen();
    }

    public void stop() {
        wantOpen = false;
        release();
    }

    public void switchCamera() {
        int count = cameraCount();
        if (count < 2) {
            return;
        }
        boolean wasOpen = camera != null;
        int next = (cameraId + 1) % count;
        release();
        cameraId = next;
        flashOn = false;
        if (wasOpen) {
            wantOpen = true;
            tryOpen();
        }
    }

    public boolean toggleFlash() {
        if (camera == null) {
            return false;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                return false;
            }
            flashOn = !flashOn;
            p.setFlashMode(flashOn ? Camera.Parameters.FLASH_MODE_TORCH
                                   : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            return flashOn;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部打开逻辑
    // ------------------------------------------------------------------

    private void tryOpen() {
        if (!wantOpen || camera != null) {
            return;
        }
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            return;
        }
        Camera cam = null;
        try {
            cam = Camera.open(cameraId);
        } catch (Throwable t) {
            cam = null;
        }
        if (cam == null) {
            if (errorCallback != null) {
                errorCallback.onCameraError("Camera.open failed");
            }
            return;
        }

        try {
            Camera.Parameters p = cam.getParameters();
            p.setPreviewFormat(ImageFormat.NV21);

            Camera.Size chosen = choosePreviewSize(p.getSupportedPreviewSizes());
            if (chosen != null) {
                p.setPreviewSize(chosen.width, chosen.height);
            }

            List<String> focus = p.getSupportedFocusModes();
            if (focus != null) {
                if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else if (focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }

            List<String> banding = p.getSupportedAntibanding();
            if (banding != null && banding.contains("50hz")) {
                p.setAntibanding("50hz");
            }

            try {
                cam.setParameters(p);
            } catch (Throwable t) {
                Log.w(TAG, "setParameters failed: " + t);
            }

            // 回读实际生效的参数，避免被 HAL 静默改掉后尺寸对不上
            Camera.Parameters actual = cam.getParameters();
            Camera.Size actualSize = actual.getPreviewSize();
            if (actualSize != null) {
                previewWidth = actualSize.width;
                previewHeight = actualSize.height;
            }
            Log.i(TAG, "preview size requested=" + (chosen == null ? "null"
                    : chosen.width + "x" + chosen.height)
                    + " actual=" + previewWidth + "x" + previewHeight
                    + " format=" + actual.getPreviewFormat()
                    + " focus=" + actual.getFocusMode());

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            frameRotation = info.orientation;
            int displayOrientation = calcDisplayOrientation(info);
            Log.i(TAG, "cameraId=" + cameraId + " facing=" + info.facing
                    + " sensorOrientation=" + info.orientation
                    + " displayOrientation=" + displayOrientation);

            cam.setDisplayOrientation(displayOrientation);
            cam.setPreviewDisplay(holder);

            int bufSize = previewWidth * previewHeight
                    * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            Log.i(TAG, "callback buffer size=" + bufSize);
            cam.addCallbackBuffer(new byte[bufSize]);
            cam.addCallbackBuffer(new byte[bufSize]);
            cam.setPreviewCallbackWithBuffer(this);

            cam.startPreview();
            camera = cam;
            Log.i(TAG, "startPreview OK, surface=" + holder.getSurface().isValid());
            applyFlashToNewCamera();
        } catch (Throwable t) {
            Log.e(TAG, "open/preview failed: " + t, t);
            try {
                cam.release();
            } catch (Throwable ignored) {
            }
            if (errorCallback != null) {
                errorCallback.onCameraError("preview failed: " + t.getClass().getSimpleName());
            }
        }
    }

    private void applyFlashToNewCamera() {
        if (!flashOn || camera == null) {
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(p);
        } catch (Throwable ignored) {
        }
    }

    private int calcDisplayOrientation(Camera.CameraInfo info) {
        int rotation = 0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            rotation = wm.getDefaultDisplay().getRotation();
        } catch (Throwable ignored) {
        }
        int degrees;
        switch (rotation) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                degrees = 0;
                break;
        }
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - ((info.orientation + degrees) % 360)) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        // 目标 960x540：二维码识别够用，帧缓冲比 720p 少 40%，解码也更快
        for (Camera.Size s : sizes) {
            if (s.width == TARGET_PREVIEW_WIDTH && s.height == TARGET_PREVIEW_HEIGHT) {
                return s;
            }
        }
        Camera.Size best = null;
        long bestScore = Long.MAX_VALUE;
        long targetArea = (long) TARGET_PREVIEW_WIDTH * TARGET_PREVIEW_HEIGHT;
        for (Camera.Size s : sizes) {
            double ar = (double) s.width / (double) s.height;
            if (ar < 1.25 || ar > 2.2) {
                continue; // 跳过畸变的预览尺寸
            }
            long area = (long) s.width * (long) s.height;
            long score = Math.abs(area - targetArea);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best != null) {
            return best;
        }
        for (Camera.Size s : sizes) {
            if (best == null || (long) s.width * s.height > (long) best.width * best.height) {
                best = s;
            }
        }
        return best;
    }

    private void release() {
        Camera cam = camera;
        camera = null;
        if (cam == null) {
            return;
        }
        try {
            cam.setPreviewCallbackWithBuffer(null);
        } catch (Throwable ignored) {
        }
        try {
            cam.stopPreview();
        } catch (Throwable ignored) {
        }
        try {
            cam.release();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        tryOpen();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        if (camera == null) {
            tryOpen();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        release();
    }

    // ------------------------------------------------------------------
    // Camera.PreviewCallback
    // ------------------------------------------------------------------

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        FrameCallback cb = frameCallback;
        try {
            if (cb != null && data != null && previewWidth > 0) {
                cb.onPreviewFrame(data, previewWidth, previewHeight, frameRotation);
            }
        } catch (Throwable ignored) {
        }
        frameCount++;
        if (frameCount == 1 || frameCount % 60 == 0) {
            Log.i(TAG, "frames=" + frameCount + " len=" + (data == null ? -1 : data.length));
        }
        try {
            cam.addCallbackBuffer(data);
        } catch (Throwable ignored) {
        }
    }
}
