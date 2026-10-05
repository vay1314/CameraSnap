package com.vay.camerasnap.wrapper.camera

import com.vay.camerasnap.constant.Key
import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.hook.CameraHooker

object CameraSettings {
    const val KEY_CAMERA_SNAP = "pref_camera_snap_key"
    const val KEY_CATEGORY_MODULE_SETTING = "category_module_setting"
    const val KEY_CATEGORY_PHOTO_SETTING = "category_photo_setting"

    fun getPreferVideoQuality(cameraId: Int, moduleIndex: Int) =
        CameraMembers.SettingsMembers.mGetPreferVideoQuality.invoke(null, cameraId, moduleIndex) as Int

    private fun getString(i: Int) = CameraHooker.appContext!!.getString(i)

    fun getMiuiSettingsKeyForStreetSnap(str: String) = when (str) {
        getString(R.string.pref_camera_snap_value_take_picture) ->
            Key.LONG_PRESS_VOLUME_DOWN_STREET_SNAP_PICTURE
        getString(R.string.pref_camera_snap_value_take_movie) ->
            Key.LONG_PRESS_VOLUME_DOWN_STREET_SNAP_MOVIE
        else -> Key.LONG_PRESS_VOLUME_DOWN_DEFAULT
    }
}