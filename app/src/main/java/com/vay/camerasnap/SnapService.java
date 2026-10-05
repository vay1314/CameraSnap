package com.vay.camerasnap;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

public final class SnapService extends Service {
    private static final String CHANNEL = "street_snap";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::requestStop;
    private PowerManager.WakeLock wakeLock;
    private CaptureEngine engine;
    private PowerManager power;
    private boolean thermalRegistered;
    private boolean receiverRegistered;
    private boolean closing, restartAfterClose;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = status -> {
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && engine != null && !closing)
            engine.stop("设备温度过高，已停止拍摄");
    };
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { requestStop(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "息屏街拍", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        power = getSystemService(PowerManager.class);
        try {
            power.addThermalStatusListener(getMainExecutor(), thermalListener);
            thermalRegistered = true;
        } catch (RuntimeException e) { Log.w(HookEntry.TAG, "Thermal monitoring unavailable", e); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && SnapConfig.ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (intent == null || !SnapConfig.ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SnapConfig config = SnapConfig.read(this);
        try {
            Intent stop = new Intent(this, SnapService.class).setAction(SnapConfig.ACTION_STOP);
            PendingIntent action = PendingIntent.getService(this, 0, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle(config.mode == SnapConfig.VIDEO ? "息屏录像中" : "息屏连拍中")
                    .setContentText("松开音量下键停止，或点击停止按钮")
                    .setOngoing(true).addAction(new Notification.Action.Builder(null, "停止", action).build()).build();
            if (Build.VERSION.SDK_INT >= 30) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                if (config.mode == SnapConfig.VIDEO) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(1, notification, types);
            } else startForeground(1, notification);
            if (config.mode == SnapConfig.OFF || power.isInteractive() ||
                    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    (config.mode == SnapConfig.VIDEO && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
                SnapConfig.status(this, "拍摄未开始：请检查模式、权限和息屏状态");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (engine != null) {
                if (closing) restartAfterClose = true;
                return START_NOT_STICKY;
            }
            if (isThermalConstrained()) {
                SnapConfig.status(this, "拍摄未开始：设备温度过高，请等待降温");
                stopSelf();
                return START_NOT_STICKY;
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraSnap:Capture");
            wakeLock.acquire(SnapConfig.MAX_SESSION_MS + 15_000);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(stopReceiver, filter);
            receiverRegistered = true;
            main.postDelayed(timeout, SnapConfig.MAX_SESSION_MS);
            engine = new CaptureEngine(this, config, this::requestStop);
            engine.start();
        } catch (Exception e) {
            Log.e(HookEntry.TAG, "Capture start failed", e);
            SnapConfig.status(this, "拍摄启动失败：" + e.getClass().getSimpleName() + " · " + e.getMessage());
            requestStop();
        }
        return START_NOT_STICKY;
    }

    private void requestStop() {
        restartAfterClose = false;
        if (engine == null) { stopSelf(); return; }
        if (closing) return;
        closing = true;
        main.removeCallbacks(timeout);
        engine.close(() -> {
            engine = null;
            closing = false;

            if (restartAfterClose) {
                restartAfterClose = false;
                if (receiverRegistered) { unregisterReceiver(stopReceiver); receiverRegistered = false; }
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                onStartCommand(new Intent(SnapConfig.ACTION_START), 0, 0);
            } else stopSelf();
        });
    }

    private boolean isThermalConstrained() {
        try { return power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE; }
        catch (RuntimeException e) { Log.w(HookEntry.TAG, "Thermal status unavailable", e); return false; }
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (engine != null) engine.close();
        if (thermalRegistered) {
            try { power.removeThermalStatusListener(thermalListener); }
            catch (RuntimeException e) { Log.w(HookEntry.TAG, "Cannot remove thermal listener", e); }
            thermalRegistered = false;
        }
        if (receiverRegistered) unregisterReceiver(stopReceiver);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
