package io.github.vay1314.camerasnap.wrapper.camera2

import io.github.vay1314.camerasnap.dexkit.CameraMembers

class CameraCapabilities(private val instance: Any?) {
    fun getSensorOrientation() = CameraMembers.OtherMembers.mGetSensorOrientation.invoke(instance) as Int

    fun getFacing() = CameraMembers.OtherMembers.mGetFacing.invoke(instance) as Int
}