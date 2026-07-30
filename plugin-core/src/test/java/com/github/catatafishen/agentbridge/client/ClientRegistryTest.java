package com.github.catatafishen.agentbridge.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientRegistry")
class ClientRegistryTest {

    @Test
    @DisplayName("contains all six ACP agents")
    void containsAllAgents() {
        List<ClientRegistry.AgentDescriptor> all = ClientRegistry.getAll();
        assertEquals(6, all.size());

        List<String> ids = all.stream().map(ClientRegistry.AgentDescriptor::id).toList();
        assertTrue(ids.contains("copilot"));
        assertTrue(ids.contains("junie"));
        assertTrue(ids.contains("kiro"));
        assertTrue(ids.contains("opencode"));
        assertTrue(ids.contains("hermes"));
        assertTrue(ids.contains("vibe"));
    }

    @Test
    @DisplayName("preserves display order")
    void preservesOrder() {
        List<ClientRegistry.AgentDescriptor> all = ClientRegistry.getAll();
        assertEquals("copilot", all.get(0).id());
        assertEquals("junie", all.get(1).id());
        assertEquals("kiro", all.get(2).id());
        assertEquals("opencode", all.get(3).id());
        assertEquals("hermes", all.get(4).id());
        assertEquals("vibe", all.get(5).id());
    }

    @Test
    @DisplayName("get returns descriptor by ID")
    void getById() {
        ClientRegistry.AgentDescriptor copilot = ClientRegistry.get("copilot");
        assertNotNull(copilot);
        assertEquals("GitHub Copilot", copilot.displayName());
    }

    @Test
    @DisplayName("get returns null for unknown ID")
    void getUnknown() {
        assertNull(ClientRegistry.get("unknown_agent"));
    }

    @Test
    @DisplayName("each agent has a non-null factory")
    void factoriesExist() {
        for (ClientRegistry.AgentDescriptor desc : ClientRegistry.getAll()) {
            assertNotNull(desc.factory(), desc.id() + " should have a factory");
        }
    }

    @Test
    @DisplayName("getAll returns unmodifiable list")
    void unmodifiableList() {
        List<ClientRegistry.AgentDescriptor> all = ClientRegistry.getAll();
        assertThrows(UnsupportedOperationException.class, () -> all.add(null));
    }
}
