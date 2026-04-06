package com.github.catatafishen.agentbridge.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentRegistry")
class AgentRegistryTest {

    @Test
    @DisplayName("contains all four ACP agents")
    void containsAllAgents() {
        List<AgentRegistry.AgentDescriptor> all = AgentRegistry.getAll();
        assertEquals(4, all.size());

        List<String> ids = all.stream().map(AgentRegistry.AgentDescriptor::id).toList();
        assertTrue(ids.contains("copilot"));
        assertTrue(ids.contains("junie"));
        assertTrue(ids.contains("kiro"));
        assertTrue(ids.contains("opencode"));
    }

    @Test
    @DisplayName("preserves display order")
    void preservesOrder() {
        List<AgentRegistry.AgentDescriptor> all = AgentRegistry.getAll();
        assertEquals("copilot", all.get(0).id());
        assertEquals("junie", all.get(1).id());
        assertEquals("kiro", all.get(2).id());
        assertEquals("opencode", all.get(3).id());
    }

    @Test
    @DisplayName("get returns descriptor by ID")
    void getById() {
        AgentRegistry.AgentDescriptor copilot = AgentRegistry.get("copilot");
        assertNotNull(copilot);
        assertEquals("GitHub Copilot", copilot.displayName());
    }

    @Test
    @DisplayName("get returns null for unknown ID")
    void getUnknown() {
        assertNull(AgentRegistry.get("unknown_agent"));
    }

    @Test
    @DisplayName("each agent has a non-null factory")
    void factoriesExist() {
        for (AgentRegistry.AgentDescriptor desc : AgentRegistry.getAll()) {
            assertNotNull(desc.factory(), desc.id() + " should have a factory");
        }
    }

    @Test
    @DisplayName("getAll returns unmodifiable list")
    void unmodifiableList() {
        List<AgentRegistry.AgentDescriptor> all = AgentRegistry.getAll();
        assertThrows(UnsupportedOperationException.class, () -> all.add(null));
    }
}
