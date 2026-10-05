package com.vay.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureReadinessTest {
    @Test public void capturesOnlyAfterContinuousFocusAndExposureSettle() {
        assertFalse(CaptureReadiness.ready(true, 1, 1, 1));
        assertFalse(CaptureReadiness.ready(true, 2, 1, 2));
        assertTrue(CaptureReadiness.ready(true, 2, 2, 2));
    }

    @Test public void unfocusedAndFailedLocksDoNotQualify() {
        assertFalse(CaptureReadiness.ready(true, 0, 2, 2));
        assertFalse(CaptureReadiness.ready(true, 3, 2, 2));
        assertFalse(CaptureReadiness.ready(true, 5, 2, 2));
        assertFalse(CaptureReadiness.ready(true, 6, 2, 2));
        assertTrue(CaptureReadiness.ready(true, 4, 2, 2));
    }

    @Test public void fixedFocusCamerasStillWaitForExposure() {
        assertTrue(CaptureReadiness.ready(false, 0, 2, 2));
        assertFalse(CaptureReadiness.ready(false, 0, 1, 2));
    }

    @Test public void missingOptionalMetadataDoesNotBlockCapture() {
        assertTrue(CaptureReadiness.ready(true, null, null, null));
        assertFalse(CaptureReadiness.ready(true, null, 1, null));
    }

    @Test public void flashRequiredCanCaptureWithoutEnablingFlash() {
        assertTrue(CaptureReadiness.ready(true, 2, 4, 2));
        assertFalse(CaptureReadiness.ready(true, 2, 5, 2));
    }

    @Test public void whiteBalanceSearchWaitsAndLocksQualify() {
        assertFalse(CaptureReadiness.ready(true, 2, 2, 1));
        assertTrue(CaptureReadiness.ready(true, 4, 3, 3));
    }
}
