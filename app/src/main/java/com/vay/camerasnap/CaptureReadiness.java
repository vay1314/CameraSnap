package com.vay.camerasnap;

import android.hardware.camera2.CaptureResult;

final class CaptureReadiness {
    static boolean ready(boolean autofocus, Integer af, Integer ae, Integer awb) {
        boolean focusReady = !autofocus || af == null ||
                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;
        boolean exposureReady = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
        boolean whiteBalanceReady = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                awb == CaptureResult.CONTROL_AWB_STATE_LOCKED;
        return focusReady && exposureReady && whiteBalanceReady;
    }
}
