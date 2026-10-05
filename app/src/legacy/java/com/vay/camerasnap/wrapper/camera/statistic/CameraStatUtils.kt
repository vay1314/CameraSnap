package com.vay.camerasnap.wrapper.camera.statistic

import com.vay.camerasnap.dexkit.CameraMembers

object CameraStatUtils {
    fun trackSnapInfo(z: Boolean) {
        CameraMembers.OtherMembers.mTrackSnapInfo.invoke(null, z)
    }
}