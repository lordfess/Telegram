package org.telegram.messenger.camera;

import android.hardware.camera2.CameraDevice;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;

@RequiresApi(21)
public class Camera2Info implements CameraInfo {
    protected final String cameraId;
    protected final int frontCamera;
    protected CameraDevice camera;
    protected ArrayList<Size> pictureSizes = new ArrayList<>();
    protected ArrayList<Size> previewSizes = new ArrayList<>();

    protected Camera2Info(String id, int frontFace) {
        cameraId = id;
        frontCamera = frontFace;
    }

    protected String getCameraId() {
        return cameraId;
    }

    @Override
    public ArrayList<Size> getPreviewSizes() {
        return previewSizes;
    }

    @Override
    public ArrayList<Size> getPictureSizes() {
        return pictureSizes;
    }

    @Override
    public boolean isFrontface() {
        return frontCamera == 0;
    }
}
