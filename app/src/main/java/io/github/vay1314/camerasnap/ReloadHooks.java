package io.github.vay1314.camerasnap;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class ReloadHooks {
    private final Map<String, HookHandle> previous = new HashMap<>();
    private final List<HookHandle> unnamed = new ArrayList<>();

    void restore(List<HookHandle> handles) {
        for (HookHandle handle : handles) {
            String id = handle.getId();
            if (id == null) unnamed.add(handle);
            else {
                HookHandle duplicate = previous.put(id, handle);
                if (duplicate != null) unnamed.add(duplicate);
            }
        }
    }

    HookHandle install(String id, Hooker hooker, Supplier<HookHandle> fresh) {
        HookHandle old = previous.get(id);
        if (old == null) return fresh.get();
        HookHandle replacement = old.replaceHook(hooker);
        previous.remove(id);
        return replacement;
    }

    void removeRemaining() {
        List<HookHandle> remaining = new ArrayList<>(previous.values());
        remaining.addAll(unnamed);
        previous.clear();
        unnamed.clear();
        RuntimeException failure = null;
        for (HookHandle handle : remaining) {
            try { handle.unhook(); }
            catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
