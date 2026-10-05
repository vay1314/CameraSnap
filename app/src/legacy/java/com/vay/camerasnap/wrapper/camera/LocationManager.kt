package com.vay.camerasnap.wrapper.camera

import android.location.Location
import com.vay.camerasnap.dexkit.CameraMembers

class LocationManager(private val instance: Any) {
    companion object {
        fun instance() = CameraMembers.OtherMembers.mInstance.invoke(null).let { LocationManager(it!!) }
    }

    fun getCurrentLocation() = CameraMembers.OtherMembers.mGetCurrentLocation.invoke(instance) as Location?
}