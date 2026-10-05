package com.vay.camerasnap.wrapper.preference

import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.type.java.CharSequenceClass

class PreferenceGroup(private val instance: Any) {
    fun getInstance() = instance

    fun findPreference(key: String) = instance.current().method {
        superClass()
        name = "findPreference"
        param(CharSequenceClass)
    }.call(key)

    fun removePreferenceRecursively(key: String) = instance.current().method {
        superClass()
        name = "removePreferenceRecursively"
        param(CharSequenceClass)
    }.call(key)

    fun addPreference(preference: Any) = instance.current().method {
        superClass()
        name = "addPreference"
        param("androidx.preference.Preference")
    }.call(preference)
}