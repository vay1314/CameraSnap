package io.github.vay1314.camerasnap;

import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Hooker;

public final class HookEntry extends XposedModule {
    static final String TAG = "CameraSnap";
    private volatile SystemKeyDispatcher dispatcher;
    private volatile boolean retiring;
    private ClassLoader systemLoader;
    private Context systemContext;
    private boolean systemHooksInstalled;
    private String processName;
    private final Set<ClassLoader> cameraLoaders = new HashSet<>();
    private final Set<Object> cameraFragments = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, WeakReference<PreferenceLink>> preferenceLinks = new WeakHashMap<>();
    private final ReloadHooks reloadHooks = new ReloadHooks();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "API " + getApiVersion() + ", process=" + param.getProcessName());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        installSystemHooks(param.getClassLoader());
    }

    private void installHook(Method method, Hooker hooker) {
        String id = "CameraSnap:" + method.toGenericString();
        reloadHooks.install(id, hooker, () -> hook(method).setId(id).intercept(hooker));
    }

    private void installSystemHooks(ClassLoader loader) {
        systemLoader = loader;
        if (systemHooksInstalled) return;
        String[] names = {"com.android.server.policy.MiuiPhoneWindowManager",
                "com.android.server.policy.BaseMiuiPhoneWindowManager",
                "com.android.server.policy.PhoneWindowManager"};
        Method keyMethod = null;
        Class<?> policy = null;
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, loader);

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
            installHook(keyMethod, chain -> {
                if (retiring) return chain.proceed();
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
                    installHook(init, chain -> {
                        Object result = chain.proceed();
                        ensureDispatcher((Context) chain.getArgs().get(0), description);
                        return result;
                    });
                    break;
                }
            }
            systemHooksInstalled = true;
            if (systemContext != null) ensureDispatcher(systemContext, description);
            log(Log.INFO, TAG, "Installed system key hook: " + description);
        } catch (RuntimeException e) { log(Log.ERROR, TAG, "System hook setup failed", e); }
    }

    private synchronized void ensureDispatcher(Context context, String description) {
        if (retiring || context == null) return;
        systemContext = context;
        if (dispatcher == null) dispatcher = new SystemKeyDispatcher(context, this, description);
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (retiring || !"com.android.camera".equals(param.getPackageName()) || !param.isFirstPackage() ||
                !"com.android.camera".equals(processName)) return;
        installCameraHooks(param.getClassLoader());
    }

    @SuppressLint("PrivateApi")
    private void installCameraHooks(ClassLoader loader) {
        if (!cameraLoaders.add(loader)) return;

        try {
            Class<?> capture = Class.forName("com.android.camera.fragment.settings.CameraCapturePreferenceFragment", false, loader);
            installHook(Reflection.method(capture, "xh", String.class), chain -> {
                if (retiring) return chain.proceed();
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
                installHook(method, chain -> {
                    Object result = chain.proceed();
                    if (retiring) return result;
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
        if (retiring) return;
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
        cameraFragments.add(fragment);
        Class<?> preference = Class.forName("androidx.preference.Preference", false, loader);
        Class<?> listener = Class.forName("androidx.preference.Preference$OnPreferenceClickListener", false, loader);
        Object item = nativeItem == null ? preference.getConstructor(Context.class).newInstance(context) : nativeItem;
        Object originalClick = Reflection.method(preference, "getOnPreferenceClickListener").invoke(item);
        WeakReference<PreferenceLink> reference = preferenceLinks.get(item);
        PreferenceLink existing = reference == null ? null : reference.get();
        if (existing != null && originalClick == existing.installed.get()) return;
        Object originalSummary = Reflection.method(preference, "getSummary").invoke(item);
        PreferenceLink link = new PreferenceLink(item, originalClick, originalSummary);
        if (nativeItem == null) {
            Reflection.method(preference, "setKey", String.class).invoke(item, key);
            Reflection.method(preference, "setTitle", CharSequence.class).invoke(item, "CameraSnap");
        }
        Reflection.method(preference, "setSummary", CharSequence.class).invoke(item, "模块设置：长按音量下键拍照或录像");
        Object click = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getName().equals("onPreferenceClick")) {
                return link.active && !retiring && openSettings(context);
            }
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return "CameraSnapSettingsListener";
        });
        Reflection.method(preference, "setOnPreferenceClickListener", listener).invoke(item, click);
        link.installed = new WeakReference<>(click);
        preferenceLinks.put(item, new WeakReference<>(link));
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
        link.parent = new WeakReference<>(parent);
    }

    @Override public boolean onHotReloading(HotReloadingParam param) {
        SystemKeyDispatcher previous;
        synchronized (this) {
            retiring = true;
            previous = dispatcher;
        }
        try {
            if (previous != null && !previous.closeAndAwait(3000))
                throw new IllegalStateException("Policy worker did not finish retiring");
            Object[][] fragments = {new Object[0]};
            if ("com.android.camera".equals(processName) && !onMainAndAwait(() -> {
                fragments[0] = cameraFragments.toArray();
                for (WeakReference<PreferenceLink> reference : new ArrayList<>(preferenceLinks.values())) {
                    PreferenceLink link = reference.get();
                    if (link != null) link.restore();
                }
                preferenceLinks.clear();
                cameraFragments.clear();
            })) throw new IllegalStateException("Camera preference cleanup did not finish");
            param.setSavedInstanceState(new Object[]{systemLoader, systemContext,
                    cameraLoaders.toArray(new ClassLoader[0]), fragments[0]});
            dispatcher = null;
            cameraLoaders.clear();
            log(Log.INFO, TAG, "Old module generation retired for hot reload");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(Log.ERROR, TAG, "Hot reload interrupted; restart the device", e);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Hot reload rejected after cleanup failure; restart the device", e);
        }
        return false;
    }

    @Override public void onHotReloaded(HotReloadedParam param) {
        processName = param.getProcessName();
        reloadHooks.restore(param.getOldHookHandles());
        try {
            if (!(param.getSavedInstanceState() instanceof Object[] state) || state.length != 4)
                throw new IllegalStateException("Missing hot reload state");
            if (param.isSystemServer()) {
                if (!(state[0] instanceof ClassLoader loader))
                    throw new IllegalStateException("Missing system classloader");
                systemContext = state[1] instanceof Context context ? context : null;
                installSystemHooks(loader);
                if (!systemHooksInstalled) throw new IllegalStateException("System key hook was not restored");
            } else if ("com.android.camera".equals(processName)) {
                if (state[2] instanceof ClassLoader[] loaders)
                    for (ClassLoader loader : loaders) installCameraHooks(loader);
                if (state[3] instanceof Object[] fragments && !onMainAndAwait(() -> {
                    for (Object fragment : fragments) {
                        try { addSettingsLink(fragment, fragment.getClass().getClassLoader()); }
                        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    }
                })) throw new IllegalStateException("Camera preference reinstall did not finish");
            }
            log(Log.INFO, TAG, "Hot reload installed, version=" + BuildConfig.VERSION_NAME + ", process=" + processName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(Log.ERROR, TAG, "Hot reload initialization interrupted; restart the target process or device", e);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Hot reload initialization failed; restart the target process or device", e);
        } finally {
            reloadHooks.removeRemaining();
        }
    }

    private boolean onMainAndAwait(Runnable action) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) { action.run(); return true; }
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch complete = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> {
            try { action.run(); }
            catch (RuntimeException e) { failure.set(e); }
            finally { complete.countDown(); }
        };
        if (!main.post(task)) return false;
        if (!complete.await(3000, TimeUnit.MILLISECONDS)) {
            main.removeCallbacks(task);
            return false;
        }
        if (failure.get() != null) throw failure.get();
        return true;
    }

    private static final class PreferenceLink {
        final WeakReference<Object> item;
        final Object originalClick;
        final CharSequence originalSummary;
        WeakReference<Object> installed = new WeakReference<>(null);
        WeakReference<Object> parent = new WeakReference<>(null);
        boolean active = true;

        PreferenceLink(Object item, Object originalClick, Object originalSummary) {
            this.item = new WeakReference<>(item);
            this.originalClick = originalClick;
            this.originalSummary = originalSummary == null ? null : originalSummary.toString();
        }

        void restore() {
            active = false;
            Object preference = item.get();
            if (preference == null) return;
            try {
                Class<?> cls = preference.getClass();
                Object current = Reflection.method(cls, "getOnPreferenceClickListener").invoke(preference);
                if (current != installed.get()) return;
                Class<?> listener = Class.forName("androidx.preference.Preference$OnPreferenceClickListener", false, cls.getClassLoader());
                Reflection.method(cls, "setOnPreferenceClickListener", listener).invoke(preference, originalClick);
                Reflection.method(cls, "setSummary", CharSequence.class).invoke(preference, originalSummary);
                Object group = parent.get();
                if (group != null) {
                    Class<?> base = Class.forName("androidx.preference.Preference", false, cls.getClassLoader());
                    Reflection.method(group.getClass(), "removePreference", base).invoke(group, preference);
                }
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
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
