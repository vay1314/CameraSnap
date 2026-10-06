package io.github.vay1314.camerasnap;

final class StoragePolicy {
    static final long RESERVE_BYTES = 200L * 1024 * 1024;

    static boolean canWrite(long available, long incoming) {
        return available < 0 || (incoming >= 0 && available >= incoming && available - incoming >= RESERVE_BYTES);
    }

    static long videoLimit(long available) {
        return available < 0 ? 0 : Math.max(0, available - RESERVE_BYTES);
    }
}
