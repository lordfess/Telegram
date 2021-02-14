/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.camera;

import android.hardware.Camera;

import java.util.ArrayList;

public class Camera1Info implements CameraInfo {
    protected final int cameraId;
    protected final int frontCamera;
    protected Camera camera;
    protected ArrayList<Size> pictureSizes = new ArrayList<>();
    protected ArrayList<Size> previewSizes = new ArrayList<>();

    public Camera1Info(int id, int frontFace) {
        cameraId = id;
        frontCamera = frontFace;
    }

    protected int getCameraId() {
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
        return frontCamera != 0;
    }
}
