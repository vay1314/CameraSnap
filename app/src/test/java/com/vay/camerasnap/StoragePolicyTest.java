package com.vay.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class StoragePolicyTest {
    @Test public void preservesReserveAfterPhotoWrite() {
        long reserve = StoragePolicy.RESERVE_BYTES;
        assertFalse(StoragePolicy.canWrite(reserve - 1, 0));
        assertTrue(StoragePolicy.canWrite(reserve, 0));
        assertFalse(StoragePolicy.canWrite(reserve + 4095, 4096));
        assertTrue(StoragePolicy.canWrite(reserve + 4096, 4096));
    }

    @Test public void unknownProviderSpaceReliesOnWriteErrors() {
        assertTrue(StoragePolicy.canWrite(-1, 4096));
        assertEquals(0, StoragePolicy.videoLimit(-1));
    }

    @Test public void videoSizeNeverConsumesReserve() {
        assertEquals(100, StoragePolicy.videoLimit(StoragePolicy.RESERVE_BYTES + 100));
        assertEquals(0, StoragePolicy.videoLimit(StoragePolicy.RESERVE_BYTES - 1));
    }

    @Test public void incomingSizeCannotOverflowOrBeNegative() {
        assertFalse(StoragePolicy.canWrite(Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(StoragePolicy.canWrite(100, Long.MAX_VALUE));
        assertFalse(StoragePolicy.canWrite(Long.MAX_VALUE, -1));
    }
}
