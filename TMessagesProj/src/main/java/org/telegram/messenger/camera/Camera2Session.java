package org.telegram.messenger.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;


import androidx.annotation.RequiresApi;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

@RequiresApi(21)
public class Camera2Session implements CameraSession {
    protected Camera2Info cameraInfo;
    private int currentFlashMode;
    protected boolean torchEnabled = false;
    private OrientationEventListener orientationEventListener;
    private int lastOrientation = -1;
    private int lastDisplayOrientation = -1;
    private boolean isVideo;
    protected final Size pictureSize;
    protected final Size previewSize;
    protected final int pictureFormat;
    private boolean initied;
    private int maxZoom;
    private boolean afAreaControlSupported;
    private boolean aeAreaControlSupported;
    private int currentOrientation;
    private int diffOrientation;
    private int jpegOrientation;
    private boolean sameTakePictureOrientation;
    private boolean flipFront = true;
    private float currentZoom;
    private boolean optimizeForBarcode;
    public static final int ORIENTATION_HYSTERESIS = 5;

    public Camera2Session(CameraInfo info, Size preview, Size picture, int format) {
        previewSize = preview;
        pictureSize = picture;
        pictureFormat = format;
        cameraInfo = (Camera2Info)info;

        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        currentFlashMode = sharedPreferences.getInt(cameraInfo.isFrontface() ? "flashMode_front" : "flashMode", CameraSession.FlashMode.FLASH_MODE_OFF);

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

    protected int mapToAPIFlashMode(int mode) {
        switch (mode) {
            case CameraSession.FlashMode.FLASH_MODE_OFF: return CameraMetadata.CONTROL_AE_MODE_ON;
            case CameraSession.FlashMode.FLASH_MODE_ON: return CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            case CameraSession.FlashMode.FLASH_MODE_AUTO: return CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
        }
        return CameraMetadata.CONTROL_AE_MODE_ON;
    }

    protected int mapToSessionFlashMode(int mode) {
        switch (mode) {
            case CameraMetadata.CONTROL_AE_MODE_ON: return CameraSession.FlashMode.FLASH_MODE_OFF;
            case CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH: return CameraSession.FlashMode.FLASH_MODE_ON;
            case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH: return CameraSession.FlashMode.FLASH_MODE_AUTO;
        }
        return CameraSession.FlashMode.FLASH_MODE_OFF;
    }

    @Override
    public void checkFlashMode(int mode) {
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        ArrayList<Integer> modes = cameraController.availableFlashModes;
        if (modes.contains(currentFlashMode)) {
            return;
        }
        currentFlashMode = mode;
        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        sharedPreferences.edit().putInt(cameraInfo.isFrontface() ? "flashMode_front" : "flashMode", mode).commit();
    }

    @Override
    public void setCurrentFlashMode(int mode) {
        currentFlashMode = mode;
        configurePhotoCamera();
        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        sharedPreferences.edit().putInt(cameraInfo.isFrontface() ? "flashMode_front" : "flashMode", mode).commit();
    }

    @Override
    public void setTorchEnabled(boolean enabled) {
        torchEnabled = enabled;
        configurePhotoCamera();
    }

    @Override
    public int getCurrentFlashMode() {
        return currentFlashMode;
    }

    @Override
    public int getNextFlashMode() {
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        ArrayList<Integer> modes = cameraController.availableFlashModes;
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
        isVideo = true;
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        CameraCharacteristics cc = cameraController.getCameraCharacteristics();
        CaptureRequest.Builder builder = cameraController.getCurrentCaptureRequestBuilder();
        if (builder == null) {
            return;
        }

        int outputOrientation = 0;
        int displayOrientation = getDisplayOrientation(cc, true);
        int sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
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
            if (sensorOrientation % 90 != 0) {
                sensorOrientation = 0;
            }
            if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
                temp = (sensorOrientation + degrees) % 360;
                temp = (360 - temp) % 360;
            } else {
                temp = (sensorOrientation - degrees + 360) % 360;
            }
            cameraDisplayOrientation = temp;
        }
        currentOrientation = cameraDisplayOrientation;
        diffOrientation = currentOrientation - displayOrientation;

