package io.github.vay1314.camerasnap.wrapper.camera.statistic

import io.github.vay1314.camerasnap.dexkit.CameraMembers

object CameraStatUtils {
    fun trackSnapInfo(z: Boolean) {
        CameraMembers.OtherMembers.mTrackSnapInfo.invoke(null, z)
    }
}