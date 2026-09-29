package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VibeClient}.
 *
 * <p>Tests focus on the pure static helpers (prefix stripping, reprimand text, MCP config) so
 * they can run without the IntelliJ platform or a live ACP process.</p>
 */
class VibeClientTest {

    // ─── Constants ───────────────────────────────────────────────────────────

    @Test
    void agentId_isVibe() {
        assertEquals("vibe", VibeClient.AGENT_ID);
    }

    // ─── MCP Server Config ───────────────────────────────────────────────────

    @Nested
    class McpServerConfig {

        @Test
        void addMcpServerConfig_addsServerArray() {
            JsonObject params = new JsonObject();
            VibeClient.addMcpServerConfig(8080, params);

            assertTrue(params.has("mcpServers"), "Expected mcpServers key");
            JsonArray servers = params.getAsJsonArray("mcpServers");
            assertEquals(1, servers.size());
        }

        @Test
        void addMcpServerConfig_serverHasCorrectType() {
            JsonObject params = new JsonObject();
            VibeClient.addMcpServerConfig(8080, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            assertEquals("http", server.get("type").getAsString());
        }

        @Test
        void addMcpServerConfig_serverHasCorrectName() {
            JsonObject params = new JsonObject();
            VibeClient.addMcpServerConfig(8080, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            assertEquals("agentbridge", server.get("name").getAsString());
        }

        @Test
        void addMcpServerConfig_serverHasCorrectUrl() {
            JsonObject params = new JsonObject();
            VibeClient.addMcpServerConfig(9999, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            assertEquals("http://127.0.0.1:9999/mcp", server.get("url").getAsString());
        }

        @Test
        void addMcpServerConfig_usesExplicitIpv4() {
            JsonObject params = new JsonObject();
            VibeClient.addMcpServerConfig(8080, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            String url = server.get("url").getAsString();
            assertTrue(url.contains("127.0.0.1"), "Should use 127.0.0.1, not localhost");
            assertFalse(url.contains("localhost"), "Should not use localhost");
        }

        @Test
        void addMcpServerConfig_includesEmptyHeaders() {
            JsonObject params = new JsonObject();
            VibeClient.addMcpServerConfig(8080, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            assertNotNull(server.get("headers"));
            assertTrue(server.get("headers").isJsonArray());
            assertEquals(0, server.getAsJsonArray("headers").size());
        }
    }

    // ─── Tool prefix helpers ─────────────────────────────────────────────────

    @Nested
    class ToolPrefix {

        @Test
        void hasPrefix_returnsTrueForMcpTool() {
            assertTrue(VibeClient.hasToolPrefix("agentbridge_read_file"));
        }

        @Test
        void hasPrefix_returnsFalseForNativeTool() {
            assertFalse(VibeClient.hasToolPrefix("bash"));
            assertFalse(VibeClient.hasToolPrefix("write"));
            assertFalse(VibeClient.hasToolPrefix("edit"));
        }

        @Test
        void stripPrefix_removesPrefix() {
            String result = VibeClient.stripToolPrefix("agentbridge_write_file");
            org.junit.jupiter.api.Assertions.assertEquals("write_file", result);
        }

        @Test
        void stripPrefix_noop_whenNoPrefixPresent() {
            String result = VibeClient.stripToolPrefix("bash");
            org.junit.jupiter.api.Assertions.assertEquals("bash", result);
        }
    }
}
