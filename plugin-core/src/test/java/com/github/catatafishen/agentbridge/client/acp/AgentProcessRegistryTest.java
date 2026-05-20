package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@DisplayName("AgentProcessRegistry")
class AgentProcessRegistryTest {

    /**
     * Clear the static PROCESSES set between tests to avoid cross-test pollution.
     */
    @AfterEach
    void cleanup() throws Exception {
        getProcesses().clear();
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Set<Process> getProcesses() throws Exception {
        Field f = AgentProcessRegistry.class.getDeclaredField("PROCESSES");
        f.setAccessible(true);
        return (Set<Process>) f.get(null);
    }

    private void invokeKillAll() throws Exception {
        Method killAll = AgentProcessRegistry.class.getDeclaredMethod("killAll");
        killAll.setAccessible(true);
        killAll.invoke(null);
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    void registerAndKillAllKillsAliveProcess() throws Exception {
        Process p = new TestProcess(true);

        AgentProcessRegistry.register(p);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            acpMock.verify(() -> AcpClient.destroyProcessTree(p));
        }
    }

    @Test
    void unregisterRemovesProcessFromTracking() throws Exception {
        Process p = new TestProcess(true);

        AgentProcessRegistry.register(p);
        AgentProcessRegistry.unregister(p);

        assertFalse(getProcesses().contains(p));
    }

    @Test
    void killAllWithMixedAliveAndDeadProcesses() throws Exception {
        Process alive = new TestProcess(true);
        Process dead = new TestProcess(false);

        AgentProcessRegistry.register(alive);
        AgentProcessRegistry.register(dead);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            acpMock.verify(() -> AcpClient.destroyProcessTree(alive));
            acpMock.verify(() -> AcpClient.destroyProcessTree(dead), never());
        }
    }

    @Test
    void registerNullThrowsNullPointerException() {
        // ConcurrentHashMap.KeySetView does not allow null elements.
        assertThrows(NullPointerException.class, () -> AgentProcessRegistry.register(null));
    }

    @Test
    void killAllAfterUnregisterDoesNotKillRemovedProcess() throws Exception {
        Process p = new TestProcess(true);

        AgentProcessRegistry.register(p);
        AgentProcessRegistry.unregister(p);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            acpMock.verify(() -> AcpClient.destroyProcessTree(any()), never());
        }
    }

    @Test
    void killAllIsIdempotent() throws Exception {
        // First call: alive → destroyed; second call: dead → skipped.
        TestProcess p = new TestProcess(true);

        AgentProcessRegistry.register(p);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            p.setAlive(false);
            invokeKillAll();
            // Only one actual destroy because second call sees isAlive() == false
            acpMock.verify(() -> AcpClient.destroyProcessTree(p), times(1));
        }
    }

    @Test
    void unregisterNullIsHandledGracefully() {
        assertDoesNotThrow(() -> AgentProcessRegistry.unregister(null));
    }

    // ── Test stub ───────────────────────────────────────────────────────

    /**
     * Concrete Process stub replacing {@code Mockito.mock(Process.class)} for JBR 25 compatibility.
     * Mockito cannot bytecode-instrument JDK classes like {@link Process} on JBR 25.
     */
    private static final class TestProcess extends Process {
        private volatile boolean alive;

        TestProcess(boolean alive) {
            this.alive = alive;
        }

        void setAlive(boolean alive) {
            this.alive = alive;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return alive ? -1 : 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }
    }
}
