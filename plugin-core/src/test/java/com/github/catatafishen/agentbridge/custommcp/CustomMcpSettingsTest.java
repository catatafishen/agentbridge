package com.github.catatafishen.agentbridge.custommcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CustomMcpSettings} — persistent MCP server configuration.
 * Constructs CustomMcpSettings directly (no Project needed for these tests).
 */
class CustomMcpSettingsTest {

    @Test
    void getServers_initiallyEmpty() {
        CustomMcpSettings settings = new CustomMcpSettings();
        assertTrue(settings.getServers().isEmpty());
    }

    @Test
    void getServers_returnsCopy_mutatingReturnedListDoesNotAffectSettings() {
        CustomMcpSettings settings = new CustomMcpSettings();
        CustomMcpServerConfig config = new CustomMcpServerConfig(
                "id-1", "Server A", "http://localhost:3000/mcp", "instructions", true);
        settings.setServers(List.of(config));

        List<CustomMcpServerConfig> returned = settings.getServers();
        CustomMcpServerConfig extra = new CustomMcpServerConfig(
                "id-2", "Server B", "http://localhost:4000/mcp", "", true);
        assertThrows(UnsupportedOperationException.class, () -> returned.add(extra));

        // Original settings unchanged
        assertEquals(1, settings.getServers().size());
    }

    @Test
    void setServers_storesCopy_mutatingOriginalListDoesNotAffectSettings() {
        CustomMcpSettings settings = new CustomMcpSettings();
        CustomMcpServerConfig config = new CustomMcpServerConfig(
                "id-1", "Server A", "http://localhost:3000/mcp", "instructions", true);
        ArrayList<CustomMcpServerConfig> mutableList = new ArrayList<>();
        mutableList.add(config);

        settings.setServers(mutableList);

        // Mutate the original list after calling setServers
        mutableList.add(new CustomMcpServerConfig(
                "id-2", "Server B", "http://localhost:4000/mcp", "", true));

        // Settings should still have only 1 server
        assertEquals(1, settings.getServers().size());
        assertEquals("Server A", settings.getServers().get(0).getName());
    }

    @Test
    void loadState_replacesState_getServersReflectsNewState() {
        CustomMcpSettings settings = new CustomMcpSettings();
        CustomMcpServerConfig config = new CustomMcpServerConfig(
                "id-1", "Server A", "http://localhost:3000/mcp", "instructions", true);

        CustomMcpSettings.State newState = new CustomMcpSettings.State();
        newState.setServers(new ArrayList<>(List.of(config)));

        settings.loadState(newState);

        List<CustomMcpServerConfig> servers = settings.getServers();
        assertEquals(1, servers.size());
        assertEquals("Server A", servers.get(0).getName());
        assertEquals("http://localhost:3000/mcp", servers.get(0).getUrl());
    }

    @Test
    void getState_returnsNonNull() {
        CustomMcpSettings settings = new CustomMcpSettings();
        assertNotNull(settings.getState());
    }

    @Test
    void setServers_multipleServers_allPreserved() {
        CustomMcpSettings settings = new CustomMcpSettings();
        CustomMcpServerConfig a = new CustomMcpServerConfig(
                "id-a", "Alpha", "http://a/mcp", "", true);
        CustomMcpServerConfig b = new CustomMcpServerConfig(
                "id-b", "Beta", "http://b/mcp", "", false);

        settings.setServers(List.of(a, b));

        List<CustomMcpServerConfig> servers = settings.getServers();
        assertEquals(2, servers.size());
        assertEquals("Alpha", servers.get(0).getName());
        assertEquals("Beta", servers.get(1).getName());
        assertFalse(servers.get(1).isEnabled());
    }
}
