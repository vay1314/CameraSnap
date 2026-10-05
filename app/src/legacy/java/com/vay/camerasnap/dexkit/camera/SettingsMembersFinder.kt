package com.vay.camerasnap.dexkit.camera

import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.dexkit.base.BaseFinder
import com.vay.camerasnap.dexkit.const.CameraQueryKey
import com.vay.camerasnap.hook.CameraHooker
import com.vay.camerasnap.utils.DexKitHelper.getMethodInstance
import com.vay.camerasnap.utils.DexKitHelper
import com.vay.camerasnap.utils.DexKitHelper.uniqueFindMethodInvoking
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import io.luckypray.dexkit.builder.BatchFindArgs
import java.lang.reflect.Method

object SettingsMembersFinder: BaseFinder() {
    override fun prepareBatchFindClassesUsingStrings(): BatchFindArgs.Builder.() -> Unit = {
        addQuery(CameraQueryKey.CameraSettings, arrayOf("filterByConfig: isSupportVideoFrontMirror = "))
        addQuery(CameraQueryKey.DataItemFeature, arrayOf("supermoon", "supernight"))
        addQuery(CameraQueryKey.DataItemFeatureOld, arrayOf("ro.boot.camera.config"))
    }

    override fun prepareBatchFindMethodsUsingStrings(): BatchFindArgs.Builder.() -> Unit = {
        addQuery(CameraQueryKey.FunModule_updatePictureAndPreviewSize, arrayOf("previewSize: "))
        addQuery(CameraQueryKey.CameraSettings_getMiuiSettingsKeyForStreetSnap, arrayOf("none"))
        addQuery(CameraQueryKey.SnapKeyReceiver_onReceive, arrayOf("miui.intent.action.CAMERA_KEY_BUTTON"))
    }

    override fun onFindMembers() {
        CameraMembers.SettingsMembers.cCameraSettings = batchFindClassesUsingStringsResultMap[CameraQueryKey.CameraSettings]!!.first().name.toClass(CameraHooker.appClassLoader)

        val dataItemFeatureClassDescriptors = batchFindClassesUsingStringsResultMap[CameraQueryKey.DataItemFeature]!! + batchFindClassesUsingStringsResultMap[CameraQueryKey.DataItemFeatureOld]!!
        val snapKeyReceiverOnReceiveDescriptor = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapKeyReceiver_onReceive]!!.first {
            it.returnTypeSig == DexKitHelper.TypeSignature.VOID
        }
        var mGetSupportSnap:Method? = null
        for (dataItemFeatureClassDescriptor in dataItemFeatureClassDescriptors) {
            try {
                mGetSupportSnap = bridge.uniqueFindMethodInvoking {
                    methodDescriptor = snapKeyReceiverOnReceiveDescriptor.descriptor

                    beInvokedMethodDeclareClass = dataItemFeatureClassDescriptor.name
                    beInvokedMethodReturnType = DexKitHelper.TypeSignature.BOOLEAN
                    beInvokedMethodParameterTypes = arrayOf()
                }
                break
            } catch (e: Exception) {
            }
        }
        if (mGetSupportSnap != null) {
            CameraMembers.SettingsMembers.mGetSupportSnap = mGetSupportSnap
        } else {
            YLog.error(msg = "not found mGetSupportSnap!!!")
        }

        val updatePictureAndPreviewSize = batchFindMethodsUsingStringsResultMap[CameraQueryKey.FunModule_updatePictureAndPreviewSize]!!.first {
            it.declaringClassName == "com.android.camera.module.FunModule" &&
                    it.returnTypeSig == DexKitHelper.TypeSignature.VOID
        }
        val cameraSettingsClassDescriptor = batchFindClassesUsingStringsResultMap[CameraQueryKey.CameraSettings]!!.first()
        CameraMembers.SettingsMembers.mGetPreferVideoQuality = bridge.uniqueFindMethodInvoking {
            methodDescriptor = updatePictureAndPreviewSize.descriptor

            beInvokedMethodDeclareClass = cameraSettingsClassDescriptor.name
            beInvokedMethodParameterTypes = arrayOf(DexKitHelper.TypeSignature.INT, DexKitHelper.TypeSignature.INT)
            beInvokedMethodReturnType = DexKitHelper.TypeSignature.INT
        }

        CameraMembers.SettingsMembers.mGetMiuiSettingsKeyForStreetSnap =
            batchFindMethodsUsingStringsResultMap[CameraQueryKey.CameraSettings_getMiuiSettingsKeyForStreetSnap]!!.first {
                it.declaringClassName == CameraMembers.SettingsMembers.cCameraSettings.name &&
                        it.returnTypeSig == DexKitHelper.TypeSignature.STRING &&
                        it.parameterTypesSig == DexKitHelper.TypeSignature.STRING
            }.getMethodInstance()
    }
}