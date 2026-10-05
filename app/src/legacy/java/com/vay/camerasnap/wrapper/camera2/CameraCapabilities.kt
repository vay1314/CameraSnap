package com.vay.camerasnap.wrapper.camera2

import com.vay.camerasnap.dexkit.CameraMembers

class CameraCapabilities(private val instance: Any?) {
    fun getSensorOrientation() = CameraMembers.OtherMembers.mGetSensorOrientation.invoke(instance) as Int

    fun getFacing() = CameraMembers.OtherMembers.mGetFacing.invoke(instance) as Int
}