        if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
                outputOrientation = (sensorOrientation - jpegOrientation + 360) % 360;
            } else {
                outputOrientation = (sensorOrientation + jpegOrientation) % 360;
            }
        }
        builder.set(CaptureRequest.JPEG_ORIENTATION, outputOrientation);
        if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
        } else {
            sameTakePictureOrientation = displayOrientation == outputOrientation;
        }

        if (cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0) {
            afAreaControlSupported = true;
        }

        if (cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0) {
            aeAreaControlSupported = true;
        }

        cameraController.applyConfig();
    }

    protected Rect getZoomRect(CameraCharacteristics cc) {
        Float maxZoom = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        Rect activeRect = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        float zoom =  (maxZoom - 1) * currentZoom + 1;
        int diffHorizontal = (int)(activeRect.width() - activeRect.width() / zoom) / 2;
        int diffVertical = (int)(activeRect.height() - activeRect.height() / zoom) / 2;
        return new Rect(diffHorizontal, diffVertical, activeRect.right - diffHorizontal, activeRect.bottom - diffVertical);
    }

    protected void configurePhotoCamera() {
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        configurePhotoCamera(cameraController.getCurrentCaptureRequestBuilder());
    }

    protected void configurePhotoCamera(CaptureRequest.Builder builder) {
        if (builder == null) {
            return;
        }

        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        CameraCharacteristics cc = cameraController.getCameraCharacteristics();

        // TODO: For API >= 30 use CaptureRequest.CONTROL_ZOOM_RATIO and check CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
        builder.set(CaptureRequest.SCALER_CROP_REGION, getZoomRect(cc));
        if (torchEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, mapToAPIFlashMode(currentFlashMode));
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        }

        if (cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0) {
            afAreaControlSupported = true;
        }

        if (cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0) {
            aeAreaControlSupported = true;
        }

        builder.set(CaptureRequest.JPEG_QUALITY, (byte)100);
        builder.set(CaptureRequest.JPEG_THUMBNAIL_QUALITY, (byte)100);
        if (optimizeForBarcode) {
            int[] modes = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
            for (int mode : modes) {
                if (mode == CameraMetadata.CONTROL_SCENE_MODE_BARCODE) {
                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, mode);
                }
            }
        }

        int outputOrientation = 0;
        int cameraDisplayOrientation;
        int displayOrientation = getDisplayOrientation(cc, true);
        int sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);

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
            if (sensorOrientation % 90 != 0) {
                sensorOrientation = 0;
            }
            if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
                temp = (sensorOrientation + degrees) % 360;
                temp = (360 - temp) % 360;
            } else {
                temp = (sensorOrientation - degrees + 360) % 360;
            }
            cameraDisplayOrientation = temp;
        }
        currentOrientation = cameraDisplayOrientation;

        if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
                outputOrientation = (sensorOrientation - jpegOrientation + 360) % 360;
            } else {
                outputOrientation = (sensorOrientation + jpegOrientation) % 360;
            }
        }
        builder.set(CaptureRequest.JPEG_ORIENTATION, outputOrientation);
        if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
        } else {
            sameTakePictureOrientation = displayOrientation == outputOrientation;
        }

        cameraController.applyConfig();
    }

    private Rect convertToMeteringRect(Rect rect) {
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        CameraCharacteristics cc = cameraController.getCameraCharacteristics();
        Rect activeRect = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        int left = (int)((rect.left + 1000) / 2000. * activeRect.width());
        int top = (int)((rect.top + 1000) / 2000. * activeRect.height());
        int right = (int)((rect.right + 1000) / 2000. * activeRect.width());
        int bottom = (int)((rect.bottom + 1000) / 2000. * activeRect.height());
        return new Rect(left, top, right, bottom);
    }

    @Override
    public void focusToRect(Rect focusRect, Rect meteringRect) {
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        try {
            CaptureRequest.Builder builder = cameraController.getCurrentCaptureRequestBuilder();

            if (afAreaControlSupported) {
                MeteringRectangle rect = new MeteringRectangle(convertToMeteringRect(focusRect), 1000);
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{rect});
            }

            if (aeAreaControlSupported) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
                MeteringRectangle rect = new MeteringRectangle(convertToMeteringRect(meteringRect), 1000);
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{rect});
            }

            cameraController.applyConfig();
        } catch (Exception e) {
            e.printStackTrace();
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
        try {
            Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
            CameraCharacteristics cc = cameraController.cameraManager.getCameraCharacteristics(cameraInfo.getCameraId());
            int outputOrientation = 0;
            if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
                    outputOrientation = (cc.get(CameraCharacteristics.SENSOR_ORIENTATION) - jpegOrientation + 360) % 360;
                } else {
                    outputOrientation = (cc.get(CameraCharacteristics.SENSOR_ORIENTATION) + jpegOrientation) % 360;
                }
            }
            recorder.setOrientationHint(outputOrientation);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        int highProfile = getHigh();
        boolean canGoHigh = CamcorderProfile.hasProfile(Integer.parseInt(cameraInfo.cameraId), highProfile);
        boolean canGoLow = CamcorderProfile.hasProfile(Integer.parseInt(cameraInfo.cameraId), CamcorderProfile.QUALITY_LOW);
        if (canGoHigh && (quality == 1 || !canGoLow)) {
            recorder.setProfile(CamcorderProfile.get(Integer.parseInt(cameraInfo.cameraId), highProfile));
        } else if (canGoLow) {
            recorder.setProfile(CamcorderProfile.get(Integer.parseInt(cameraInfo.cameraId), CamcorderProfile.QUALITY_LOW));
        } else {
            throw new IllegalStateException("cannot find valid CamcorderProfile");
        }
        isVideo = true;
    }

    private int getHigh() {
        if ("LGE".equals(Build.MANUFACTURER) && "g3_tmo_us".equals(Build.PRODUCT)) {
            return CamcorderProfile.QUALITY_480P;
        }
        return CamcorderProfile.QUALITY_HIGH;
    }

    private int getDisplayOrientation(CameraCharacteristics cc, boolean isStillCapture) {
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
        int sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);

        if (cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
            displayOrientation = (sensorOrientation + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;

            if (!isStillCapture && displayOrientation == 90) {
                displayOrientation = 270;
            }
            if (!isStillCapture && "Huawei".equals(Build.MANUFACTURER) && "angler".equals(Build.PRODUCT) && displayOrientation == 270) {
                displayOrientation = 90;
            }
        } else {
            displayOrientation = (sensorOrientation - degrees + 360) % 360;
        }

        return displayOrientation;
    }

    @Override
    public int getDisplayOrientation() {
        try {
            Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
            CameraCharacteristics cc = cameraController.cameraManager.getCameraCharacteristics(cameraInfo.getCameraId());
            return getDisplayOrientation(cc, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    @Override
    public void setOneShotPreviewCallback(CameraSession.PreviewCallback callback) {
        Camera2Controller cameraController = (Camera2Controller)CameraModule.getInstance().getCameraController();
        cameraController.setOneShotPreviewCallback(callback);
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
