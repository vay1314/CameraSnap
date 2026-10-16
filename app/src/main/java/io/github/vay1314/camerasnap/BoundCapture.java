package io.github.vay1314.camerasnap;

final class BoundCapture {
    interface Transport {
        boolean bind(Runnable connected, Runnable disconnected);
        void start();
        void stop();
        void unbind();
    }

    private final Transport transport;
    private boolean active, bound, started;

    BoundCapture(Transport transport) { this.transport = transport; }

    boolean begin() {
        if (active) return true;
        active = true;
        try {
            bound = transport.bind(this::connected, this::stop);
            if (!bound) active = false;
            return bound;
        } catch (RuntimeException e) {
            active = false;
            throw e;
        }
    }

    private void connected() {
        if (!active || !bound || started) return;
        try {
            transport.start();
            started = true;
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    void stop() {
        active = false;
        boolean wasStarted = started;
        boolean wasBound = bound;
        started = false;
        bound = false;
        try {
            if (wasStarted) transport.stop();
        } finally {
            if (wasBound) transport.unbind();
        }
    }
}
