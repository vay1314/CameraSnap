package io.github.vay1314.camerasnap.hook

import io.github.vay1314.camerasnap.dexkit.CameraMembers
import io.github.vay1314.camerasnap.dexkit.camera.CameraSnapMembersFinder
import io.github.vay1314.camerasnap.dexkit.camera.CameraTriggerMembersFinder
import io.github.vay1314.camerasnap.dexkit.camera.OtherMembersFinder
import io.github.vay1314.camerasnap.dexkit.camera.SettingsMembersFinder
import io.github.vay1314.camerasnap.hook.base.BaseHookerWithDexKit
import io.github.vay1314.camerasnap.hook.camera.CameraSnapHooker
import io.github.vay1314.camerasnap.hook.camera.CameraTriggerHooker
import io.github.vay1314.camerasnap.hook.camera.SettingsHooker
import io.github.vay1314.camerasnap.utils.DexKitHelper.loadFinder
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