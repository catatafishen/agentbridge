package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AgentProcessRegistry")
class AgentProcessRegistryTest {

    /** Clear the static PROCESSES set between tests to avoid cross-test pollution. */
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
        Process p = mock(Process.class);
        when(p.isAlive()).thenReturn(true);

        AgentProcessRegistry.register(p);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            acpMock.verify(() -> AcpClient.destroyProcessTree(p));
        }
    }

    @Test
    void unregisterRemovesProcessFromTracking() throws Exception {
        Process p = mock(Process.class);

        AgentProcessRegistry.register(p);
        AgentProcessRegistry.unregister(p);

        assertFalse(getProcesses().contains(p));
    }

    @Test
    void killAllWithMixedAliveAndDeadProcesses() throws Exception {
        Process alive = mock(Process.class);
        when(alive.isAlive()).thenReturn(true);

        Process dead = mock(Process.class);
        when(dead.isAlive()).thenReturn(false);

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
        Process p = mock(Process.class);
        when(p.isAlive()).thenReturn(true);

        AgentProcessRegistry.register(p);
        AgentProcessRegistry.unregister(p);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            acpMock.verify(() -> AcpClient.destroyProcessTree(any()), never());
        }
    }

    @Test
    void killAllIsIdempotent() throws Exception {
        Process p = mock(Process.class);
        // First call: alive → destroyed; second call: dead → skipped.
        when(p.isAlive()).thenReturn(true).thenReturn(false);

        AgentProcessRegistry.register(p);

        try (MockedStatic<AcpClient> acpMock = mockStatic(AcpClient.class)) {
            invokeKillAll();
            invokeKillAll();
            // Only one actual destroy because second call sees isAlive() == false
            acpMock.verify(() -> AcpClient.destroyProcessTree(p), times(1));
        }
    }

    @Test
    void unregisterNullIsHandledGracefully() {
        assertDoesNotThrow(() -> AgentProcessRegistry.unregister(null));
    }
}
