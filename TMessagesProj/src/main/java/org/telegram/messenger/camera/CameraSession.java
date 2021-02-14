package org.telegram.messenger.camera;

import android.graphics.Rect;

public interface CameraSession {
    class FlashMode {
        public final static int FLASH_MODE_OFF = 0;
        public final static int FLASH_MODE_ON = 1;
        public final static int FLASH_MODE_AUTO = 2;
    }

    void setOptimizeForBarcode(boolean value);

    void checkFlashMode(int mode);

    void setCurrentFlashMode(int mode);

    int getCurrentFlashMode();

    int getNextFlashMode();

    void setTorchEnabled(boolean enabled);

    void setInitied();

    boolean isInitied();

    int getCurrentOrientation();

    boolean isFlipFront();

    void setFlipFront(boolean value);

    int getWorldAngle();

    boolean isSameTakePictureOrientation();

    int getMaxZoom();

    void setZoom(float value);

    void focusToRect(Rect focusRect, Rect meteringRect);

    int getDisplayOrientation();

    void setOneShotPreviewCallback(PreviewCallback callback);

    void destroy();

    interface PreviewCallback {
        void onPreviewFrame(byte[] data);
    }
}
