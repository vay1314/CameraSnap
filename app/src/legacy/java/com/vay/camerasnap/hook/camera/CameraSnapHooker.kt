package com.vay.camerasnap.hook.camera

import android.provider.Settings
import com.vay.camerasnap.constant.Key
import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.wrapper.camera.snap.SnapCamera
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog

object CameraSnapHooker: YukiBaseHooker() {
    override fun onHook() {
        CameraMembers.SnapCameraMembers.mInitSnapType.hook {
            replaceUnit {
                val snapCamera = SnapCamera.getWrapper(instance)

                val snapType = Settings.Secure.getString(
                    snapCamera.mContext.contentResolver,
                    Key.LONG_PRESS_VOLUME_DOWN
                )
                snapCamera.mIsCamcorder =
                    snapType == Key.LONG_PRESS_VOLUME_DOWN_STREET_SNAP_MOVIE
            }
        }

        CameraMembers.SnapCameraMembers.mPlaySound.hook { intercept() }

        CameraMembers.SnapCameraMembers.mRelease.hook {
            before {
                try {
                    YLog.warn(msg = "stopCamcorder: release")
                    SnapCamera.getWrapper(instance).stopCamcorder()
                    SnapCamera.removeWrapper(instance)
                } catch (e: Exception) {
                    YLog.error(msg = "release: ${e.message}", e = e)
                }
            }
        }
    }
}