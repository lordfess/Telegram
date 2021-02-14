package org.telegram.messenger.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Bitmaps;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RequiresApi(21)
public class Camera2Controller implements CameraController, MediaRecorder.OnInfoListener {
    protected CameraManager cameraManager = null;
    private CameraCaptureSession previewSession;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession currentSession;
    private CaptureRequest.Builder currentBuilder;

    private HandlerThread cameraThread;
    private Handler cameraThreadHandler = null;

    private Camera2Session cameraSession;
    protected ArrayList<Integer> availableFlashModes = new ArrayList<>();
    protected volatile ArrayList<CameraInfo> cameraInfos;
    private CameraController.VideoTakeCallback onVideoTakeCallback;
    private CameraSession.PreviewCallback oneShotCallback = null;
    private final ArrayList<Runnable> onFinishCameraInitRunnables = new ArrayList<>();

    private MediaRecorder recorder;
    private ImageReader imageReader;
    private Surface surface;
    private SurfaceTexture texture;
    private Surface persistentSurface;

    private String recordedFile;
    private boolean mirrorRecorderVideo;
    private boolean cameraInitied;
    private boolean loadingCameras;
    private boolean isRoundCamera;

    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    protected Camera2Controller() {

    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("camera-thread");
        cameraThread.start();
        cameraThreadHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        cameraThread.quitSafely();
        try {
            cameraThread.join();
            cameraThread = null;
            cameraThreadHandler = null;
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
    }

    @Override
    public void cancelOnInitRunnable(final Runnable onInitRunnable) {
        onFinishCameraInitRunnables.remove(onInitRunnable);
    }

    @Override
    public void initCamera(final Runnable onInitRunnable) {
        initCamera(onInitRunnable, false);
    }

    private void initCamera(final Runnable onInitRunnable, boolean withDelay) {
        if (cameraInitied) {
            return;
        }
        if (onInitRunnable != null && !onFinishCameraInitRunnables.contains(onInitRunnable)) {
            onFinishCameraInitRunnables.add(onInitRunnable);
        }
        if (loadingCameras || cameraInitied) {
            return;
        }
        loadingCameras = true;

        startCameraThread();
        cameraThreadHandler.post(() -> {
            try {
                cameraManager = (CameraManager) ApplicationLoader.applicationContext.getSystemService(Context.CAMERA_SERVICE);
                if (cameraInfos == null) {
                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    String cache = preferences.getString("cameraCache", null);
                    Comparator<Size> comparator = (o1, o2) -> {
                        if (o1.mWidth < o2.mWidth) {
                            return 1;
                        } else if (o1.mWidth > o2.mWidth) {
                            return -1;
                        } else {
                            if (o1.mHeight < o2.mHeight) {
                                return 1;
                            } else if (o1.mHeight > o2.mHeight) {
                                return -1;
                            }
                            return 0;
                        }
                    };
                    ArrayList<CameraInfo> result = new ArrayList<>();
                    boolean cacheValid = false;
                    if (cache != null) {
                        SerializedData serializedData = new SerializedData(Base64.decode(cache, Base64.DEFAULT));
                        int cached_api = serializedData.readInt32(false);
                        if (cached_api == 2) {
                            int count = serializedData.readInt32(false);
                            for (int a = 0; a < count; a++) {
                                Camera2Info camera2Info = new Camera2Info(serializedData.readString(false), serializedData.readInt32(false));
                                int pCount = serializedData.readInt32(false);
                                for (int b = 0; b < pCount; b++) {
                                    camera2Info.previewSizes.add(new Size(serializedData.readInt32(false), serializedData.readInt32(false)));
                                }
                                pCount = serializedData.readInt32(false);
                                for (int b = 0; b < pCount; b++) {
                                    camera2Info.pictureSizes.add(new Size(serializedData.readInt32(false), serializedData.readInt32(false)));
                                }
                                result.add(camera2Info);

                                Collections.sort(camera2Info.previewSizes, comparator);
                                Collections.sort(camera2Info.pictureSizes, comparator);
                            }
                            cacheValid = true;
                        }
                        serializedData.cleanup();
                    }
                    if (!cacheValid) {
                        String[] cameras = cameraManager.getCameraIdList();
                        int bufferSize = 4;
                        for (String camera : cameras) {
                            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(camera);
                            Camera2Info cameraInfo = new Camera2Info(camera, cc.get(CameraCharacteristics.LENS_FACING));

                            StreamConfigurationMap streamConfigurationMap = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                            android.util.Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
                            for (android.util.Size size : sizes) {
                                if (size.getWidth() == 1280 && size.getHeight() != 720) {
                                    continue;
                                }
                                if (size.getHeight() < 2160 && size.getWidth() < 2160) {
                                    cameraInfo.previewSizes.add(new Size(size.getWidth(), size.getHeight()));
                                    if (BuildVars.LOGS_ENABLED) {
                                        FileLog.d("preview size = " + size.getWidth() + " " + size.getHeight());
                                    }
                                }
                            }

                            sizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
                            for (android.util.Size size : sizes) {
                                if (size.getWidth() == 1280 && size.getHeight() != 720) {
                                    continue;
                                }
                                if (!"samsung".equals(Build.MANUFACTURER) || !"jflteuc".equals(Build.PRODUCT) || size.getHeight() < 2048) {
                                    cameraInfo.pictureSizes.add(new Size(size.getWidth(), size.getHeight()));
                                    if (BuildVars.LOGS_ENABLED) {
                                        FileLog.d("picture size = " + size.getWidth() + " " + size.getHeight());
                                    }
                                }
                            }

                            result.add(cameraInfo);

                            Collections.sort(cameraInfo.previewSizes, comparator);
                            Collections.sort(cameraInfo.pictureSizes, comparator);

                            bufferSize += 4 + 4 + 8 * (cameraInfo.previewSizes.size() + cameraInfo.pictureSizes.size());
                        }

                        SerializedData serializedData = new SerializedData(bufferSize);
                        serializedData.writeInt32(2);
                        serializedData.writeInt32(result.size());
                        for (int a = 0; a < cameras.length; a++) {
                            Camera2Info cameraInfo = (Camera2Info) result.get(a);
                            serializedData.writeString(cameraInfo.cameraId);
                            serializedData.writeInt32(cameraInfo.frontCamera);

                            int pCount = cameraInfo.previewSizes.size();
                            serializedData.writeInt32(pCount);
                            for (int b = 0; b < pCount; b++) {
                                Size size = cameraInfo.previewSizes.get(b);
                                serializedData.writeInt32(size.mWidth);
                                serializedData.writeInt32(size.mHeight);
                            }
                            pCount = cameraInfo.pictureSizes.size();
                            serializedData.writeInt32(pCount);
                            for (int b = 0; b < pCount; b++) {
                                Size size = cameraInfo.pictureSizes.get(b);
                                serializedData.writeInt32(size.mWidth);
                                serializedData.writeInt32(size.mHeight);
                            }
                        }
                        preferences.edit().putString("cameraCache", Base64.encodeToString(serializedData.toByteArray(), Base64.DEFAULT)).commit();
                        serializedData.cleanup();
                    }
                    cameraInfos = result;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    loadingCameras = false;
                    cameraInitied = true;
                    if (!onFinishCameraInitRunnables.isEmpty()) {
                        for (int a = 0; a < onFinishCameraInitRunnables.size(); a++) {
                            onFinishCameraInitRunnables.get(a).run();
                        }
                        onFinishCameraInitRunnables.clear();
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.cameraInitied);
                });
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    onFinishCameraInitRunnables.clear();
                    loadingCameras = false;
                    cameraInitied = false;
                    if (!withDelay && "APP_PAUSED".equals(e.getMessage())) {
                        AndroidUtilities.runOnUIThread(() -> initCamera(onInitRunnable, true), 1000);
                    }
                });
            }
        });
    }

    @Override
    public boolean isCameraInitied() {
        return cameraInitied && cameraInfos != null && !cameraInfos.isEmpty();
    }

    @Override
    public ArrayList<CameraInfo> getCameras() {
        return cameraInfos;
    }

    @Override
    @SuppressLint("MissingPermission")
    public void open(final CameraSession s, final SurfaceTexture texture, final Runnable callback, final Runnable prestartCallback) {
        if (s == null || texture == null) {
            return;
        }

        cameraSession = (Camera2Session) s;
        this.texture = texture;
        isRoundCamera = false;

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                return;
            }
            cameraManager.openCamera(cameraSession.cameraInfo.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    cameraSession.cameraInfo.camera = camera;
                    availableFlashModes.clear();
                    try {
                        CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraSession.cameraInfo.cameraId);
                        if (cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                            int[] modes = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
                            for (int mode : modes) {
                                if (mode == CameraMetadata.CONTROL_AE_MODE_ON || mode == CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH || mode == CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH) {
                                    availableFlashModes.add(cameraSession.mapToSessionFlashMode(mode));
                                }
                            }
                        }
                        if (prestartCallback != null) {
                            prestartCallback.run();
                        }
                        surface = new Surface(texture);
                        startPreview(cameraSession);
                        if (callback != null) {
                            AndroidUtilities.runOnUIThread(callback);
                        }
                    } catch (CameraAccessException e) {
                        FileLog.e(e);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    stopPreview(cameraSession);
                    cameraSession.cameraInfo.camera = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraSession.cameraInfo.camera = null;
                }
            }, cameraThreadHandler);
        } catch (Exception e) {
            cameraSession.cameraInfo.camera = null;
            FileLog.e(e);
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void openRound(final CameraSession s, final SurfaceTexture texture, final Runnable callback, final Runnable configureCallback) {
        if (s == null || texture == null) {
            return;
        }

        cameraSession = (Camera2Session) s;
        this.texture = texture;
        isRoundCamera = true;

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                return;
            }
            cameraManager.openCamera(cameraSession.cameraInfo.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    if (configureCallback != null) {
                        configureCallback.run();
                    }
                    surface = new Surface(texture);
                    cameraSession.cameraInfo.camera = camera;
                    startPreview(cameraSession);
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(callback);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    stopPreview(cameraSession);
                    cameraSession.cameraInfo.camera = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraSession.cameraInfo.camera = null;
                }
            }, cameraThreadHandler);
        } catch (Exception e) {
            cameraSession.cameraInfo.camera = null;
            FileLog.e(e);
        }
    }

    protected CameraCharacteristics getCameraCharacteristics() {
        if (cameraManager != null) {
            try {
                return cameraManager.getCameraCharacteristics(cameraSession.cameraInfo.cameraId);
            } catch (CameraAccessException e) {
                FileLog.e(e);
            }
        }
        return null;
    }

    protected CaptureRequest.Builder getCurrentCaptureRequestBuilder() {
        return currentBuilder;
    }

    protected void applyConfig() {
        if (currentSession != null) {
            try {
                currentSession.stopRepeating();
                currentSession.setRepeatingRequest(currentBuilder.build(), null, cameraThreadHandler);
            } catch (CameraAccessException e) {
                FileLog.e(e);
            }
        }
    }

    @Override
    public void startPreview(final CameraSession s) {
        if (s == null || previewSession != null || texture == null) {
            return;
        }
        Camera2Session session = (Camera2Session) s;

        try {
            texture.setDefaultBufferSize(cameraSession.previewSize.getWidth(), cameraSession.previewSize.getHeight());
            session.cameraInfo.camera.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            try {
                                previewSession = s;
                                CaptureRequest.Builder previewRequestBuilder = s.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                previewRequestBuilder.addTarget(surface);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                previewSession.setRepeatingRequest(previewRequestBuilder.build(), null, cameraThreadHandler);

                                currentSession = previewSession;
                                currentBuilder = previewRequestBuilder;
                            } catch (CameraAccessException e) {
                                FileLog.e(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {

                        }

                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            previewSession = null;
                        }
                    }, cameraThreadHandler);
        } catch (CameraAccessException e) {
            FileLog.e(e);
        }
    }

    @Override
    public void stopPreview(final CameraSession session) {
        if (session == null || previewSession == null) {
            return;
        }
        previewSession.close();
        previewSession = null;
    }

    private enum CaptureState {
        PREVIEW,
        WAIT_AF,
        WAIT_AE,
        WAIT_SHOOT,
        SHOT
    }

    private CaptureState captureState = CaptureState.PREVIEW;

    private final CameraCaptureSession.CaptureCallback captureCallback
            = new CameraCaptureSession.CaptureCallback() {
        private void processResult(CaptureResult result) {
            switch (captureState) {
                case PREVIEW: {
                    break;
                }
                case WAIT_AF: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            captureState = CaptureState.SHOT;
                            captureStillPicture();
                        } else {
                            startPrecapture();
                        }
                    }
                    break;
                }
                case WAIT_AE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED) {
                        captureState = CaptureState.WAIT_SHOOT;
                    }
                    break;
                }
                case WAIT_SHOOT: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        captureState = CaptureState.SHOT;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            processResult(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            processResult(result);
        }
    };

    private void lockFocus() {
        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureState = CaptureState.WAIT_AF;
            captureSession.capture(captureRequestBuilder.build(), captureCallback,
                    cameraThreadHandler);
        } catch (CameraAccessException e) {
            FileLog.e(e);
        }
    }

    private void startPrecapture() {
        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            captureState = CaptureState.WAIT_AE;
            captureSession.capture(captureRequestBuilder.build(), captureCallback,
                    cameraThreadHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unlockFocus() {
        captureSession.close();
        startPreview(cameraSession);
    }

    private void captureStillPicture() {
        try {
            currentSession = null;
            CaptureRequest.Builder captureBuilder =
                    cameraSession.cameraInfo.camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            cameraSession.configurePhotoCamera(captureBuilder);
            captureBuilder.addTarget(imageReader.getSurface());

            captureSession.stopRepeating();
            captureSession.abortCaptures();
            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            }, cameraThreadHandler);
        } catch (CameraAccessException e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean takePicture(final File path, final CameraSession s, final Runnable callback) {
        if (s == null) {
            return false;
        }
        Camera2Session session = (Camera2Session) s;

        imageReader = ImageReader.newInstance(session.pictureSize.getWidth(), session.pictureSize.getHeight(), session.pictureFormat, 1);
        imageReader.setOnImageAvailableListener(reader -> {
            byte[] data = null;
            try {
                Image image = reader.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                data = new byte[buffer.remaining()];
                buffer.get(data);
                image.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (oneShotCallback != null) {
                oneShotCallback.onPreviewFrame(data);
                oneShotCallback = null;
                return;
            }
            Bitmap bitmap = null;
            int size = (int) (AndroidUtilities.getPhotoSize() / AndroidUtilities.density);
            String key = String.format(Locale.US, "%s@%d_%d", Utilities.MD5(path.getAbsolutePath()), size, size);
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                float scaleFactor = Math.max((float) options.outWidth / AndroidUtilities.getPhotoSize(), (float) options.outHeight / AndroidUtilities.getPhotoSize());
                if (scaleFactor < 1) {
                    scaleFactor = 1;
                }
                options.inJustDecodeBounds = false;
                options.inSampleSize = (int) scaleFactor;
                options.inPurgeable = true;
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                if (session.cameraInfo.isFrontface() && session.isFlipFront()) {
                    try {
                        Matrix matrix = new Matrix();
                        int orient = getOrientation(data);
                        matrix.setRotate(getOrientation(data));
                        matrix.postScale(-1, 1);
                        Bitmap scaled = Bitmaps.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        if (scaled != bitmap) {
                            bitmap.recycle();
                        }
                        FileOutputStream outputStream = new FileOutputStream(path);
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream);
                        outputStream.flush();
                        outputStream.getFD().sync();
                        outputStream.close();
                        if (scaled != null) {
                            ImageLoader.getInstance().putImageToCache(new BitmapDrawable(scaled), key);
                        }
                        if (callback != null) {
                            AndroidUtilities.runOnUIThread(callback);
                        }
                        return;
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                FileOutputStream outputStream = new FileOutputStream(path);
                outputStream.write(data);
                outputStream.flush();
                outputStream.getFD().sync();
                outputStream.close();
                if (bitmap != null) {
                    ImageLoader.getInstance().putImageToCache(new BitmapDrawable(bitmap), key);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback);
            }
        }, cameraThreadHandler);

        try {
            texture.setDefaultBufferSize(session.previewSize.getWidth(), session.previewSize.getHeight());
            session.cameraInfo.camera.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            try {
                                captureSession = s;
                                captureRequestBuilder = s.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, session.mapToAPIFlashMode(session.getCurrentFlashMode()));
                                captureRequestBuilder.addTarget(surface);

                                currentSession = null;
                                currentBuilder = captureRequestBuilder;
                                session.configurePhotoCamera();

                                captureState = CaptureState.PREVIEW;
                                captureSession.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, cameraThreadHandler);
                                lockFocus();
                            } catch (CameraAccessException e) {
                                FileLog.e(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {

                        }

                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            captureSession = null;
                        }
                    }, cameraThreadHandler);
        } catch (CameraAccessException e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    protected void setOneShotPreviewCallback(CameraSession.PreviewCallback callback) {
        oneShotCallback = callback;
        takePicture(null, cameraSession, null);
    }

    @Override
    public void recordVideo(final CameraSession s, final File path, boolean mirror, final CameraController.VideoTakeCallback callback, final Runnable onVideoStartRecord) {
        if (s == null) {
            return;
        }
        Camera2Session session = (Camera2Session) s;

        try {
            mirrorRecorderVideo = mirror;
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);

            if (Build.VERSION.SDK_INT >= 23) {
                persistentSurface = MediaCodec.createPersistentInputSurface();
                recorder.setInputSurface(persistentSurface);
            }

            session.configureRecorder(1, recorder);
            recorder.setOutputFile(path.getAbsolutePath());
            recorder.setMaxFileSize(1024 * 1024 * 1024);
            recorder.setVideoFrameRate(30);
            recorder.setMaxDuration(0);
            Size pictureSize;
            pictureSize = new Size(16, 9);
            pictureSize = CameraController.chooseOptimalSize(session.cameraInfo.getPictureSizes(), 720, 480, pictureSize);
            int bitrate;
            if (Math.min(pictureSize.mHeight, pictureSize.mWidth) >= 720) {
                bitrate = 3500000;
            } else {
                bitrate = 1800000;
            }
            recorder.setVideoEncodingBitRate(bitrate);
            recorder.setVideoSize(pictureSize.getWidth(), pictureSize.getHeight());
            recorder.setOnInfoListener(Camera2Controller.this);
            recorder.prepare();

            final Surface recorderSurface = persistentSurface == null ? recorder.getSurface() : persistentSurface;
            texture.setDefaultBufferSize(session.previewSize.getWidth(), session.previewSize.getHeight());
            session.cameraInfo.camera.createCaptureSession(Arrays.asList(surface, recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            try {
                                captureSession = s;
                                captureRequestBuilder = s.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                captureRequestBuilder.addTarget(surface);
                                captureRequestBuilder.addTarget(recorderSurface);

                                currentSession = s;
                                currentBuilder = captureRequestBuilder;
                                if (isRoundCamera) {
                                    session.configureRoundCamera();
                                } else {
                                    if (session.getCurrentFlashMode() == CameraSession.FlashMode.FLASH_MODE_ON) {
                                        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                                    }
                                    captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                                            session.getZoomRect(cameraManager.getCameraCharacteristics(session.cameraInfo.cameraId)));
                                }

                                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, cameraThreadHandler);
                                recorder.start();

                                onVideoTakeCallback = callback;
                                recordedFile = path.getAbsolutePath();
                                if (onVideoStartRecord != null) {
                                    AndroidUtilities.runOnUIThread(onVideoStartRecord);
                                }
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {

                        }

                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            captureSession = null;
                        }
                    }, cameraThreadHandler);
        } catch (IOException | CameraAccessException e) {
            FileLog.e(e);
        }
    }

    private void finishRecordingVideo() {
        MediaMetadataRetriever mediaMetadataRetriever = null;
        long duration = 0;
        try {
            mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(recordedFile);
            String d = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                duration = (int) Math.ceil(Long.parseLong(d) / 1000.0f);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (mediaMetadataRetriever != null) {
                    mediaMetadataRetriever.release();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        Bitmap bitmap = SendMessagesHelper.createVideoThumbnail(recordedFile, MediaStore.Video.Thumbnails.MINI_KIND);
        if (mirrorRecorderVideo) {
            Bitmap b = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            canvas.scale(-1, 1, b.getWidth() / 2, b.getHeight() / 2);
            canvas.drawBitmap(bitmap, 0, 0, null);
            bitmap.recycle();
            bitmap = b;
        }
        String fileName = Integer.MIN_VALUE + "_" + SharedConfig.getLastLocalId() + ".jpg";
        final File cacheFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
        try {
            FileOutputStream stream = new FileOutputStream(cacheFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        SharedConfig.saveConfig();
        final long durationFinal = duration;
        final Bitmap bitmapFinal = bitmap;
        AndroidUtilities.runOnUIThread(() -> {
            if (onVideoTakeCallback != null) {
                String path = cacheFile.getAbsolutePath();
                if (bitmapFinal != null) {
                    ImageLoader.getInstance().putImageToCache(new BitmapDrawable(bitmapFinal), Utilities.MD5(path));
                }
                onVideoTakeCallback.onFinishVideoRecording(path, durationFinal);
                onVideoTakeCallback = null;
            }
        });
    }

    @Override
    public void onInfo(MediaRecorder mediaRecorder, int what, int extra) {
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
            MediaRecorder tempRecorder = recorder;
            recorder = null;
            if (tempRecorder != null) {
                tempRecorder.stop();
                tempRecorder.release();
            }
            if (onVideoTakeCallback != null) {
                finishRecordingVideo();
            }
        }
    }

    @Override
    public void stopVideoRecording(final CameraSession session, final boolean abandon) {
        if (session == null) {
            return;
        }
        MediaRecorder tempRecorder = recorder;
        recorder = null;
        try {
            tempRecorder.stop();
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            tempRecorder.release();
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (persistentSurface != null) {
            try {
                persistentSurface.release();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        startPreview(session);
        if (!abandon && onVideoTakeCallback != null) {
            finishRecordingVideo();
        } else {
            onVideoTakeCallback = null;
        }
    }

    @Override
    public void close(final CameraSession s, final CountDownLatch countDownLatch, final Runnable beforeDestroyRunnable) {
        Camera2Session session = (Camera2Session) s;
        session.destroy();
        cameraThreadHandler.post(() -> {
            if (beforeDestroyRunnable != null) {
                beforeDestroyRunnable.run();
            }
            try {
                cameraOpenCloseLock.acquire();
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (previewSession != null) {
                    previewSession.close();
                    previewSession = null;
                }
                if (session.cameraInfo.camera != null) {
                    session.cameraInfo.camera.close();
                    session.cameraInfo.camera = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
            } catch (InterruptedException e) {
                FileLog.e(e);
            } finally {
                cameraOpenCloseLock.release();
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        });
        if (countDownLatch != null) {
            try {
                countDownLatch.await();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static int getOrientation(byte[] jpeg) {
        if (jpeg == null) {
            return 0;
        }

        int offset = 0;
        int length = 0;

        while (offset + 3 < jpeg.length && (jpeg[offset++] & 0xFF) == 0xFF) {
            int marker = jpeg[offset] & 0xFF;

            if (marker == 0xFF) {
                continue;
            }
            offset++;

            if (marker == 0xD8 || marker == 0x01) {
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                break;
            }

            length = pack(jpeg, offset, 2, false);
            if (length < 2 || offset + length > jpeg.length) {
                return 0;
            }

            // Break if the marker is EXIF in APP1.
            if (marker == 0xE1 && length >= 8 &&
                    pack(jpeg, offset + 2, 4, false) == 0x45786966 &&
                    pack(jpeg, offset + 6, 2, false) == 0) {
                offset += 8;
                length -= 8;
                break;
            }

            offset += length;
            length = 0;
        }

        if (length > 8) {
            int tag = pack(jpeg, offset, 4, false);
            if (tag != 0x49492A00 && tag != 0x4D4D002A) {
                return 0;
            }
            boolean littleEndian = (tag == 0x49492A00);

            int count = pack(jpeg, offset + 4, 4, littleEndian) + 2;
            if (count < 10 || count > length) {
                return 0;
            }
            offset += count;
            length -= count;

            count = pack(jpeg, offset - 2, 2, littleEndian);
            while (count-- > 0 && length >= 12) {
                tag = pack(jpeg, offset, 2, littleEndian);
                if (tag == 0x0112) {
                    int orientation = pack(jpeg, offset + 8, 2, littleEndian);
                    switch (orientation) {
                        case 1:
                            return 0;
                        case 3:
                            return 180;
                        case 6:
                            return 90;
                        case 8:
                            return 270;
                    }
                    return 0;
                }
                offset += 12;
                length -= 12;
            }
        }
        return 0;
    }

    private static int pack(byte[] bytes, int offset, int length, boolean littleEndian) {
        int step = 1;
        if (littleEndian) {
            offset += length - 1;
            step = -1;
        }

        int value = 0;
        while (length-- > 0) {
            value = (value << 8) | (bytes[offset] & 0xFF);
            offset += step;
        }
        return value;
    }
}
