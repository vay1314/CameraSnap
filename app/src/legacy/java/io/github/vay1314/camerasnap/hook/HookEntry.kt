package io.github.vay1314.camerasnap.hook

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {
    override fun onInit() = configs {
        debugLog { tag = "CameraSnap" }

        isDebug = false
    }

    override fun onHook() = encase {
        loadApp("com.android.camera", CameraHooker)

        loadSystem(AndroidHooker)
    }
}
