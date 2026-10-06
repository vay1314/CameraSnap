package io.github.vay1314.camerasnap.wrapper.base

import io.github.vay1314.camerasnap.hook.CameraHooker.appClassLoader
import com.highcapable.yukihookapi.hook.factory.toClass

abstract class StaticClass {
    abstract val className: String

    val clazz get() = className.toClass(appClassLoader)
}