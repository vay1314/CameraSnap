package io.github.vay1314.camerasnap;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReloadHooksTest {
    private static final Hooker CALLBACK = chain -> null;

    private static final class FakeHandle implements HookHandle {
        final String id;
        int replacements, removals;
        boolean failReplace, failRemove;
        Hooker installed;

        FakeHandle(String id) { this.id = id; }
        @Override public Executable getExecutable() { return null; }
        @Override public String getId() { return id; }
        @Override public void unhook() {
            removals++;
            if (failRemove) throw new IllegalStateException("remove failed");
        }
        @Override public HookHandle replaceHook(Hooker hooker) {
            if (failReplace) throw new IllegalStateException("replace failed");
            replacements++;
            installed = hooker;
            return new FakeHandle(id);
        }
    }

    @Test public void replacesExistingHookWithoutInstallingDuplicate() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle old = new FakeHandle("key");
        hooks.restore(Collections.singletonList(old));
        HookHandle replacement = hooks.install("key", CALLBACK, () -> { throw new AssertionError("duplicate hook"); });
        assertEquals("key", replacement.getId());
        assertSame(CALLBACK, old.installed);
        assertEquals(1, old.replacements);
        hooks.removeRemaining();
        assertEquals(0, old.removals);
    }

    @Test public void installsNewHookWhenNoPreviousIdMatches() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle fresh = new FakeHandle("new");
        assertSame(fresh, hooks.install("new", CALLBACK, () -> fresh));
        hooks.removeRemaining();
        assertEquals(0, fresh.removals);
    }

    @Test public void removesHooksNoLongerUsedByNewGeneration() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle removed = new FakeHandle("obsolete");
        FakeHandle retained = new FakeHandle("current");
        hooks.restore(Arrays.asList(removed, retained));
        hooks.install("current", CALLBACK, () -> { throw new AssertionError(); });
        hooks.removeRemaining();
        hooks.removeRemaining();
        assertEquals(1, removed.removals);
        assertEquals(0, retained.removals);
    }

    @Test public void removesUnnamedOldHooks() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle unnamed = new FakeHandle(null);
        hooks.restore(Collections.singletonList(unnamed));
        hooks.removeRemaining();
        assertEquals(1, unnamed.removals);
    }

    @Test public void failedReplacementRemainsEligibleForCleanup() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle old = new FakeHandle("broken");
        old.failReplace = true;
        hooks.restore(Collections.singletonList(old));
        assertThrows(IllegalStateException.class, () -> hooks.install("broken", CALLBACK, () -> old));
        hooks.removeRemaining();
        assertEquals(1, old.removals);
    }

    @Test public void duplicateOldIdsDoNotLeaveExtraHooksInstalled() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle first = new FakeHandle("same");
        FakeHandle second = new FakeHandle("same");
        hooks.restore(Arrays.asList(first, second));
        hooks.install("same", CALLBACK, () -> { throw new AssertionError(); });
        hooks.removeRemaining();
        assertEquals(1, first.removals);
        assertEquals(1, second.replacements);
        assertEquals(0, second.removals);
    }

    @Test public void cleanupAttemptsEveryHookEvenWhenOneRemovalFails() {
        ReloadHooks hooks = new ReloadHooks();
        FakeHandle broken = new FakeHandle("broken");
        FakeHandle other = new FakeHandle("other");
        broken.failRemove = true;
        hooks.restore(Arrays.asList(broken, other));
        assertThrows(IllegalStateException.class, hooks::removeRemaining);
        assertEquals(1, broken.removals);
        assertEquals(1, other.removals);
    }
}
