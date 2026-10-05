package com.vay.camerasnap.hook

import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.dexkit.camera.CameraSnapMembersFinder
import com.vay.camerasnap.dexkit.camera.CameraTriggerMembersFinder
import com.vay.camerasnap.dexkit.camera.OtherMembersFinder
import com.vay.camerasnap.dexkit.camera.SettingsMembersFinder
import com.vay.camerasnap.hook.base.BaseHookerWithDexKit
import com.vay.camerasnap.hook.camera.CameraSnapHooker
import com.vay.camerasnap.hook.camera.CameraTriggerHooker
import com.vay.camerasnap.hook.camera.SettingsHooker
import com.vay.camerasnap.utils.DexKitHelper.loadFinder
import io.luckypray.dexkit.DexKitBridge

object CameraHooker: BaseHookerWithDexKit() {
    override var storeMemberClass: Any? = CameraMembers
    val isNewCameraVersion: Boolean by lazy { appVersionCode!! >= 500000000 }

    override fun onFindMembers(bridge: DexKitBridge) {
        bridge.loadFinder(CameraSnapMembersFinder)

        bridge.loadFinder(CameraTriggerMembersFinder)

        bridge.loadFinder(SettingsMembersFinder)

        bridge.loadFinder(OtherMembersFinder)
    }

    override fun startHook() {
        loadHooker(CameraTriggerHooker)

        loadHooker(CameraSnapHooker)

        loadHooker(SettingsHooker)
    }
}