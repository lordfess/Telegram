package org.telegram.messenger.camera;

import android.os.Build;

public class CameraModule {
    private static volatile CameraModule INSTANCE = null;
    private final CameraController cameraController;
    private final boolean forceUseAPI1 = false;

    public static CameraModule getInstance() {
        CameraModule localInstance = INSTANCE;
        if (localInstance == null) {
            synchronized (CameraModule.class) {
                localInstance = INSTANCE;
                if (localInstance == null) {
                    INSTANCE = localInstance = new CameraModule();
                }
            }
        }
        return localInstance;
    }

    private CameraModule() {
        if (Build.VERSION.SDK_INT >= 21 && !forceUseAPI1) {
            cameraController = new Camera2Controller();
        } else {
            cameraController = new Camera1Controller();
        }
    }

    public CameraController getCameraController() {
        return cameraController;
    }

    public CameraSession createCameraSession(CameraInfo info, Size preview, Size picture, int format) {
        if (Build.VERSION.SDK_INT >= 21 && !forceUseAPI1) {
            return new Camera2Session(info, preview, picture, format);
        } else {
            return new Camera1Session(info, preview, picture, format);
        }
    }
}
