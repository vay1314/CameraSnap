package io.github.vay1314.camerasnap.wrapper.camera.storage

import android.os.Environment
import io.github.vay1314.camerasnap.dexkit.CameraMembers

object Storage {
    const val LOW_STORAGE_THRESHOLD = 209715200

    val DIRECTORY = Environment.getExternalStorageDirectory().toString() + "/DCIM/Camera"

    fun getAvailableSpace() = CameraMembers.OtherMembers.mGetAvailableSpace.invoke(null) as Long
}