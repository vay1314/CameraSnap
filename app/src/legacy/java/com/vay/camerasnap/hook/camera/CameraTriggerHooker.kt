package com.vay.camerasnap.hook.camera

import android.app.PendingIntent
import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.hook.CameraHooker.onFindMembers
import com.vay.camerasnap.wrapper.camera.snap.SnapTrigger
import com.vay.camerasnap.wrapper.camera.statistic.CameraStatUtils
import com.vay.camerasnap.wrapper.camera.storage.Storage
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.android.ContextClass
import com.highcapable.yukihookapi.hook.type.android.IntentClass
import com.highcapable.yukihookapi.hook.type.java.IntType

object CameraTriggerHooker: YukiBaseHooker() {
    override fun onHook() {
        CameraMembers.SnapTriggerMembers.mSnapRunner.hook {
            replaceUnit {
                val mSnapTrigger = SnapTrigger(instance.current().field { type = CameraMembers.SnapTriggerMembers.cSnapTrigger }.any()!!)

                if (mSnapTrigger.mCamera.instance == null || !mSnapTrigger.mCamera.mIsCamcorder) {
                    callOriginal()
                    return@replaceUnit
                }

                if (mSnapTrigger.mPowerManager == null || !mSnapTrigger.mPowerManager!!.isInteractive) {
                    if (!mSnapTrigger.shouldQuitSnap() && Storage.getAvailableSpace() >= Storage.LOW_STORAGE_THRESHOLD) {
                        mSnapTrigger.shutdownWatchDog()
                        mSnapTrigger.vibratorShort()
                        mSnapTrigger.mCamera.startCamcorder()
                        YLog.debug(msg = "take movie")
                        CameraStatUtils.trackSnapInfo(true)
                    }
                }
            }
        }

        PendingIntent::class.java.method {
            name = "getActivity"
            param(ContextClass, IntType, IntentClass, IntType)
        }.hook {
            before {
                if (appInfo.targetSdkVersion < 31) return@before

                val flag = args[3] as Int
                if (flag == 0)
                    args[3] = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            }
        }
    }
}