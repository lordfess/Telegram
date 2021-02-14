package org.telegram.messenger.camera;

import java.util.ArrayList;

public interface CameraInfo {
    ArrayList<Size> getPreviewSizes();

    ArrayList<Size> getPictureSizes();

    boolean isFrontface();
}
