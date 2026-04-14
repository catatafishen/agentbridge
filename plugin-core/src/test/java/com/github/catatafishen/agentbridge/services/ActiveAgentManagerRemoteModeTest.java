package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.acp.client.CopilotClient;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveAgentManagerRemoteModeTest {

    private ActiveAgentManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Allocate an ActiveAgentManager instance without calling its constructor, which
        // requires live IntelliJ platform services (PropertiesComponent).
        // ReflectionFactory.newConstructorForSerialization uses the same mechanism as Java
        // object deserialization: it bypasses the normal constructor chain entirely.
        Constructor<?> objDefCtor = Object.class.getDeclaredConstructor();
        @SuppressWarnings("unchecked")
        Constructor<ActiveAgentManager> serCtor =
            (Constructor<ActiveAgentManager>)
                sun.reflect.ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(ActiveAgentManager.class, objDefCtor);
        manager = serCtor.newInstance();

        // Inject a simple in-memory PropertiesComponent stub (avoids mocking IntelliJ internals).
        PropertiesComponent stub = new InMemoryPropertiesComponent();
        Field providerField = ActiveAgentManager.class.getDeclaredField("propertiesProvider");
        providerField.setAccessible(true);
        providerField.set(manager, (Supplier<PropertiesComponent>) () -> stub);
    }

    /**
     * Minimal in-memory PropertiesComponent that satisfies the abstract methods required by
     * ActiveAgentManager remote-mode logic (setValue/getBoolean for boolean settings).
     * getBoolean() is final in PropertiesComponent and delegates to getValue(), so implementing
     * getValue() is sufficient for boolean persistence.
     */
    private static final class InMemoryPropertiesComponent extends PropertiesComponent {
        private final Map<String, String> store = new HashMap<>();

        @Override
        public void setValue(@NotNull String name, @Nullable String value) {
            if (value == null) store.remove(name);
            else store.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, @Nullable String value, @Nullable String defaultValue) {
            if (value == null || value.equals(defaultValue)) store.remove(name);
            else store.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, float value, float defaultValue) {
            if (value == defaultValue) store.remove(name);
            else store.put(name, String.valueOf(value));
        }

        @Override
        public void setValue(@NotNull String name, int value, int defaultValue) {
            if (value == defaultValue) store.remove(name);
            else store.put(name, String.valueOf(value));
        }

        @Override
        public void setValue(@NotNull String name, boolean value, boolean defaultValue) {
            if (value == defaultValue) store.remove(name);
            else store.put(name, String.valueOf(value));
        }

        @Override
        public @Nullable String getValue(@NotNull String name) {
            return store.get(name);
        }

        @Override
        public boolean isValueSet(@NotNull String name) {
            return store.containsKey(name);
        }

        @Override
        public void unsetValue(@NotNull String name) {
            store.remove(name);
        }

        @Override
        public @NotNull String[] getValues(@NotNull String name) {
            String v = store.get(name);
            return v != null ? new String[]{v} : new String[0];
        }

        @Override
        public void setValues(@NotNull String name, String @Nullable [] values) {
        }

        @Override
        public java.util.List<String> getList(@NotNull String name) {
            return java.util.Collections.emptyList();
        }

        @Override
        public void setList(@NotNull String name, java.util.Collection<String> values) {
        }

        @Override
        public boolean updateValue(@NotNull String name, boolean value) {
            String old = store.get(name);
            setValue(name, value, false);
            return old == null ? value : Boolean.parseBoolean(old) != value;
        }
    }

    @Test
    void isRemoteMode_falseByDefault() {
        assertFalse(manager.isRemoteMode());
    }

    @Test
    void setRemoteMode_true_isRemoteModeReturnsTrue() {
        manager.setRemoteMode(true);
        assertTrue(manager.isRemoteMode());
    }

    @Test
    void setRemoteMode_false_isRemoteModeReturnsFalse() {
        manager.setRemoteMode(true);
        manager.setRemoteMode(false);
        assertFalse(manager.isRemoteMode());
    }

    @Test
    void setRemoteMode_toggles() {
        manager.setRemoteMode(true);
        assertTrue(manager.isRemoteMode());
        manager.setRemoteMode(true);
        assertTrue(manager.isRemoteMode());
        manager.setRemoteMode(false);
        assertFalse(manager.isRemoteMode());
    }

    @Test
    void setRemoteUrlListener_storesListener() throws Exception {
        Consumer<String> listener = url -> {
        };
        manager.setRemoteUrlListener(listener);
        Field field = ActiveAgentManager.class.getDeclaredField("pendingRemoteUrlListener");
        field.setAccessible(true);
        assertSame(listener, field.get(manager));
    }

    @Test
    void setRemoteUrlListener_null_clearsListener() throws Exception {
        manager.setRemoteUrlListener(url -> {
        });
        manager.setRemoteUrlListener(null);
        Field field = ActiveAgentManager.class.getDeclaredField("pendingRemoteUrlListener");
        field.setAccessible(true);
        assertNull(field.get(manager));
    }

    @Test
    void setRemoteUrlListener_replacesPreviousListener() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        manager.setRemoteUrlListener(first::set);
        manager.setRemoteUrlListener(second::set);
        Field field = ActiveAgentManager.class.getDeclaredField("pendingRemoteUrlListener");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Consumer<String> stored = (Consumer<String>) field.get(manager);
        stored.accept("url");
        assertNull(first.get());
        assertEquals("url", second.get());
    }

    @Test
    void configureCopilotClientForStart_remoteModeEnabled_setsRemoteModeAndListener()
        throws Exception {
        CopilotClient copilotClient = allocateCopilotClient();
        Consumer<String> listener = url -> {
        };
        manager.setRemoteMode(true);
        manager.setRemoteUrlListener(listener);

        manager.configureCopilotClientForStart(copilotClient);

        assertTrue(getRemoteModeField(copilotClient));
        assertSame(listener, getUrlListenerField(copilotClient));
    }

    @Test
    void configureCopilotClientForStart_remoteModeDisabled_doesNotSetRemoteMode()
        throws Exception {
        CopilotClient copilotClient = allocateCopilotClient();

        manager.configureCopilotClientForStart(copilotClient);

        assertFalse(getRemoteModeField(copilotClient));
        assertNull(getUrlListenerField(copilotClient));
    }

    @Test
    void configureCopilotClientForStart_withoutListener_onlySetsRemoteMode()
        throws Exception {
        CopilotClient copilotClient = allocateCopilotClient();
        manager.setRemoteMode(true);

        manager.configureCopilotClientForStart(copilotClient);

        assertTrue(getRemoteModeField(copilotClient));
        assertNull(getUrlListenerField(copilotClient));
    }

    @Test
    void setRemoteErrorListener_storesListener() throws Exception {
        Consumer<String> listener = msg -> {
        };
        manager.setRemoteErrorListener(listener);
        Field field = ActiveAgentManager.class.getDeclaredField("pendingRemoteErrorListener");
        field.setAccessible(true);
        assertSame(listener, field.get(manager));
    }

    @Test
    void setRemoteErrorListener_null_clearsListener() throws Exception {
        manager.setRemoteErrorListener(msg -> {
        });
        manager.setRemoteErrorListener(null);
        Field field = ActiveAgentManager.class.getDeclaredField("pendingRemoteErrorListener");
        field.setAccessible(true);
        assertNull(field.get(manager));
    }

    @Test
    void configureCopilotClientForStart_remoteModeEnabled_setsErrorListener() throws Exception {
        CopilotClient copilotClient = allocateCopilotClient();
        Consumer<String> listener = msg -> {
        };
        manager.setRemoteMode(true);
        manager.setRemoteErrorListener(listener);

        manager.configureCopilotClientForStart(copilotClient);

        assertSame(listener, getErrorListenerField(copilotClient));
    }

    @Test
    void configureCopilotClientForStart_noErrorListener_doesNotCallSetRemoteErrorListener()
        throws Exception {
        CopilotClient copilotClient = allocateCopilotClient();
        manager.setRemoteMode(true);

        manager.configureCopilotClientForStart(copilotClient);

        assertNull(getErrorListenerField(copilotClient));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static CopilotClient allocateCopilotClient() throws Exception {
        java.lang.reflect.Constructor<?> objDefCtor = Object.class.getDeclaredConstructor();
        java.lang.reflect.Constructor<CopilotClient> serCtor =
            (java.lang.reflect.Constructor<CopilotClient>)
                sun.reflect.ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(CopilotClient.class, objDefCtor);
        return serCtor.newInstance();
    }

    private static boolean getRemoteModeField(CopilotClient client) throws Exception {
        Field f = CopilotClient.class.getDeclaredField("remoteMode");
        f.setAccessible(true);
        return (boolean) f.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> getUrlListenerField(CopilotClient client) throws Exception {
        Field f = CopilotClient.class.getDeclaredField("remoteUrlListener");
        f.setAccessible(true);
        return (Consumer<String>) f.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> getErrorListenerField(CopilotClient client) throws Exception {
        Field f = CopilotClient.class.getDeclaredField("remoteErrorListener");
        f.setAccessible(true);
        return (Consumer<String>) f.get(client);
    }
}
