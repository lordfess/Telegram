package org.telegram.messenger.camera;

import android.graphics.SurfaceTexture;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public interface CameraController {
    interface VideoTakeCallback {
        void onFinishVideoRecording(String thumbPath, long duration);
    }

    void initCamera(Runnable onInitRunnable);

    boolean isCameraInitied();

    ArrayList<CameraInfo> getCameras();

    void open(CameraSession session, SurfaceTexture texture, Runnable callback, Runnable prestartCallback);

    void openRound(CameraSession session, SurfaceTexture texture, Runnable callback, Runnable configureCallback);

    void startPreview(CameraSession session);

    void stopPreview(CameraSession session);

    boolean takePicture(File path, CameraSession session, Runnable callback);

    void recordVideo(CameraSession session, File path, boolean mirror, CameraController.VideoTakeCallback callback, Runnable onVideoStartRecord);

    void stopVideoRecording(CameraSession session, boolean abandon);

    void cancelOnInitRunnable(Runnable onInitRunnable);

    void close(CameraSession session, CountDownLatch countDownLatch, Runnable beforeDestroyRunnable);

    static Size chooseOptimalSize(List<Size> choices, int width, int height, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (int a = 0; a < choices.size(); a++) {
            Size option = choices.get(a);
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(choices, new CompareSizesByArea());
        }
    }

    class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
