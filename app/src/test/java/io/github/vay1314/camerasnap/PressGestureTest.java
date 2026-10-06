package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class PressGestureTest {
    private static final class Fixture implements PressGesture.Scheduler, PressGesture.Actions {
        Runnable task;
        int starts, stops, scheduled;
        long delay;
        final PressGesture gesture = new PressGesture(this, this);
        @Override public void schedule(Runnable task, long delay) { this.task = task; this.delay = delay; scheduled++; }
        @Override public void cancel(Runnable task) { if (this.task == task) this.task = null; }
        @Override public void start() { starts++; }
        @Override public void stop() { stops++; }
        void fire() { Runnable runnable = task; task = null; if (runnable != null) runnable.run(); }
    }

    @Test public void shortPressNeverOpensCamera() {
        Fixture f = new Fixture();
        f.gesture.down(500);
        f.gesture.cancel();
        f.fire();
        assertEquals(0, f.starts);
        assertEquals(0, f.stops);
    }

    @Test public void longPressStartsOnceAndReleaseStopsOnce() {
        Fixture f = new Fixture();
        f.gesture.down(500);
        f.fire();
        f.gesture.down(0); 
        f.fire();
        f.gesture.cancel();
        f.gesture.cancel();
        assertEquals(1, f.starts);
        assertEquals(1, f.stops);
    }

    @Test public void screenOnOrDisableCancelsPendingPress() {
        Fixture f = new Fixture();
        f.gesture.down(300);
        Runnable staleTimer = f.task;
        f.gesture.cancel();
        staleTimer.run(); 
        assertEquals(0, f.starts);
    }

    @Test public void nextPressWorksAfterStoppingPreviousCapture() {
        Fixture f = new Fixture();
        for (int i = 0; i < 2; i++) { f.gesture.down(500); f.fire(); f.gesture.cancel(); }
        assertEquals(2, f.starts);
        assertEquals(2, f.stops);
    }

    @Test public void repeatsDoNotResetLongPressDeadline() {
        Fixture f = new Fixture();
        f.gesture.down(500);
        f.gesture.down(100);
        assertEquals(1, f.scheduled);
        assertEquals(500, f.delay);
    }

    @Test public void delayedDispatchClampsExpiredDeadlineToZero() {
        Fixture f = new Fixture();
        f.gesture.down(-20);
        assertEquals(0, f.delay);
        f.fire();
        assertEquals(1, f.starts);
    }
}
