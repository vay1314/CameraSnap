package com.vay.camerasnap.wrapper.base

import com.vay.camerasnap.hook.CameraHooker.appClassLoader
import com.highcapable.yukihookapi.hook.factory.toClass

abstract class StaticClass {
    abstract val className: String

    val clazz get() = className.toClass(appClassLoader)
}