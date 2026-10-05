package com.vay.camerasnap.hook.camera

import android.content.Context
import android.provider.Settings
import com.vay.camerasnap.constant.Key
import com.vay.camerasnap.dexkit.CameraMembers
import com.vay.camerasnap.hook.CameraHooker
import com.vay.camerasnap.hook.CameraHooker.onFindMembers
import com.vay.camerasnap.utils.ReflectUtils.isSubClassOf
import com.vay.camerasnap.wrapper.camera.CameraSettings
import com.vay.camerasnap.wrapper.camera.R
import com.vay.camerasnap.wrapper.camera.fragment.settings.CameraPreferenceFragment
import com.vay.camerasnap.wrapper.camera.ui.PreviewListPreference
import com.vay.camerasnap.wrapper.preference.PreferenceGroup
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.type.android.ContextClass
import com.highcapable.yukihookapi.hook.type.java.AnyClass

object SettingsHooker: YukiBaseHooker() {
    override fun onHook() {
        CameraMembers.SettingsMembers.mGetSupportSnap.hook {
            replaceToTrue()
        }

        CameraMembers.SettingsMembers.mGetMiuiSettingsKeyForStreetSnap.hook {
            replaceAny {
                CameraSettings.getMiuiSettingsKeyForStreetSnap(args[0].toString())
            }
        }

        val itemHookCreator: Class<*>.() -> Unit = {
            method {
                superClass()
                name = CameraMembers.OtherMembers.mInitializeActivity.name
                emptyParam()
            }.hook {
                after {
                    addSnapSetting(CameraPreferenceFragment(instance), CameraSettings.KEY_CATEGORY_MODULE_SETTING)
                    addSnapSetting(CameraPreferenceFragment(instance), CameraSettings.KEY_CATEGORY_PHOTO_SETTING)
                }
            }

            if (CameraHooker.isNewCameraVersion) {
                method {
                    name = "onPreferenceChange"
                    param("androidx.preference.Preference", AnyClass)
                }.hook {
                    before {
                        val key = args[0]!!.current()
                            .method { name = "getKey"; emptyParam(); superClass() }.string()
                        val preferenceValue = args[1]
                        if (key != CameraSettings.KEY_CAMERA_SNAP || preferenceValue == null) {
                            return@before
                        }

                        val cameraPreferenceFragment = CameraPreferenceFragment(instance)
                        var snapConfig =
                            cameraPreferenceFragment.getString(R.string.pref_camera_snap_value_off)
                        when (preferenceValue) {
                            is Boolean ->
                                snapConfig =
                                    if (preferenceValue) cameraPreferenceFragment.getString(R.string.pref_camera_snap_value_take_picture)
                                    else cameraPreferenceFragment.getString(R.string.pref_camera_snap_value_off)

                            is String ->
                                snapConfig = preferenceValue
                        }

                        Settings.Secure.putString(
                            cameraPreferenceFragment.getActivity().contentResolver,
                            Key.LONG_PRESS_VOLUME_DOWN,
                            CameraSettings.getMiuiSettingsKeyForStreetSnap(snapConfig)
                        )
                        resultTrue()
                    }
                }
            }
        }

        arrayOf(
            "com.android.camera.fragment.settings.CameraPreferenceFragment".toClassOrNull(),
            "com.android.camera2.compat.theme.custom.mm.setting.CameraPreferenceFragmentMM".toClassOrNull()
        ).forEach { it?.apply(itemHookCreator) }
    }

    private fun HookParam.addSnapSetting(cameraPreferenceFragment: CameraPreferenceFragment, modePreferenceKey: String) {
        val addCategory: PreferenceGroup

        val modePreference = cameraPreferenceFragment.mPreferenceGroup.findPreference(modePreferenceKey)
        if (modePreference != null) {
            addCategory = PreferenceGroup(modePreference)
        } else {
            YLog.error(msg = "modePreference is null, modePreferenceKey: $modePreferenceKey")
            return
        }

        val context = if ("miuix.preference.PreferenceFragment".toClass() isSubClassOf "androidx.preference.PreferenceFragment".toClass())
            instance.current().field { name = "mStyledContext"; type = ContextClass; superClass() }.cast<Context>()!!
        else
            cameraPreferenceFragment.getContext()

        val snapPreviewListPreference = PreviewListPreference(context).apply {
            setKey(CameraSettings.KEY_CAMERA_SNAP)
            setDefaultValue(cameraPreferenceFragment.getString(R.string.pref_camera_snap_default))
            setTitle(R.string.pref_camera_snap_enable_title)
            setEntries(arrayOf(
                context.getString(R.string.pref_camera_snap_entry_take_picture),
                context.getString(R.string.pref_camera_snap_entry_take_movie),
                context.getString(R.string.pref_camera_snap_entry_off)
            ))
            setEntryValues(arrayOf(
                context.getString(R.string.pref_camera_snap_value_take_picture),
                context.getString(R.string.pref_camera_snap_value_take_movie),
                context.getString(R.string.pref_camera_snap_value_off)
            ))
            setPersistent(false)
            setOrder(3)
        }

        addCategory.removePreferenceRecursively(CameraSettings.KEY_CAMERA_SNAP)

        if (cameraPreferenceFragment.mFromWhere == 163 || modePreferenceKey == CameraSettings.KEY_CATEGORY_PHOTO_SETTING) {
            addCategory.addPreference(snapPreviewListPreference.getInstance())
            cameraPreferenceFragment.registerListener()

            when (Settings.Secure.getString(cameraPreferenceFragment.getActivity().contentResolver, Key.LONG_PRESS_VOLUME_DOWN)) {
                Key.LONG_PRESS_VOLUME_DOWN_PAY, Key.LONG_PRESS_VOLUME_DOWN_DEFAULT ->
                    snapPreviewListPreference.setValue(cameraPreferenceFragment.getString(R.string.pref_camera_snap_value_off))
                Key.LONG_PRESS_VOLUME_DOWN_STREET_SNAP_PICTURE ->
                    snapPreviewListPreference.setValue(cameraPreferenceFragment.getString(R.string.pref_camera_snap_value_take_picture))
                Key.LONG_PRESS_VOLUME_DOWN_STREET_SNAP_MOVIE ->
                    snapPreviewListPreference.setValue(cameraPreferenceFragment.getString(R.string.pref_camera_snap_value_take_movie))
                else ->
                    snapPreviewListPreference.setValue(cameraPreferenceFragment.getString(R.string.pref_camera_snap_default))
            }
        }
    }
}