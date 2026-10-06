package io.github.vay1314.camerasnap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

final class SystemKeyDispatcher {
    private final Context context;
    private final HookEntry module;
    private final Handler worker;
    private final PowerManager power;
    private final PressGesture gesture;
    private volatile int mode = SnapConfig.OFF;
    private volatile boolean ready;
    private boolean claimedVolumeDown;
    private final String hookDescription;
    private int retries;
    private final Runnable refresh = this::reload;

    SystemKeyDispatcher(Context context, HookEntry module, String description) {
        this.context = context;
        this.module = module;
        this.hookDescription = description;
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        HandlerThread thread = new HandlerThread("CameraSnapPolicy");
        thread.start();
        worker = new Handler(thread.getLooper());
        gesture = new PressGesture(new PressGesture.Scheduler() {
            @Override public void schedule(Runnable task, long delay) { worker.postDelayed(task, delay); }
            @Override public void cancel(Runnable task) { worker.removeCallbacks(task); }
        }, new PressGesture.Actions() {
            @Override public void start() { startCapture(); }
            @Override public void stop() { stopCapture(); }
        });
        worker.post(() -> {
            context.getContentResolver().registerContentObserver(SnapConfig.URI, true,
                    new ContentObserver(worker) {
                        @Override public void onChange(boolean selfChange) { reload(); }
                    });
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction("android.intent.action.USER_SWITCHED");
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { gesture.cancel(); }
            };
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter, null, worker);
            reload();
        });
    }

    private void reload() {
        try {
            SnapConfig config = SnapConfig.fromBundle(context.getContentResolver().call(SnapConfig.URI, "get", null, null));
            if (mode != config.mode) gesture.cancel();
            mode = config.mode;
            ready = true;
            retries = 0;
            worker.removeCallbacks(refresh);
            context.getContentResolver().call(SnapConfig.URI, "system_ready", hookDescription, null);
        } catch (RuntimeException e) {
            gesture.cancel();
            mode = SnapConfig.OFF;
            ready = false;
            if (retries++ < 12) {
                worker.removeCallbacks(refresh);
                worker.postDelayed(refresh, 5000);
            }
            module.log(Log.WARN, HookEntry.TAG, "Module configuration unavailable; keys retain original behavior", e);
        }
    }

    boolean intercept(KeyEvent event, int userId) {
        int key = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;

        if (key == KeyEvent.KEYCODE_VOLUME_DOWN && up) {
            boolean consumed = claimedVolumeDown;
            claimedVolumeDown = false;
            worker.post(gesture::cancel);
            return consumed;
        }

        if (key == KeyEvent.KEYCODE_VOLUME_DOWN && event.getRepeatCount() > 0) return claimedVolumeDown;
        if (!ready) {
            if (key == KeyEvent.KEYCODE_VOLUME_DOWN && event.getRepeatCount() == 0) worker.post(refresh);
            return false;
        }
        if (userId != 0 || power == null || power.isInteractive() || mode == SnapConfig.OFF) {
            worker.post(gesture::cancel);
            return false;
        }
        if (key == KeyEvent.KEYCODE_POWER || key == KeyEvent.KEYCODE_VOLUME_UP) {
            worker.post(gesture::cancel);
            return false;
        }
        if (key != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            claimedVolumeDown = true;
            if (event.getRepeatCount() == 0) {
                long due = event.getDownTime() + ViewConfiguration.getLongPressTimeout();
                worker.post(() -> {
                    if (mode != SnapConfig.OFF && !power.isInteractive())
                        gesture.down(due - SystemClock.uptimeMillis());
                });
            }
        }
        return true;
    }

    private void startCapture() {
        if (mode == SnapConfig.OFF || power.isInteractive()) return;
        try {
            context.startForegroundService(new Intent(SnapConfig.ACTION_START)
                    .setClassName(SnapConfig.PACKAGE, SnapConfig.PACKAGE + ".SnapService"));
        } catch (RuntimeException e) { module.log(Log.ERROR, HookEntry.TAG, "Street snap service could not start", e); }
    }

    private void stopCapture() {
        try {
            context.startService(new Intent(SnapConfig.ACTION_STOP)
                    .setClassName(SnapConfig.PACKAGE, SnapConfig.PACKAGE + ".SnapService"));
        } catch (RuntimeException e) { module.log(Log.ERROR, HookEntry.TAG, "Street snap service could not stop", e); }
    }
}
