package com.github.catatafishen.agentbridge.permissions;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PermissionStore}.
 * <p>
 * Uses a HashMap-based fake for {@link PropertiesComponent} because Mockito's inline mock
 * mode cannot instrument that class on Java 25 (JVM restriction on modifying core classes).
 */
class PermissionStoreTest {

    private static final String PREFIX = "agentbridge.permission.";

    /**
     * Minimal in-memory stand-in for the abstract PropertiesComponent.
     */
    private static class FakePropertiesComponent extends PropertiesComponent {
        private final Map<String, String> data = new HashMap<>();

        @Override
        public @Nullable String getValue(@NotNull String name) {
            return data.get(name);
        }

        @Override
        public void setValue(@NotNull String name, @Nullable String value) {
            if (value == null) data.remove(name);
            else data.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, @Nullable String value, @Nullable String defaultValue) {
            setValue(name, value);
        }

        @Override
        public void unsetValue(@NotNull String name) {
            data.remove(name);
        }

        @Override
        public boolean isValueSet(@NotNull String name) {
            return data.containsKey(name);
        }

        @Override
        public void setValue(@NotNull String name, float value, float defaultValue) {
            data.put(name, String.valueOf(value));
        }

        @Override
        public void setValue(@NotNull String name, int value, int defaultValue) {
            data.put(name, String.valueOf(value));
        }

        @Override
        public void setValue(@NotNull String name, boolean value, boolean defaultValue) {
            data.put(name, String.valueOf(value));
        }

        @Override
        public boolean updateValue(@NotNull String name, boolean value) {
            String prev = data.put(name, String.valueOf(value));
            return !String.valueOf(value).equals(prev);
        }

        @Override
        public @Nullable String[] getValues(@NotNull String name) {
            return null;
        }

        @Override
        public void setValues(@NotNull String name, @Nullable String @Nullable [] values) {
            // not used by PermissionStore
        }

        @Override
        public @Nullable List<String> getList(@NotNull String name) {
            return null;
        }

        @Override
        public void setList(@NotNull String name, @Nullable Collection<String> values) {
            // not used by PermissionStore
        }
    }

    private FakePropertiesComponent properties;
    private PermissionStore store;

    @BeforeEach
    void setUp() {
        properties = new FakePropertiesComponent();
        store = new PermissionStore(properties);
    }

    // ── getExplicitPermission ─────────────────────────────────────────────────

    @Test
    void getExplicitPermissionReturnsNullWhenNotSet() {
        assertNull(store.getExplicitPermission("my_tool"));
    }

    @Test
    void getExplicitPermissionReturnsAllowWhenStored() {
        properties.setValue(PREFIX + "my_tool", "ALLOW");

        assertEquals(ToolPermission.ALLOW, store.getExplicitPermission("my_tool"));
    }

    @Test
    void getExplicitPermissionReturnsDenyWhenStored() {
        properties.setValue(PREFIX + "my_tool", "DENY");

        assertEquals(ToolPermission.DENY, store.getExplicitPermission("my_tool"));
    }

    @Test
    void getExplicitPermissionReturnsAskWhenStored() {
        properties.setValue(PREFIX + "my_tool", "ASK");

        assertEquals(ToolPermission.ASK, store.getExplicitPermission("my_tool"));
    }

    @Test
    void getExplicitPermissionReturnsNullForUnrecognizedValue() {
        properties.setValue(PREFIX + "my_tool", "INVALID_PERMISSION");

        assertNull(store.getExplicitPermission("my_tool"));
    }

    // ── setExplicitPermission ─────────────────────────────────────────────────

    @Test
    void setExplicitPermissionWritesToPropertiesWithCorrectKey() {
        store.setExplicitPermission("git_commit", ToolPermission.DENY);

        assertEquals("DENY", properties.getValue(PREFIX + "git_commit"));
    }

    @Test
    void setExplicitPermissionWritesAskPermission() {
        store.setExplicitPermission("run_command", ToolPermission.ASK);

        assertEquals("ASK", properties.getValue(PREFIX + "run_command"));
    }

    // ── removeExplicitPermission ──────────────────────────────────────────────

    @Test
    void removeExplicitPermissionUnsetsTheValue() {
        properties.setValue(PREFIX + "delete_file", "ALLOW");

        store.removeExplicitPermission("delete_file");

        assertNull(properties.getValue(PREFIX + "delete_file"));
    }

    // ── session-level permissions ─────────────────────────────────────────────

    @Test
    void isSessionAllowedReturnsFalseInitially() {
        assertFalse(store.isSessionAllowed("run_tests"));
    }

    @Test
    void allowForSessionMakesToolSessionAllowed() {
        store.allowForSession("run_tests");

        assertTrue(store.isSessionAllowed("run_tests"));
    }

    @Test
    void allowForSessionDoesNotAffectOtherTools() {
        store.allowForSession("run_tests");

        assertFalse(store.isSessionAllowed("git_commit"));
    }

    @Test
    void clearSessionPermissionsRemovesAllSessionAllowances() {
        store.allowForSession("run_tests");
        store.allowForSession("git_commit");
        store.allowForSession("build_project");

        store.clearSessionPermissions();

        assertFalse(store.isSessionAllowed("run_tests"));
        assertFalse(store.isSessionAllowed("git_commit"));
        assertFalse(store.isSessionAllowed("build_project"));
    }

    @Test
    void clearSessionPermissionsIsIdempotent() {
        store.clearSessionPermissions();
        store.clearSessionPermissions();

        assertFalse(store.isSessionAllowed("any_tool"));
    }

    @Test
    void allowForSessionIsConcurrentlySafe() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) store.allowForSession("tool_" + i);
        });
        Thread t2 = new Thread(() -> {
            for (int i = 100; i < 200; i++) store.allowForSession("tool_" + i);
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertTrue(store.isSessionAllowed("tool_0"));
        assertTrue(store.isSessionAllowed("tool_199"));
    }
}
