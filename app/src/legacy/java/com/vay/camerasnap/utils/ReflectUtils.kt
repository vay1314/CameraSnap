package com.vay.camerasnap.utils

import java.lang.reflect.Field

object ReflectUtils {
    fun Field.setValue(obj: Any, value: Any) {
        this.isAccessible = true
        this.set(obj, value)
    }

    infix fun Class<*>.isSubClassOf(superClass: Class<*>): Boolean {
        var currentClass: Class<*>? = this
        while (currentClass != null) {
            if (currentClass == superClass) {
                return true
            }
            currentClass = currentClass.superclass
        }
        return false
    }
}