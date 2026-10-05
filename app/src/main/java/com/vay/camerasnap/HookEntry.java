package com.vay.camerasnap;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import io.github.libxposed.api.XposedModule;

public final class HookEntry extends XposedModule {
    static final String TAG = "UnlockCameraSnap";
    private volatile SystemKeyDispatcher dispatcher;
    private boolean systemHooksInstalled;
    private String processName;
    private final Set<ClassLoader> cameraLoaders = new HashSet<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "API " + getApiVersion() + ", process=" + param.getProcessName());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (systemHooksInstalled) return;
        String[] names = {"com.android.server.policy.MiuiPhoneWindowManager",
                "com.android.server.policy.BaseMiuiPhoneWindowManager",
                "com.android.server.policy.PhoneWindowManager"};
        Method keyMethod = null;
        Class<?> policy = null;
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, param.getClassLoader());

                for (Method candidate : cls.getDeclaredMethods()) {
                    if (candidate.getName().equals("interceptKeyBeforeQueueing") &&
                            candidate.getReturnType() == int.class &&
                            java.util.Arrays.equals(candidate.getParameterTypes(),
                                    new Class<?>[]{KeyEvent.class, int.class})) {
                        keyMethod = candidate;
                        policy = cls;
                        break;
                    }
                }
                if (keyMethod != null) break;
            } catch (ClassNotFoundException ignored) { }
        }
        if (keyMethod == null) {
            log(Log.ERROR, TAG, "No compatible policy key method; system key hook was not installed");
            return;
        }
        try {
            final String description = keyMethod.toString();
            hook(keyMethod).intercept(chain -> {
                SystemKeyDispatcher target = dispatcher;
                if (target == null) {
                    try { ensureDispatcher((Context) Reflection.field(chain.getThisObject(), "mContext"), description); }
                    catch (ReflectiveOperationException e) { log(Log.ERROR, TAG, "Cannot obtain policy context", e); }
                    target = dispatcher;
                }
                if (target != null) {
                    int userId = 0;
                    try { userId = (Integer) Reflection.field(chain.getThisObject(), "mCurrentUserId"); }
                    catch (ReflectiveOperationException ignored) { }
                    if (target.intercept((KeyEvent) chain.getArgs().get(0), userId)) return 0;
                }
                return chain.proceed();
            });

            for (Class<?> cls = policy; cls != null; cls = cls.getSuperclass()) {
                Method init = null;
                for (Method candidate : cls.getDeclaredMethods()) {
                    if (candidate.getName().equals("init") && candidate.getParameterCount() > 0 &&
                            candidate.getParameterTypes()[0] == Context.class) { init = candidate; break; }
                }
                if (init != null) {
                    hook(init).intercept(chain -> {
                        Object result = chain.proceed();
                        ensureDispatcher((Context) chain.getArgs().get(0), description);
                        return result;
                    });
                    break;
                }
            }
            systemHooksInstalled = true;
            log(Log.INFO, TAG, "Installed system key hook: " + description);
        } catch (RuntimeException e) { log(Log.ERROR, TAG, "System hook setup failed", e); }
    }

    private synchronized void ensureDispatcher(Context context, String description) {
        if (dispatcher == null && context != null) dispatcher = new SystemKeyDispatcher(context, this, description);
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.camera".equals(param.getPackageName()) || !param.isFirstPackage() ||
                !"com.android.camera".equals(processName)) return;
        ClassLoader loader = param.getClassLoader();
        if (!cameraLoaders.add(loader)) return;

        try {
            Class<?> capture = Class.forName("com.android.camera.fragment.settings.CameraCapturePreferenceFragment", false, loader);
            hook(Reflection.method(capture, "xh", String.class)).intercept(chain -> {
                if ("pref_street_shot".equals(chain.getArgs().get(0))) {
                    try {
                        Context context = (Context) Reflection.method(capture, "getContext").invoke(chain.getThisObject());
                        if (context != null && openSettings(context)) return true;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        log(Log.ERROR, TAG, "Cannot redirect native street settings", e);
                    }
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Native pref_street_shot redirect installed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "Native street settings handler unavailable", e);
        }
        try {
            Class<?> base = Class.forName("com.android.camera.fragment.settings.BasePreferenceFragment", false, loader);
            Class<?> settings = Class.forName("com.android.camera.fragment.settings.CameraPreferenceFragment", false, loader);
            for (Method method : new Method[]{Reflection.method(base, "onCreate", Bundle.class),
                    Reflection.method(base, "ue"), Reflection.method(settings, "onResume")}) {
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    try { addSettingsLink(chain.getThisObject(), loader); }
                    catch (ReflectiveOperationException | RuntimeException e) {
                        log(Log.ERROR, TAG, "Cannot add camera settings link; launcher settings remain available", e);
                    }
                    return result;
                });
            }
            log(Log.INFO, TAG, "Camera settings hooks installed independently of snap/recording internals");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "Camera settings structure unsupported; use the module launcher", e);
        }
    }

    private void addSettingsLink(Object fragment, ClassLoader loader) throws ReflectiveOperationException {
        String name = fragment.getClass().getName();
        if (!name.endsWith("CameraCommonPreferenceFragment") && !name.endsWith("CameraPreferenceFragment") &&
                !name.endsWith("CameraPreferenceFragmentMM") && !name.endsWith("CameraCapturePreferenceFragment")) return;
        Class<?> cls = fragment.getClass();
        Object root = Reflection.method(cls, "getPreferenceScreen").invoke(fragment);
        if (root == null) return;
        String key = "unlock_miui_camera_snap_settings";
        Object nativeItem = Reflection.method(root.getClass(), "findPreference", CharSequence.class).invoke(root, "pref_street_shot");
        if (nativeItem == null && Reflection.method(root.getClass(), "findPreference", CharSequence.class).invoke(root, key) != null) return;
        Context context = (Context) Reflection.method(cls, "getContext").invoke(fragment);
        if (context == null) return;
        Class<?> preference = Class.forName("androidx.preference.Preference", false, loader);
        Class<?> listener = Class.forName("androidx.preference.Preference$OnPreferenceClickListener", false, loader);
        Object item = nativeItem == null ? preference.getConstructor(Context.class).newInstance(context) : nativeItem;
        if (nativeItem == null) {
            Reflection.method(preference, "setKey", String.class).invoke(item, key);
            Reflection.method(preference, "setTitle", CharSequence.class).invoke(item, "息屏街拍");
        }
        Reflection.method(preference, "setSummary", CharSequence.class).invoke(item, "模块设置：长按音量下键拍照或录像");
        Object click = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getName().equals("onPreferenceClick")) {
                return openSettings(context);
            }
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return "CameraSnapSettingsListener";
        });
        Reflection.method(preference, "setOnPreferenceClickListener", listener).invoke(item, click);
        if (nativeItem != null) {
            log(Log.DEBUG, TAG, "Native street preference now opens module settings");
            return;
        }
        Object category = null;
        for (String categoryKey : new String[]{"category_common_setting_group2", "category_photo_setting", "category_module_setting"}) {
            category = Reflection.method(root.getClass(), "findPreference", CharSequence.class).invoke(root, categoryKey);
            if (category != null) break;
        }
        Object parent = category == null ? root : category;
        Reflection.method(parent.getClass(), "addPreference", preference).invoke(parent, item);
    }

    private boolean openSettings(Context context) {
        try {
            context.startActivity(new Intent().setClassName(SnapConfig.PACKAGE,
                    SnapConfig.PACKAGE + ".SettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Cannot open module settings", e);
            return false;
        }
    }
}
