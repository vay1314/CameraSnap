package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class BoundCaptureTest {
    private static final class FakeTransport implements BoundCapture.Transport {
        Runnable connected, disconnected;
        boolean accepted = true, failStart, failStop;
        int starts, stops, unbinds;

        @Override public boolean bind(Runnable connected, Runnable disconnected) {
            this.connected = connected;
            this.disconnected = disconnected;
            return accepted;
        }
        @Override public void start() {
            if (failStart) throw new IllegalStateException("start rejected");
            starts++;
        }
        @Override public void stop() {
            stops++;
            if (failStop) throw new IllegalStateException("stop rejected");
        }
        @Override public void unbind() { unbinds++; }
    }

    @Test public void waitsForBindingBeforeStartingCapture() {
        FakeTransport transport = new FakeTransport();
        BoundCapture capture = new BoundCapture(transport);
        assertTrue(capture.begin());
        assertEquals(0, transport.starts);
        transport.connected.run();
        assertEquals(1, transport.starts);
        capture.stop();
        assertEquals(1, transport.stops);
        assertEquals(1, transport.unbinds);
    }

    @Test public void releaseBeforeConnectionPreventsLateCapture() {
        FakeTransport transport = new FakeTransport();
        BoundCapture capture = new BoundCapture(transport);
        capture.begin();
        capture.stop();
        transport.connected.run();
        assertEquals(0, transport.starts);
        assertEquals(0, transport.stops);
        assertEquals(1, transport.unbinds);
    }

    @Test public void deniedBindingNeverStartsOrUnbinds() {
        FakeTransport transport = new FakeTransport();
        transport.accepted = false;
        BoundCapture capture = new BoundCapture(transport);
        assertFalse(capture.begin());
        transport.connected.run();
        capture.stop();
        assertEquals(0, transport.starts);
        assertEquals(0, transport.unbinds);
    }

    @Test public void duplicateCallbacksCannotRestartOrDoubleUnbind() {
        FakeTransport transport = new FakeTransport();
        BoundCapture capture = new BoundCapture(transport);
        capture.begin();
        transport.connected.run();
        transport.connected.run();
        transport.disconnected.run();
        transport.disconnected.run();
        capture.stop();
        transport.connected.run();
        assertEquals(1, transport.starts);
        assertEquals(1, transport.stops);
        assertEquals(1, transport.unbinds);
    }

    @Test public void previousGestureCallbacksCannotStartNextSession() {
        FakeTransport previous = new FakeTransport();
        BoundCapture first = new BoundCapture(previous);
        first.begin();
        first.stop();
        FakeTransport next = new FakeTransport();
        BoundCapture second = new BoundCapture(next);
        second.begin();
        previous.connected.run();
        previous.disconnected.run();
        assertEquals(0, previous.starts);
        assertEquals(0, next.starts);
        next.connected.run();
        assertEquals(1, next.starts);
        assertEquals(0, next.unbinds);
        second.stop();
    }

    @Test public void failedForegroundStartReleasesBinding() {
        FakeTransport transport = new FakeTransport();
        transport.failStart = true;
        BoundCapture capture = new BoundCapture(transport);
        capture.begin();
        assertThrows(IllegalStateException.class, () -> transport.connected.run());
        capture.stop();
        assertEquals(0, transport.stops);
        assertEquals(1, transport.unbinds);
    }

    @Test public void failedStopStillReleasesBinding() {
        FakeTransport transport = new FakeTransport();
        BoundCapture capture = new BoundCapture(transport);
        capture.begin();
        transport.connected.run();
        transport.failStop = true;
        assertThrows(IllegalStateException.class, capture::stop);
        capture.stop();
        assertEquals(1, transport.stops);
        assertEquals(1, transport.unbinds);
    }
}
