package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the static helpers in {@link VibeClient}.
 */
class VibeClientStaticMethodsTest {

    // ── stripToolPrefix ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("stripToolPrefix")
    class StripToolPrefix {

        @Test
        @DisplayName("strips agentbridge_ prefix")
        void stripsPrefix() {
            assertEquals("read_file", VibeClient.stripToolPrefix("agentbridge_read_file"));
        }

        @Test
        @DisplayName("strips prefix leaving empty string")
        void stripsPrefixLeavingEmpty() {
            assertEquals("", VibeClient.stripToolPrefix("agentbridge_"));
        }

        @Test
        @DisplayName("no prefix returns unchanged")
        void noPrefixUnchanged() {
            assertEquals("read_file", VibeClient.stripToolPrefix("read_file"));
        }

        @Test
        @DisplayName("empty string returns empty")
        void emptyString() {
            assertEquals("", VibeClient.stripToolPrefix(""));
        }

        @Test
        @DisplayName("partial prefix not stripped")
        void partialPrefixNotStripped() {
            assertEquals("agentbridge", VibeClient.stripToolPrefix("agentbridge"));
        }
    }

    // ── hasToolPrefix ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasToolPrefix")
    class HasToolPrefix {

        @Test
        @DisplayName("returns true for agentbridge_ prefix")
        void trueWithPrefix() {
            assertTrue(VibeClient.hasToolPrefix("agentbridge_read_file"));
        }

        @Test
        @DisplayName("returns false without prefix")
        void falseWithoutPrefix() {
            assertFalse(VibeClient.hasToolPrefix("read_file"));
        }

        @Test
        @DisplayName("returns true for prefix-only string")
        void trueForPrefixOnly() {
            assertTrue(VibeClient.hasToolPrefix("agentbridge_"));
        }

        @Test
        @DisplayName("returns false for empty string")
        void falseForEmpty() {
            assertFalse(VibeClient.hasToolPrefix(""));
        }

        @Test
        @DisplayName("returns false for mcp_agentbridge_ prefix (Hermes format)")
        void falseForHermesPrefix() {
            assertFalse(VibeClient.hasToolPrefix("mcp_agentbridge_read_file"));
        }
    }

    // ── addMcpServerConfig ───────────────────────────────────────────────────

    @Nested
    @DisplayName("addMcpServerConfig")
    class AddMcpServerConfig {

        @Test
        @DisplayName("adds mcpServers array with correct server entry for port 3000")
        void port3000() {
            JsonObject params = new JsonObject();

            VibeClient.addMcpServerConfig(3000, params);

            assertTrue(params.has("mcpServers"));
            JsonArray servers = params.getAsJsonArray("mcpServers");
            assertEquals(1, servers.size());

            JsonObject server = servers.get(0).getAsJsonObject();
            assertEquals("http", server.get("type").getAsString());
            assertEquals("agentbridge", server.get("name").getAsString());
            assertEquals("http://127.0.0.1:3000/mcp", server.get("url").getAsString());
            assertTrue(server.has("headers"));
            assertEquals(0, server.getAsJsonArray("headers").size());
        }

        @Test
        @DisplayName("uses different port in URL")
        void port8080() {
            JsonObject params = new JsonObject();

            VibeClient.addMcpServerConfig(8080, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            assertEquals("http://127.0.0.1:8080/mcp", server.get("url").getAsString());
        }
    }
}
