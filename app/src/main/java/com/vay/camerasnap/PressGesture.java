package com.vay.camerasnap;

final class PressGesture {
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
        void cancel(Runnable task);
    }
    interface Actions { void start(); void stop(); }
    private final Scheduler scheduler;
    private final Actions actions;
    private boolean pressed, started;
    private final Runnable trigger;

    PressGesture(Scheduler scheduler, Actions actions) {
        this.scheduler = scheduler;
        this.actions = actions;
        trigger = () -> {
            if (!pressed || started) return;
            started = true;
            actions.start();
        };
    }

    void down(long delayMs) {
        if (pressed) return;
        pressed = true;
        scheduler.schedule(trigger, Math.max(0, delayMs));
    }

    void cancel() {
        scheduler.cancel(trigger);
        pressed = false;
        if (started) {
            started = false;
            actions.stop();
        }
    }
}
