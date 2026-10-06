package io.github.vay1314.camerasnap.dexkit

import androidx.annotation.Keep
import java.lang.reflect.Field
import java.lang.reflect.Method

@Keep
object CameraMembers {
    @Keep
    object SnapTriggerMembers {
        lateinit var cSnapTrigger: Class<*>

        lateinit var mSnapRunner: Method
        lateinit var mShouldQuitSnap: Method
        lateinit var mVibrator: Method

        lateinit var fMCamera: Field
        lateinit var fMPowerManager: Field
        lateinit var fMHandler: Field
    }

    @Keep
    object SnapCameraMembers {
        lateinit var cSnapCamera: Class<*>

        lateinit var mInitSnapType: Method
        lateinit var mPlaySound: Method
        lateinit var mRelease: Method

        lateinit var fIsCamcorder: Field
        lateinit var fMCameraId: Field
        lateinit var fMCameraDevice: Field
        lateinit var fMCameraCapabilities: Field
        lateinit var fMOrientation: Field
        lateinit var fMCameraHandler: Field
        lateinit var fMContext: Field
        lateinit var fMStatusListener: Field
    }

    @Keep
    object SettingsMembers {
        lateinit var cCameraSettings: Class<*>

        lateinit var mGetSupportSnap: Method
        lateinit var mGetMiuiSettingsKeyForStreetSnap: Method
        lateinit var mGetPreferVideoQuality: Method
    }

    @Keep
    object OtherMembers {
        lateinit var mTrackSnapInfo: Method

        lateinit var mGetContentUriFromPath: Method

        lateinit var mGetAvailableSpace: Method

        lateinit var mInstance: Method
        lateinit var mGetCurrentLocation: Method

        lateinit var mGetDuration: Method

        lateinit var mGetSensorOrientation: Method
        lateinit var mGetFacing: Method

        lateinit var mInitializeActivity: Method
        lateinit var mRegisterListener: Method
    }
}