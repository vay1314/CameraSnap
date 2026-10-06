package io.github.vay1314.camerasnap.dexkit.camera

import io.github.vay1314.camerasnap.dexkit.CameraMembers
import io.github.vay1314.camerasnap.dexkit.base.BaseFinder
import io.github.vay1314.camerasnap.dexkit.const.CameraQueryKey
import io.github.vay1314.camerasnap.hook.CameraHooker
import io.github.vay1314.camerasnap.utils.DexKitHelper
import io.github.vay1314.camerasnap.utils.DexKitHelper.getMethodInstance
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.type.android.HandlerClass
import com.highcapable.yukihookapi.hook.type.android.PowerManagerClass
import io.luckypray.dexkit.builder.BatchFindArgs

object CameraTriggerMembersFinder: BaseFinder() {
    override fun prepareBatchFindClassesUsingStrings(): BatchFindArgs.Builder.() -> Unit = {
        addQuery(CameraQueryKey.SnapTrigger, arrayOf("shouldQuitSnap isNonUI = "))
        addQuery(CameraQueryKey.SnapCamera, arrayOf("takeSnap: CameraDevice is opening or was already closed."))
    }

    override fun prepareBatchFindMethodsUsingStrings(): BatchFindArgs.Builder.() -> Unit = {
        addQuery(CameraQueryKey.SnapTrigger_mVibrator, arrayOf("call vibrate to notify"))
        addQuery(CameraQueryKey.SnapTrigger_mShouldQuitSnap, arrayOf("shouldQuitSnap isNonUI = "))
        addQuery(CameraQueryKey.SnapTrigger_mSnapRunner, arrayOf("isScreenOn is true, stop take snap"))
    }

    override fun onFindMembers() {
        CameraMembers.SnapTriggerMembers.cSnapTrigger = batchFindClassesUsingStringsResultMap[CameraQueryKey.SnapTrigger]!!.first().name.toClass(CameraHooker.appClassLoader)

        CameraMembers.SnapTriggerMembers.mVibrator = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapTrigger_mVibrator]!!.first{
            it.returnTypeSig == DexKitHelper.TypeSignature.VOID
        }.getMethodInstance()

        CameraMembers.SnapTriggerMembers.mShouldQuitSnap = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapTrigger_mShouldQuitSnap]!!.first{
            it.returnTypeSig == DexKitHelper.TypeSignature.BOOLEAN
        }.getMethodInstance()

        CameraMembers.SnapTriggerMembers.mSnapRunner =
            batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapTrigger_mSnapRunner]!!.first {
                it.parameterTypesSig == "" && it.returnTypeSig == DexKitHelper.TypeSignature.VOID
            }.getMethodInstance()

        val snapTriggerClass = CameraMembers.SnapTriggerMembers.cSnapTrigger
        val snapCameraClass = batchFindClassesUsingStringsResultMap[CameraQueryKey.SnapCamera]!!.first().name.toClass(CameraHooker.appClassLoader)

        CameraMembers.SnapTriggerMembers.fMPowerManager = snapTriggerClass.field { type = PowerManagerClass }.give()!!
        CameraMembers.SnapTriggerMembers.fMHandler = snapTriggerClass.field { type = HandlerClass }.give()!!
        CameraMembers.SnapTriggerMembers.fMCamera = snapTriggerClass.field { type = snapCameraClass }.give()!!
    }
}