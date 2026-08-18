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

    // ── buildReprimandText ───────────────────────────────────────────────────

    @Nested
    @DisplayName("buildReprimandText")
    class BuildReprimandText {

        @Test
        @DisplayName("write tool → mentions agentbridge_write_file and 'path' param")
        void writeTool() {
            String msg = VibeClient.buildReprimandText("write");
            assertTrue(msg.contains("agentbridge_write_file"));
            assertTrue(msg.contains("'path'"));
        }

        @Test
        @DisplayName("edit tool → same as write (write/edit/create share a branch)")
        void editTool() {
            String msg = VibeClient.buildReprimandText("edit");
            assertTrue(msg.contains("agentbridge_write_file"));
        }

        @Test
        @DisplayName("create tool → same as write")
        void createTool() {
            String msg = VibeClient.buildReprimandText("create");
            assertTrue(msg.contains("agentbridge_write_file"));
        }

        @Test
        @DisplayName("read tool → mentions agentbridge_read_file and 'path' param")
        void readTool() {
            String msg = VibeClient.buildReprimandText("read");
            assertTrue(msg.contains("agentbridge_read_file"));
            assertTrue(msg.contains("'path'"));
        }

        @Test
        @DisplayName("view tool → same as read")
        void viewTool() {
            String msg = VibeClient.buildReprimandText("view");
            assertTrue(msg.contains("agentbridge_read_file"));
        }

        @Test
        @DisplayName("bash tool → mentions agentbridge_run_command")
        void bashTool() {
            String msg = VibeClient.buildReprimandText("bash");
            assertTrue(msg.contains("agentbridge_run_command"));
        }

        @Test
        @DisplayName("grep tool → mentions agentbridge_search_text")
        void grepTool() {
            String msg = VibeClient.buildReprimandText("grep");
            assertTrue(msg.contains("agentbridge_search_text"));
        }

        @Test
        @DisplayName("glob tool → mentions agentbridge_list_project_files")
        void globTool() {
            String msg = VibeClient.buildReprimandText("glob");
            assertTrue(msg.contains("agentbridge_list_project_files"));
        }

        @Test
        @DisplayName("unknown tool → generic message mentioning agentbridge_* prefix")
        void unknownTool() {
            String msg = VibeClient.buildReprimandText("some_tool");
            assertTrue(msg.contains("some_tool"));
            assertTrue(msg.contains("agentbridge_*"));
        }
    }
}
