package com.vay.camerasnap.utils

import android.content.Context
import com.vay.camerasnap.BuildConfig
import com.vay.camerasnap.dexkit.base.BaseFinder
import com.vay.camerasnap.hook.CameraHooker
import com.vay.camerasnap.hook.base.BaseHookerWithDexKit
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.java.JavaClass
import com.highcapable.yukihookapi.hook.type.java.JavaFieldClass
import com.highcapable.yukihookapi.hook.type.java.JavaMethodClass
import io.luckypray.dexkit.DexKitBridge
import io.luckypray.dexkit.builder.MethodCallerArgs
import io.luckypray.dexkit.builder.MethodInvokingArgs
import io.luckypray.dexkit.builder.MethodUsingFieldArgs
import io.luckypray.dexkit.descriptor.member.DexClassDescriptor
import io.luckypray.dexkit.descriptor.member.DexFieldDescriptor
import io.luckypray.dexkit.descriptor.member.DexMethodDescriptor
import java.lang.reflect.Field
import java.lang.reflect.Method

object DexKitHelper {
    object TypeSignature {
        const val VOID = "V"
        const val BOOLEAN = "Z"
        const val BYTE = "B"
        const val CHAR = "C"
        const val SHORT = "S"
        const val INT = "I"
        const val FLOAT = "F"
        const val LONG = "J"
        const val DOUBLE = "D"
        const val STRING = "Ljava/lang/String;"
        const val URI = "Landroid/net/Uri;"
        const val LOCATION = "Landroid/location/Location;"
        const val CameraCharacteristics = "Landroid/hardware/camera2/CameraCharacteristics;"
    }

    fun DexKitBridge.loadFinder(finder: BaseFinder) {
        finder.bridge = this
        BaseFinder.finders += finder
    }

    fun DexMethodDescriptor.getMethodInstance() = getMethodInstance(CameraHooker.appClassLoader!!).apply { isAccessible = true }

    fun DexKitBridge.uniqueFindMethodInvoking(builder: MethodInvokingArgs.Builder.() -> Unit): Method {
        val invokingList = findMethodInvoking(builder)
        val flatMap = invokingList.flatMap { it.value }

        require(flatMap.size == 1) {
            var builderInfo = ""
            builder.javaClass.declaredFields.forEach {
                it.isAccessible = true
                builderInfo += ("${it.name}: ${it.get(builder)}\n")
            }
            "uniqueFindMethodInvoking() Error: invokingList must contain exactly one item; Data: $invokingList \n Builder: $builderInfo"
        }

        return flatMap.first().getMethodInstance()
    }

    fun DexKitBridge.uniqueFindMethodCalling(builder: MethodCallerArgs.Builder.() -> Unit): Method {
        val callingList = findMethodCaller(builder)
        val flatMap = callingList.flatMap { it.value }

        require(flatMap.size == 1) {
            var builderInfo = ""
            builder.javaClass.declaredFields.forEach {
                it.isAccessible = true
                builderInfo += ("${it.name}: ${it.get(builder)}\n")
            }
            "uniqueFindMethodCalling() Error: callingList must contain exactly one item; Data: $callingList  \n Builder: $builderInfo"
        }

        return flatMap.first().getMethodInstance()
    }

    fun DexKitBridge.uniqueFindMethodUsingField(builder: MethodUsingFieldArgs.Builder.() -> Unit): Method {
        val usingList = findMethodUsingField(builder).keys

        require(usingList.size == 1) {
            var builderInfo = ""
            builder.javaClass.declaredFields.forEach {
                it.isAccessible = true
                builderInfo += ("${it.name}: ${it.get(builder)}\n")
            }
            "uniqueFindMethodUsingField() Error: UsingList must contain exactly one item; Data: $usingList \n Builder: $builderInfo"
        }

        return usingList.first().getMethodInstance()
    }

    fun DexKitBridge.findFieldUsingByMethod(fields: List<Field>, method: Method): Field {
        val result = mutableListOf<Field>()
        for (field in fields) {
            val findMethodUsingFieldResult = findMethodUsingField {
                fieldDescriptor = DexFieldDescriptor(field).descriptor
                callerMethodDescriptor = DexMethodDescriptor(method).descriptor
            }

            if (findMethodUsingFieldResult.isNotEmpty()) {
                result += field
            }
        }
        assert(result.size == 1) {
            "findFieldUsingByMethod: result.size != 1, method: ${method.declaringClass} -> ${method.name}(), result: ${result.size}"
        }
        return result[0].apply { isAccessible = true }
    }

    fun BaseHookerWithDexKit.storeMembers(context: Context, obj: Any) {
        context.getSharedPreferences("unlock_miui_camera_snap_anti_obfuscation", Context.MODE_PRIVATE).edit().apply {
            clear()

            appVersionCode?.let { putLong("app_version_code", it) }
            appVersionName?.let { putString("app_version_name", it) }
            putString("module_version_name", BuildConfig.VERSION_NAME)
            putInt("module_version_code", BuildConfig.VERSION_CODE)

            obj.javaClass.declaredClasses.forEach { clazz ->
                val className = clazz.simpleName
                clazz.declaredFields.forEach { field ->
                    val key = "${className}_${field.name}"
                    val value = when (field.type) {
                        JavaClass -> DexClassDescriptor(field.get(null) as Class<*>).descriptor
                        JavaMethodClass -> DexMethodDescriptor(field.get(null) as Method).descriptor
                        JavaFieldClass -> DexFieldDescriptor(field.get(null) as Field).descriptor
                        else -> null
                    }
                    value?.let { putString(key, it) }
                }
            }
        }.apply()
    }

    fun BaseHookerWithDexKit.loadMembers(context: Context, obj: Any): Boolean {
        val pref = context.getSharedPreferences("unlock_miui_camera_snap_anti_obfuscation", Context.MODE_PRIVATE)

        val isVersionSame = try {
            pref.getLong("app_version_code", 0) == appVersionCode &&
                    pref.getString("app_version_name", "") == appVersionName &&
                    pref.getString("module_version_name", "") == BuildConfig.VERSION_NAME &&
                    pref.getInt("module_version_code", 0) == BuildConfig.VERSION_CODE
        } catch (e: Exception) {
            YLog.error(msg = "failed to read app or module versions", e = e)
            return false
        }

        if (!isVersionSame) return false

        obj.javaClass.declaredClasses.forEach { clazz ->
            val className = clazz.simpleName
            for (field in clazz.declaredFields) {
                val key = "${className}_${field.name}"
                val value = pref.getString(key, "")!!

                if (field.type !in arrayOf(JavaClass, JavaMethodClass, JavaFieldClass)) continue

                if (value.isEmpty()) {
                    YLog.error(msg = "failed to load ${key}, pref empty")
                    return false
                }

                val instance = try {
                     when (field.type) {
                        JavaClass -> DexClassDescriptor(value).getClassInstance(appClassLoader!!)
                        JavaMethodClass -> DexMethodDescriptor(value).getMethodInstance(appClassLoader!!).apply { isAccessible = true }
                        JavaFieldClass -> DexFieldDescriptor(value).getFieldInstance(appClassLoader!!).apply { isAccessible = true }
                        else -> null
                    }
                } catch (e: ReflectiveOperationException) {
                    YLog.error(msg = "failed to load ${key}, no such members", e = e)
                    return false
                }

                field.set(null, instance)
            }
        }
        return true
    }
}