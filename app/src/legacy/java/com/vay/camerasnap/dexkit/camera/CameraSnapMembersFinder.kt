package com.vay.camerasnap.dexkit.camera

import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.dexkit.base.BaseFinder
import com.vay.camerasnap.dexkit.const.CameraQueryKey
import com.vay.camerasnap.hook.CameraHooker
import com.vay.camerasnap.utils.DexKitHelper
import com.vay.camerasnap.utils.DexKitHelper.findFieldUsingByMethod
import com.vay.camerasnap.utils.DexKitHelper.uniqueFindMethodInvoking
import com.vay.camerasnap.utils.DexKitHelper.uniqueFindMethodUsingField
import com.vay.camerasnap.utils.DexKitHelper.getMethodInstance
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.type.android.ContextClass
import com.highcapable.yukihookapi.hook.type.android.HandlerClass
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import io.luckypray.dexkit.builder.BatchFindArgs

object CameraSnapMembersFinder: BaseFinder(){
    override fun prepareBatchFindClassesUsingStrings(): BatchFindArgs.Builder.() -> Unit = {
        addQuery(CameraQueryKey.SnapCamera, arrayOf("takeSnap: CameraDevice is opening or was already closed."))
        addQuery(CameraQueryKey.CameraCapabilities, arrayOf("addStreamConfigurationToList: but the key is null!"))
        addQuery(CameraQueryKey.SnapTrigger, arrayOf("shouldQuitSnap isNonUI = "))
        addQuery(CameraQueryKey.PictureInfo, arrayOf("setFrontMirror JSONException occurs "))
    }

    override fun prepareBatchFindMethodsUsingStrings(): BatchFindArgs.Builder.() -> Unit = {
        addQuery(CameraQueryKey.SnapCamera_onPictureTaken, arrayOf("save picture failed "))
        addQuery(CameraQueryKey.SnapCamera_release, arrayOf("release(): E", "release(): X"))
        addQuery(CameraQueryKey.SnapTrigger_onCameraOpened, arrayOf("onCameraOpened: exit"))
        addQuery(CameraQueryKey.SnapCamera_takeSnap, arrayOf("takeSnap: CameraDevice is opening or was already closed."))
    }

    override fun onFindMembers() {
        CameraMembers.SnapCameraMembers.cSnapCamera = batchFindClassesUsingStringsResultMap[CameraQueryKey.SnapCamera]!!.first().name.toClass(CameraHooker.appClassLoader)
        val snapCameraFields = CameraMembers.SnapCameraMembers.cSnapCamera.declaredFields

        val snapTriggerOnCameraOpenedMethodDescriptor = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapTrigger_onCameraOpened]!!.first {
            it.returnTypeSig == DexKitHelper.TypeSignature.VOID
        }
        val isCamcorderMethod = bridge.uniqueFindMethodInvoking {
            methodDescriptor = snapTriggerOnCameraOpenedMethodDescriptor.descriptor

            beInvokedMethodDeclareClass = CameraMembers.SnapCameraMembers.cSnapCamera.name
            beInvokedMethodParameterTypes = arrayOf()
            beInvokedMethodReturnType = DexKitHelper.TypeSignature.BOOLEAN
        }
        CameraMembers.SnapCameraMembers.fIsCamcorder =
            bridge.findFieldUsingByMethod(snapCameraFields.filter { it.type == BooleanType }, isCamcorderMethod)

        CameraMembers.SnapCameraMembers.mInitSnapType = bridge.uniqueFindMethodUsingField {
            fieldDeclareClass = CameraMembers.SnapCameraMembers.fIsCamcorder.declaringClass.name
            fieldName = CameraMembers.SnapCameraMembers.fIsCamcorder.name

            callerMethodDeclareClass = CameraMembers.SnapCameraMembers.cSnapCamera.name
            callerMethodReturnType = DexKitHelper.TypeSignature.VOID
            callerMethodParamTypes = arrayOf()
        }

        val onPictureTakenDescriptor = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapCamera_onPictureTaken]!!.first {
            it.returnTypeSig == DexKitHelper.TypeSignature.VOID
        }
        CameraMembers.SnapCameraMembers.mPlaySound = bridge.uniqueFindMethodInvoking {
            methodDescriptor = onPictureTakenDescriptor.descriptor

            beInvokedMethodDeclareClass = CameraMembers.SnapCameraMembers.cSnapCamera.name
            beInvokedMethodReturnType = DexKitHelper.TypeSignature.VOID
            beInvokedMethodParameterTypes = arrayOf()
        }

        CameraMembers.SnapCameraMembers.mRelease = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapCamera_release]!!.first {
            it.declaringClassName == CameraMembers.SnapCameraMembers.cSnapCamera.name
        }.getMethodInstance()

        val onPictureTakenMethodDescriptor = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapCamera_onPictureTaken]!!.first {
            it.returnTypeSig == DexKitHelper.TypeSignature.VOID
        }
        val getPictureInfoMethodDescriptor = bridge.uniqueFindMethodInvoking {
            methodDescriptor = onPictureTakenMethodDescriptor.descriptor

            beInvokedMethodDeclareClass = CameraMembers.SnapCameraMembers.cSnapCamera.name
            beInvokedMethodReturnType = batchFindClassesUsingStringsResultMap[CameraQueryKey.PictureInfo]!!.first().name
            beInvokedMethodParameterTypes = arrayOf()
        }
        CameraMembers.SnapCameraMembers.fMCameraId = bridge.findFieldUsingByMethod(
            snapCameraFields.filter { it.type == IntType },
            getPictureInfoMethodDescriptor
        )

        CameraMembers.SnapCameraMembers.fMCameraDevice = CameraMembers.SnapCameraMembers.cSnapCamera.field{
            type("android.hardware.camera2.CameraDevice")
        }.give()!!

        val cameraCapabilitiesDescriptor = batchFindClassesUsingStringsResultMap[CameraQueryKey.CameraCapabilities]!!.first()
        CameraMembers.SnapCameraMembers.fMCameraCapabilities = CameraMembers.SnapCameraMembers.cSnapCamera.field {
            type(cameraCapabilitiesDescriptor.name)
        }.give()!!

        CameraMembers.SnapCameraMembers.fMOrientation = bridge.findFieldUsingByMethod(
            snapCameraFields.filter { it.type == IntType },
            CameraMembers.SnapCameraMembers.mRelease
        )

        val takeSnapMethodDescriptor = batchFindMethodsUsingStringsResultMap[CameraQueryKey.SnapCamera_takeSnap]!!.first {
            it.returnTypeSig == DexKitHelper.TypeSignature.VOID && it.parameterTypesSig == ""
        }
        CameraMembers.SnapCameraMembers.fMCameraHandler = bridge.findFieldUsingByMethod(
            snapCameraFields.filter { it.type == HandlerClass },
            takeSnapMethodDescriptor.getMethodInstance()
        )

        CameraMembers.SnapCameraMembers.fMContext = CameraMembers.SnapCameraMembers.cSnapCamera.field{
            type(ContextClass).index().first()
        }.give()!!

        val cSnapStatusListener = batchFindClassesUsingStringsResultMap[CameraQueryKey.SnapTrigger]!!.first().name.toClass(CameraHooker.appClassLoader)
            .interfaces.first()

        CameraMembers.SnapCameraMembers.fMStatusListener = CameraMembers.SnapCameraMembers.cSnapCamera.field {
            type(cSnapStatusListener)
        }.give()!!
    }
}