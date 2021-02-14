/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

public class Camera1Session implements CameraSession{
    protected Camera1Info cameraInfo;
    private int currentFlashMode;
    private OrientationEventListener orientationEventListener;
    private int lastOrientation = -1;
    private int lastDisplayOrientation = -1;
    private boolean isVideo;
    private final Size pictureSize;
    private final Size previewSize;
    private final int pictureFormat;
    private boolean initied;
    private int maxZoom;
    private boolean meteringAreaSupported;
    private int currentOrientation;
    private int diffOrientation;
    private int jpegOrientation;
    private boolean sameTakePictureOrientation;
    private boolean flipFront = true;
    private float currentZoom;
    private boolean optimizeForBarcode;

    protected final static int FLASH_MODE_TORCH = 4;

    public static final int ORIENTATION_HYSTERESIS = 5;

    private Camera.AutoFocusCallback autoFocusCallback = (success, camera) -> {
        if (success) {

        } else {

        }
    };

    public Camera1Session(CameraInfo info, Size preview, Size picture, int format) {
        previewSize = preview;
        pictureSize = picture;
        pictureFormat = format;
        cameraInfo = (Camera1Info) info;

        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        currentFlashMode = sharedPreferences.getInt(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", CameraSession.FlashMode.FLASH_MODE_OFF);

        orientationEventListener = new OrientationEventListener(ApplicationLoader.applicationContext) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientationEventListener == null || !initied || orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
                jpegOrientation = roundOrientation(orientation, jpegOrientation);
                WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
                int rotation = mgr.getDefaultDisplay().getRotation();
                if (lastOrientation != jpegOrientation || rotation != lastDisplayOrientation) {
                    if (!isVideo) {
                        configurePhotoCamera();
                    }
                    lastDisplayOrientation = rotation;
                    lastOrientation = jpegOrientation;
                }
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        } else {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
    }

    private int roundOrientation(int orientation, int orientationHistory) {
        boolean changeOrientation;
        if (orientationHistory == OrientationEventListener.ORIENTATION_UNKNOWN) {
            changeOrientation = true;
        } else {
            int dist = Math.abs(orientation - orientationHistory);
            dist = Math.min(dist, 360 - dist);
            changeOrientation = (dist >= 45 + ORIENTATION_HYSTERESIS);
        }
        if (changeOrientation) {
            return ((orientation + 45) / 90 * 90) % 360;
        }
        return orientationHistory;
    }

    @Override
    public void setOptimizeForBarcode(boolean value) {
        optimizeForBarcode = value;
        configurePhotoCamera();
    }

    protected String mapToAPIFlashMode(int mode) {
        switch (mode) {
            case CameraSession.FlashMode.FLASH_MODE_OFF: return Camera.Parameters.FLASH_MODE_OFF;
            case CameraSession.FlashMode.FLASH_MODE_ON: return Camera.Parameters.FLASH_MODE_ON;
            case CameraSession.FlashMode.FLASH_MODE_AUTO: return Camera.Parameters.FLASH_MODE_AUTO;
            case FLASH_MODE_TORCH: return Camera.Parameters.FLASH_MODE_TORCH;
        }
        return Camera.Parameters.FLASH_MODE_OFF;
    }

    protected int mapToSessionFlashMode(String mode) {
        if (mode.contentEquals(Camera.Parameters.FLASH_MODE_OFF)) return CameraSession.FlashMode.FLASH_MODE_OFF;
        if (mode.contentEquals(Camera.Parameters.FLASH_MODE_ON)) return CameraSession.FlashMode.FLASH_MODE_ON;
        if (mode.contentEquals(Camera.Parameters.FLASH_MODE_AUTO)) return CameraSession.FlashMode.FLASH_MODE_AUTO;
        return CameraSession.FlashMode.FLASH_MODE_OFF;
    }

    @Override
    public void checkFlashMode(int mode) {
        Camera1Controller controller = (Camera1Controller)CameraModule.getInstance().getCameraController();
        ArrayList<Integer> modes = controller.availableFlashModes;
        if (modes.contains(currentFlashMode)) {
            return;
        }
        currentFlashMode = mode;
        configurePhotoCamera();
        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        sharedPreferences.edit().putInt(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", mode).commit();
    }

    @Override
    public void setCurrentFlashMode(int mode) {
        currentFlashMode = mode;
        configurePhotoCamera();
        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        sharedPreferences.edit().putInt(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", mode).commit();
    }

    @Override
    public void setTorchEnabled(boolean enabled) {
        try {
            currentFlashMode = enabled ? FLASH_MODE_TORCH : CameraSession.FlashMode.FLASH_MODE_OFF;
            configurePhotoCamera();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public int getCurrentFlashMode() {
        return currentFlashMode;
    }

    @Override
    public int getNextFlashMode() {
        Camera1Controller controller = (Camera1Controller)CameraModule.getInstance().getCameraController();
        ArrayList<Integer> modes = controller.availableFlashModes;
        for (int a = 0; a < modes.size(); a++) {
            int mode = modes.get(a);
            if (mode == currentFlashMode) {
                if (a < modes.size() - 1) {
                    return modes.get(a + 1);
                } else {
                    return modes.get(0);
                }
            }
        }
        return currentFlashMode;
    }

    @Override
    public void setInitied() {
        initied = true;
    }

    @Override
    public boolean isInitied() {
        return initied;
    }

    @Override
    public int getCurrentOrientation() {
        return currentOrientation;
    }

    @Override
    public boolean isFlipFront() {
        return flipFront;
    }

    @Override
    public void setFlipFront(boolean value) {
        flipFront = value;
    }

    @Override
    public int getWorldAngle() {
        return diffOrientation;
    }

    @Override
    public boolean isSameTakePictureOrientation() {
        return sameTakePictureOrientation;
    }

    protected void configureRoundCamera() {
        try {
            isVideo = true;
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.Parameters params = null;
                try {
                    params = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                Camera.getCameraInfo(cameraInfo.getCameraId(), info);

                int displayOrientation = getDisplayOrientation(info, true);
                int cameraDisplayOrientation;

                if ("samsung".equals(Build.MANUFACTURER) && "sf2wifixx".equals(Build.PRODUCT)) {
                    cameraDisplayOrientation = 0;
                } else {
                    int degrees = 0;
                    int temp = displayOrientation;
                    switch (temp) {
                        case Surface.ROTATION_0:
                            degrees = 0;
                            break;
                        case Surface.ROTATION_90:
                            degrees = 90;
                            break;
                        case Surface.ROTATION_180:
                            degrees = 180;
                            break;
                        case Surface.ROTATION_270:
                            degrees = 270;
                            break;
                    }
                    if (info.orientation % 90 != 0) {
                        info.orientation = 0;
                    }
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        temp = (info.orientation + degrees) % 360;
                        temp = (360 - temp) % 360;
                    } else {
                        temp = (info.orientation - degrees + 360) % 360;
                    }
                    cameraDisplayOrientation = temp;
                }
                camera.setDisplayOrientation(currentOrientation = cameraDisplayOrientation);
                diffOrientation = currentOrientation - displayOrientation;

                if (params != null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("set preview size = " + previewSize.getWidth() + " " + previewSize.getHeight());
                    }
                    params.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("set picture size = " + pictureSize.getWidth() + " " + pictureSize.getHeight());
                    }
                    params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
                    params.setPictureFormat(pictureFormat);
                    params.setRecordingHint(true);

                    String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                    if (params.getSupportedFocusModes().contains(desiredMode)) {
                        params.setFocusMode(desiredMode);
                    } else {
                        desiredMode = Camera.Parameters.FOCUS_MODE_AUTO;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    }

                    int outputOrientation = 0;
                    if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
                        } else {
                            outputOrientation = (info.orientation + jpegOrientation) % 360;
                        }
                    }
                    try {
                        params.setRotation(outputOrientation);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
                        } else {
                            sameTakePictureOrientation = displayOrientation == outputOrientation;
                        }
                    } catch (Exception e) {
                        //
                    }
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    try {
                        camera.setParameters(params);
                    } catch (Exception e) {
                        //
                    }

                    if (params.getMaxNumMeteringAreas() > 0) {
                        meteringAreaSupported = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    protected void configurePhotoCamera() {
        try {
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.Parameters params = null;
                try {
                    params = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                Camera.getCameraInfo(cameraInfo.getCameraId(), info);

                int displayOrientation = getDisplayOrientation(info, true);
                int cameraDisplayOrientation;

                if ("samsung".equals(Build.MANUFACTURER) && "sf2wifixx".equals(Build.PRODUCT)) {
                    cameraDisplayOrientation = 0;
                } else {
                    int degrees = 0;
                    int temp = displayOrientation;
                    switch (temp) {
                        case Surface.ROTATION_0:
                            degrees = 0;
                            break;
                        case Surface.ROTATION_90:
                            degrees = 90;
                            break;
                        case Surface.ROTATION_180:
                            degrees = 180;
                            break;
                        case Surface.ROTATION_270:
                            degrees = 270;
                            break;
                    }
                    if (info.orientation % 90 != 0) {
                        info.orientation = 0;
                    }
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        temp = (info.orientation + degrees) % 360;
                        temp = (360 - temp) % 360;
                    } else {
                        temp = (info.orientation - degrees + 360) % 360;
                    }
                    cameraDisplayOrientation = temp;
                }
                camera.setDisplayOrientation(currentOrientation = cameraDisplayOrientation);

                if (params != null) {
                    params.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
                    params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
                    params.setPictureFormat(pictureFormat);
                    params.setJpegQuality(100);
                    params.setJpegThumbnailQuality(100);
                    maxZoom = params.getMaxZoom();
                    params.setZoom((int) (currentZoom * maxZoom));

                    if (optimizeForBarcode) {
                        List<String> modes = params.getSupportedSceneModes();
                        if (modes != null && modes.contains(Camera.Parameters.SCENE_MODE_BARCODE)) {
                            params.setSceneMode(Camera.Parameters.SCENE_MODE_BARCODE);
                        }
                        String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    } else {
                        String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    }

                    int outputOrientation = 0;
                    if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
                        } else {
                            outputOrientation = (info.orientation + jpegOrientation) % 360;
                        }
                    }
                    try {
                        params.setRotation(outputOrientation);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
                        } else {
                            sameTakePictureOrientation = displayOrientation == outputOrientation;
                        }
                    } catch (Exception e) {
                        //
                    }
                    params.setFlashMode(mapToAPIFlashMode(currentFlashMode));
                    try {
                        camera.setParameters(params);
                    } catch (Exception e) {
                        //
                    }

                    if (params.getMaxNumMeteringAreas() > 0) {
                        meteringAreaSupported = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void focusToRect(Rect focusRect, Rect meteringRect) {
        try {
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                camera.cancelAutoFocus();
                Camera.Parameters parameters = null;
                try {
                    parameters = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                if (parameters != null) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    ArrayList<Camera.Area> meteringAreas = new ArrayList<>();
                    meteringAreas.add(new Camera.Area(focusRect, 1000));
                    parameters.setFocusAreas(meteringAreas);

                    if (meteringAreaSupported) {
                        meteringAreas = new ArrayList<>();
                        meteringAreas.add(new Camera.Area(meteringRect, 1000));
                        parameters.setMeteringAreas(meteringAreas);
                    }

                    try {
                        camera.setParameters(parameters);
                        camera.autoFocus(autoFocusCallback);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public int getMaxZoom() {
        return maxZoom;
    }

    @Override
    public void setZoom(float value) {
        currentZoom = value;
        configurePhotoCamera();
    }

    protected void configureRecorder(int quality, MediaRecorder recorder) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraInfo.cameraId, info);
        int displayOrientation = getDisplayOrientation(info, false);


        int outputOrientation = 0;
        if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
            } else {
                outputOrientation = (info.orientation + jpegOrientation) % 360;
            }
        }
        recorder.setOrientationHint(outputOrientation);

        int highProfile = getHigh();
        boolean canGoHigh = CamcorderProfile.hasProfile(cameraInfo.cameraId, highProfile);
        boolean canGoLow = CamcorderProfile.hasProfile(cameraInfo.cameraId, CamcorderProfile.QUALITY_LOW);
        if (canGoHigh && (quality == 1 || !canGoLow)) {
            recorder.setProfile(CamcorderProfile.get(cameraInfo.cameraId, highProfile));
        } else if (canGoLow) {
            recorder.setProfile(CamcorderProfile.get(cameraInfo.cameraId, CamcorderProfile.QUALITY_LOW));
        } else {
            throw new IllegalStateException("cannot find valid CamcorderProfile");
        }
        isVideo = true;
    }

    protected void stopVideoRecording() {
        isVideo = false;
        configurePhotoCamera();
    }

    private int getHigh() {
        if ("LGE".equals(Build.MANUFACTURER) && "g3_tmo_us".equals(Build.PRODUCT)) {
            return CamcorderProfile.QUALITY_480P;
        }
        return CamcorderProfile.QUALITY_HIGH;
    }

    private int getDisplayOrientation(Camera.CameraInfo info, boolean isStillCapture) {
        WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        int rotation = mgr.getDefaultDisplay().getRotation();
        int degrees = 0;

        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int displayOrientation;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (info.orientation + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;

            if (!isStillCapture && displayOrientation == 90) {
                displayOrientation = 270;
            }
            if (!isStillCapture && "Huawei".equals(Build.MANUFACTURER) && "angler".equals(Build.PRODUCT) && displayOrientation == 270) {
                displayOrientation = 90;
            }
        } else {
            displayOrientation = (info.orientation - degrees + 360) % 360;
        }

        return displayOrientation;
    }

    @Override
    public int getDisplayOrientation() {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraInfo.getCameraId(), info);
            return getDisplayOrientation(info, true);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public void setPreviewCallback(Camera.PreviewCallback callback){
        cameraInfo.camera.setPreviewCallback(callback);
    }

    @Override
    public void setOneShotPreviewCallback(CameraSession.PreviewCallback callback) {
        if (cameraInfo != null && cameraInfo.camera != null) {
            try {
                cameraInfo.camera.setOneShotPreviewCallback((data, camera) -> {
                    callback.onPreviewFrame(data);
                });
            } catch (Exception ignore) {

            }
        }
    }

    @Override
    public void destroy() {
        initied = false;
        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
    }
}
