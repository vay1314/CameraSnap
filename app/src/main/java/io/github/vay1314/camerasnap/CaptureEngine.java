package io.github.vay1314.camerasnap;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CaptureEngine {
    private static final long FEEDBACK_DELAY_MS = 10;
    private static final long FEEDBACK_DURATION_MS = 20;
    private final Context context;
    private final SnapConfig config;
    private final Runnable finished;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable captureNext = this::awaitPhoto;
    private final Runnable focusTimeout = this::onFocusTimeout;
    private final Runnable photoTimeout = () -> fail(new IllegalStateException("等待照片输出超时"));
    private final Runnable checkRecordingSpace = this::checkRecordingSpace;
    private final Runnable startupTimeout = () -> fail(new IllegalStateException("摄像头启动超时"));
    private CameraDevice device;
    private CameraCaptureSession session;
    private CameraCharacteristics characteristics;
    private ImageReader reader;
    private ImageReader previewReader;
    private StreamConfigurationMap videoMap;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoFile;
    private Uri videoUri;
    private volatile boolean recording, closed;
    private boolean waitingForPhoto, photoInFlight;
    private int autofocusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
    private long previewStarted, minimumFocusFrame;
    private volatile boolean spaceWarningLogged;
    private String stopReason = "";
    private long feedbackUntil;
    private int photos, orientation;
    private OrientationEventListener orientationListener;

    CaptureEngine(Context context, SnapConfig config, Runnable finished) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.finished = finished;
    }

    void start() {
        io.execute(() -> {
            try {
                if (closed) return;
                if (config.saveTree.isEmpty())
                    requireSpace(new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath()).getAvailableBytes(), 0);
                main.post(() -> { if (!closed) openCamera(); });
            } catch (Exception e) { main.post(() -> fail(e)); }
        });
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String id = config.cameraId;
            if (id.isEmpty()) {
                for (String candidate : manager.getCameraIdList()) {
                    Integer facing = manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = candidate; break; }
                }
            }
            if (id.isEmpty()) throw new IllegalStateException("没有可用的后置摄像头");
            characteristics = manager.getCameraCharacteristics(id);
            orientationListener = new OrientationEventListener(context) {
                @Override public void onOrientationChanged(int degrees) {
                    if (degrees != ORIENTATION_UNKNOWN) orientation = ((degrees + 45) / 90 * 90) % 360;
                }
            };
            if (orientationListener.canDetectOrientation()) orientationListener.enable();
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("摄像头没有输出配置");
            if (config.mode == SnapConfig.VIDEO) videoMap = map;
            else {
                Size size = choosePhotoSize(map.getOutputSizes(ImageFormat.JPEG));
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
                reader.setOnImageAvailableListener(this::imageAvailable, main);
                Size preview = choosePreviewSize(map.getOutputSizes(ImageFormat.YUV_420_888));
                previewReader = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), ImageFormat.YUV_420_888, 2);

                previewReader.setOnImageAvailableListener(source -> {
                    try (Image frame = source.acquireLatestImage()) {   }
                    catch (RuntimeException e) { if (!closed) fail(e); }
                }, main);
            }
            main.postDelayed(startupTimeout, 15_000);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (closed) { camera.close(); return; }
                    device = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (!closed) fail(new IllegalStateException("摄像头已断开"));
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (!closed) fail(new IllegalStateException("摄像头错误 " + error));
                }
            }, main);
        } catch (Exception e) { fail(e); }
    }

    private void createSession() {
        try {
            if (config.mode == SnapConfig.VIDEO) prepareVideo(videoMap);
            Surface output = config.mode == SnapConfig.VIDEO ? recorder.getSurface() : reader.getSurface();
            java.util.List<Surface> outputs = config.mode == SnapConfig.VIDEO ? Collections.singletonList(output) :
                    Arrays.asList(previewReader.getSurface(), output);
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (closed) { configured.close(); return; }
                    session = configured;
                    main.removeCallbacks(startupTimeout);
                    if (config.mode == SnapConfig.VIDEO) startVideo(output);
                    else {
                        try {
                            CaptureRequest.Builder preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            preview.addTarget(previewReader.getSurface());
                            autoControls(preview);
                            previewStarted = SystemClock.uptimeMillis();
                            configured.setRepeatingRequest(preview.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override public void onCaptureCompleted(CameraCaptureSession captureSession,
                                        CaptureRequest request, TotalCaptureResult result) {
                                    if (closed || !waitingForPhoto || SystemClock.uptimeMillis() < previewStarted + 200 ||
                                            result.getFrameNumber() < minimumFocusFrame) return;
                                    if (CaptureReadiness.ready(autofocusMode != CaptureRequest.CONTROL_AF_MODE_OFF,
                                            result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.CONTROL_AE_STATE),
                                            result.get(CaptureResult.CONTROL_AWB_STATE))) capturePhoto();
                                }
                            }, main);
                            main.post(captureNext);
                        } catch (Exception e) { fail(e); }
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    failed.close();
                    if (!closed) fail(new IllegalStateException("无法配置拍摄会话"));
                }
            }, main);
        } catch (Exception e) { fail(e); }
    }

    private void autoControls(CaptureRequest.Builder request) {
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int desired = config.mode == SnapConfig.VIDEO ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO :
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        autofocusMode = modes != null && Arrays.stream(modes).anyMatch(mode -> mode == desired) ? desired :
                modes != null && Arrays.stream(modes).anyMatch(mode -> mode == CaptureRequest.CONTROL_AF_MODE_AUTO) ?
                        CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF;
        request.set(CaptureRequest.CONTROL_AF_MODE, autofocusMode);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private int rotation() {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        int adjustment = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? -orientation : orientation;
        return ((sensor == null ? 0 : sensor) + adjustment + 360) % 360;
    }

    private void awaitPhoto() {
        if (closed || waitingForPhoto || photoInFlight) return;
        waitingForPhoto = true;
        minimumFocusFrame = autofocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO ? Long.MAX_VALUE : 0;
        main.postDelayed(focusTimeout, 3000);
        if (autofocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
            try {
                CaptureRequest.Builder trigger = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                trigger.addTarget(previewReader.getSurface());
                autoControls(trigger);
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(trigger.build(), null, main);
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(trigger.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureStarted(CameraCaptureSession captureSession,
                            CaptureRequest request, long timestamp, long frameNumber) {
                        if (!closed && waitingForPhoto) minimumFocusFrame = frameNumber;
                    }
                }, main);
            } catch (Exception e) { fail(e); }
        }
    }

    private void onFocusTimeout() {
        if (waitingForPhoto && !closed) {
            Log.w(HookEntry.TAG, "3A wait timed out; capturing with available settings");
            capturePhoto();
        }
    }

    private void capturePhoto() {
        if (closed) return;
        if (!waitingForPhoto || photoInFlight) return;
        waitingForPhoto = false;
        photoInFlight = true;
        main.removeCallbacks(focusTimeout);
        main.postDelayed(photoTimeout, 15_000);
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            autoControls(request);
            request.set(CaptureRequest.JPEG_ORIENTATION, rotation());
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(CameraCaptureSession captureSession, CaptureRequest request,
                                                       android.hardware.camera2.CaptureFailure failure) {
                    if (!closed) fail(new IllegalStateException("拍照失败 " + failure.getReason()));
                }
            }, main);
        } catch (Exception e) { fail(e); }
    }

    private void imageAvailable(ImageReader source) {
        byte[] jpeg;
        try (Image image = source.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
        } catch (RuntimeException e) { if (!closed) fail(e); return; }
        if (closed || !photoInFlight) return;
        photoInFlight = false;
        main.removeCallbacks(photoTimeout);
        io.execute(() -> {
            Uri uri = null;
            try {
                uri = insertMedia(false);
                ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "w");
                if (descriptor == null) throw new IllegalStateException("无法写入照片");
                try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                    requireSpace(availableBytes(descriptor.getFileDescriptor()), jpeg.length);
                    stream.write(jpeg);
                }
                publish(uri);
                main.post(() -> {
                    photos++;
                    report("已保存 " + photos + " 张照片到 " + config.destinationLabel());

                    vibrateShort("photo_saved");
                    if (!closed) {
                        if (photos >= 100) finished.run();
                        else main.postDelayed(captureNext, 200);
                    }
                });
            } catch (Exception e) {
                if (uri != null) deleteMedia(uri);
                SnapConfig.status(context, "照片保存失败：" + e.getMessage());
                main.post(() -> fail(e));
            }
        });
    }

    private void prepareVideo(StreamConfigurationMap map) throws Exception {
        Size size = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
        videoUri = insertMedia(true);
        videoFile = context.getContentResolver().openFileDescriptor(videoUri, "w");
        if (videoFile == null) throw new IllegalStateException("无法创建视频文件");
        long available = availableBytes(videoFile.getFileDescriptor());
        requireSpace(available, 1024 * 1024);
        if (!config.saveTree.isEmpty()) {
            try { Os.lseek(videoFile.getFileDescriptor(), 0, OsConstants.SEEK_CUR); }
            catch (android.system.ErrnoException e) {
                throw new IllegalStateException("所选文件夹不支持视频写入，请选择手机本地文件夹", e);
            }
        }
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setVideoSize(size.getWidth(), size.getHeight());
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(10_000_000);
        recorder.setAudioEncodingBitRate(128_000);
        recorder.setAudioSamplingRate(44_100);
        recorder.setOrientationHint(rotation());
        recorder.setOutputFile(videoFile.getFileDescriptor());
        recorder.setMaxDuration((int) SnapConfig.MAX_SESSION_MS);
        long videoLimit = StoragePolicy.videoLimit(available);
        if (videoLimit > 0) recorder.setMaxFileSize(videoLimit);
        recorder.setOnErrorListener((recorder, what, extra) -> fail(new IllegalStateException("录像错误 " + what)));
        recorder.setOnInfoListener((recorder, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop("已达到单次录像时长上限");
            else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop("已达到录像安全文件大小上限");
        });
        recorder.prepare();
    }

    private void startVideo(Surface output) {
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(output);
            autoControls(request);
            session.setRepeatingRequest(request.build(), null, main);
            recorder.start();
            recording = true;
            main.postDelayed(checkRecordingSpace, 5000);
            vibrateShort("video_started");
            SnapConfig.status(context, "正在录像，松开音量下键停止");
        } catch (Exception e) { fail(e); }
    }

    private void vibrateShort(String event) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = context.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else vibrator = context.getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(HookEntry.TAG, "Capture vibration unavailable: " + event);
                return;
            }

            VibrationEffect effect = VibrationEffect.createWaveform(
                    new long[]{FEEDBACK_DELAY_MS, FEEDBACK_DURATION_MS}, -1);

            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build());
            } else vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build());

            feedbackUntil = SystemClock.uptimeMillis() + FEEDBACK_DELAY_MS + FEEDBACK_DURATION_MS + 100;

            Log.d(HookEntry.TAG, "Capture vibration requested: " + event +
                    ", delayMs=" + FEEDBACK_DELAY_MS + ", durationMs=" +
                    FEEDBACK_DURATION_MS + ", amplitude=default" +
                    ", usage=" + (Build.VERSION.SDK_INT >= 33 ? "HARDWARE_FEEDBACK" : "ALARM"));
        } catch (RuntimeException e) {
            Log.w(HookEntry.TAG, "Capture vibration failed", e);
        }
    }

    private Uri insertMedia(boolean video) throws Exception {
        String filename = (video ? "VID_" : "IMG_") +
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + "_SNAP" + (video ? ".mp4" : ".jpg");
        if (!config.saveTree.isEmpty()) {
            Uri tree = Uri.parse(config.saveTree);
            boolean writable = context.getContentResolver().getPersistedUriPermissions().stream()
                    .anyMatch(permission -> permission.getUri().equals(tree) && permission.isWritePermission());
            if (!writable) throw new SecurityException("保存目录授权已失效，请在模块设置中重新选择文件夹");
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri document = DocumentsContract.createDocument(context.getContentResolver(), directory,
                    video ? "video/mp4" : "image/jpeg", filename);
            if (document == null) throw new IllegalStateException("无法在所选文件夹创建媒体文件");
            return document;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, config.savePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI :
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("无法创建媒体文件");
        return uri;
    }

    private void publish(Uri uri) {
        if (!config.saveTree.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (context.getContentResolver().update(uri, values, null, null) != 1)
            throw new IllegalStateException("媒体保存失败");
    }

    private void deleteMedia(Uri uri) {
        try {
            if (!config.saveTree.isEmpty()) DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            else context.getContentResolver().delete(uri, null, null);
        } catch (Exception e) { Log.w(HookEntry.TAG, "Cannot remove failed media", e); }
    }

    private void fail(Exception e) {
        if (closed) return;
        Log.e(HookEntry.TAG, "Capture failed", e);
        stopReason = "拍摄失败：" + e.getClass().getSimpleName() + " · " + e.getMessage();
        SnapConfig.status(context, stopReason);
        finished.run();
    }

    void stop(String reason) {
        if (closed) return;
        stopReason = reason;
        SnapConfig.status(context, reason);
        finished.run();
    }

    private void report(String text) {
        SnapConfig.status(context, text + (stopReason.isEmpty() ? "" : "；" + stopReason));
    }

    private static void requireSpace(long available, long incoming) {
        if (!StoragePolicy.canWrite(available, incoming))
            throw new IllegalStateException("存储空间不足，需保留至少 200 MiB 可用空间");
    }

    private long availableBytes(FileDescriptor descriptor) {
        try {
            android.system.StructStatVfs stats = Os.fstatvfs(descriptor);
            return Math.multiplyExact(stats.f_bavail, stats.f_frsize);
        } catch (Exception e) {
            if (!spaceWarningLogged) {
                spaceWarningLogged = true;
                Log.w(HookEntry.TAG, "Destination free space unavailable; relying on write/recorder errors", e);
            }
            return -1;
        }
    }

    private void checkRecordingSpace() {
        if (closed || !recording || videoFile == null) return;
        FileDescriptor descriptor = videoFile.getFileDescriptor();
        io.execute(() -> {
            if (closed || !recording) return;
            long available = availableBytes(descriptor);
            main.post(() -> {
                if (closed || !recording) return;
                if (!StoragePolicy.canWrite(available, 1024 * 1024)) stop("存储空间不足，已停止录像");
                else main.postDelayed(checkRecordingSpace, 5000);
            });
        });
    }

    void close() { close(() -> { }); }

    void close(Runnable completed) {
        if (closed) return;
        closed = true;
        main.removeCallbacks(captureNext);
        main.removeCallbacks(startupTimeout);
        main.removeCallbacks(focusTimeout);
        main.removeCallbacks(photoTimeout);
        main.removeCallbacks(checkRecordingSpace);
        waitingForPhoto = false;
        if (orientationListener != null) orientationListener.disable();
        boolean validVideo = false;
        boolean wasRecording = recording;
        if (recorder != null) {
            try {
                if (recording) { recorder.stop(); validVideo = true; }
            } catch (RuntimeException e) {
                report("视频未保存：录制时间过短或录制中断");
                Log.w(HookEntry.TAG, "Video stop failed", e);
            } finally {
                recording = false;
                try { recorder.release(); } catch (RuntimeException e) { Log.w(HookEntry.TAG, "Recorder release failed", e); }
            }
        }

        if (wasRecording) vibrateShort("video_stopped");
        cleanup(() -> { if (session != null) session.close(); });
        cleanup(() -> { if (device != null) device.close(); });
        cleanup(() -> { if (reader != null) reader.close(); });
        cleanup(() -> { if (previewReader != null) previewReader.close(); });
        if (videoFile != null) {
            try { videoFile.close(); } catch (Exception e) { validVideo = false; }
        }
        if (videoUri != null) {
            if (validVideo) {
                try { publish(videoUri); report("视频已保存到 " + config.destinationLabel()); }
                catch (RuntimeException e) { deleteMedia(videoUri); report("视频保存失败：" + e.getMessage()); }
            } else deleteMedia(videoUri);
        }

        io.execute(() -> main.post(() -> main.postDelayed(completed,
                Math.max(0, feedbackUntil - SystemClock.uptimeMillis()))));
        io.shutdown();
    }

    private void cleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { Log.w(HookEntry.TAG, "Resource cleanup failed", e); }
    }

    static Size choosePhotoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("没有 JPEG 输出尺寸");
        Comparator<Size> area = Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight());
        return Arrays.stream(sizes).filter(size -> (long) size.getWidth() * size.getHeight() <= 12_500_000)
                .max(area).orElseGet(() -> Arrays.stream(sizes).min(area)
                        .orElseThrow(() -> new IllegalStateException("没有照片尺寸")));
    }

    static Size chooseVideoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("没有录像输出尺寸");
        return Arrays.stream(sizes).filter(size -> size.getWidth() <= 1920 && size.getHeight() <= 1080 &&
                        size.getWidth() % 2 == 0 && size.getHeight() % 2 == 0)
                .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                .orElseThrow(() -> new IllegalStateException("没有兼容的 1080p/720p 录像尺寸"));
    }

    static Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("没有预览输出尺寸");
        Comparator<Size> area = Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight());
        return Arrays.stream(sizes).filter(size -> size.getWidth() <= 640 && size.getHeight() <= 480)
                .max(area).orElseGet(() -> Arrays.stream(sizes).min(area)
                        .orElseThrow(() -> new IllegalStateException("没有预览尺寸")));
    }
}
