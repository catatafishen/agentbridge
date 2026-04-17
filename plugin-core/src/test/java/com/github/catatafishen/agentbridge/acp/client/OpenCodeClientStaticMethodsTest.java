package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the package-private static helpers in {@link OpenCodeClient}.
 */
class OpenCodeClientStaticMethodsTest {

    // ── stripToolPrefix ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("stripToolPrefix")
    class StripToolPrefix {

        @Test
        @DisplayName("strips agentbridge_ prefix")
        void stripsPrefix() {
            assertEquals("read_file", OpenCodeClient.stripToolPrefix("agentbridge_read_file"));
        }

        @Test
        @DisplayName("strips prefix leaving empty string")
        void stripsPrefixLeavingEmpty() {
            assertEquals("", OpenCodeClient.stripToolPrefix("agentbridge_"));
        }

        @Test
        @DisplayName("no prefix returns unchanged")
        void noPrefixUnchanged() {
            assertEquals("read_file", OpenCodeClient.stripToolPrefix("read_file"));
        }

        @Test
        @DisplayName("empty string returns empty")
        void emptyString() {
            assertEquals("", OpenCodeClient.stripToolPrefix(""));
        }
    }

    // ── hasToolPrefix ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasToolPrefix")
    class HasToolPrefix {

        @Test
        @DisplayName("returns true for agentbridge_ prefix")
        void trueWithPrefix() {
            assertTrue(OpenCodeClient.hasToolPrefix("agentbridge_read_file"));
        }

        @Test
        @DisplayName("returns false without prefix")
        void falseWithoutPrefix() {
            assertFalse(OpenCodeClient.hasToolPrefix("read_file"));
        }

        @Test
        @DisplayName("returns true for prefix only")
        void trueForPrefixOnly() {
            assertTrue(OpenCodeClient.hasToolPrefix("agentbridge_"));
        }

        @Test
        @DisplayName("returns false for empty string")
        void falseForEmpty() {
            assertFalse(OpenCodeClient.hasToolPrefix(""));
        }
    }

    // ── buildPermissionConfig ────────────────────────────────────────────────

    @Nested
    @DisplayName("buildPermissionConfig")
    class BuildPermissionConfig {

        @Test
        @DisplayName("returns map with single OPENCODE_CONFIG_CONTENT key")
        void singleKey() {
            Map<String, String> result = OpenCodeClient.buildPermissionConfig();

            assertEquals(1, result.size());
            assertTrue(result.containsKey("OPENCODE_CONFIG_CONTENT"));
        }

        @Test
        @DisplayName("JSON contains permission object with all native tools set to deny")
        void permissionDenyEntries() {
            Map<String, String> result = OpenCodeClient.buildPermissionConfig();
            String json = result.get("OPENCODE_CONFIG_CONTENT");

            JsonObject config = new Gson().fromJson(json, JsonObject.class);
            assertTrue(config.has("permission"));

            JsonObject permission = config.getAsJsonObject("permission");
            for (String tool : OpenCodeClient.nativeToolsToDeny()) {
                assertTrue(permission.has(tool), "Missing tool: " + tool);
                assertEquals("deny", permission.get(tool).getAsString(), "Tool not denied: " + tool);
            }
        }

        @Test
        @DisplayName("native tools list has 8 entries")
        void nativeToolsCount() {
            assertEquals(14, OpenCodeClient.nativeToolsToDeny().size());
        }

        @Test
        @DisplayName("config does NOT include default_agent (rejected by OpenCode v1.4.10+)")
        void noDefaultAgent() {
            Map<String, String> result = OpenCodeClient.buildPermissionConfig();
            JsonObject config = new Gson().fromJson(result.get("OPENCODE_CONFIG_CONTENT"), JsonObject.class);

            assertFalse(config.has("default_agent"),
                "default_agent must not be set — OpenCode v1.4.10+ rejects subagent slugs");
        }
    }

    @Nested
    @DisplayName("builtInAgents")
    class BuiltInAgents {

        @Test
        @DisplayName("returns OpenCode's 4 native agents in display order")
        void returnsNativeAgents() {
            var agents = OpenCodeClient.builtInAgents();

            assertEquals(4, agents.size());
            assertEquals("build", agents.get(0).slug());
            assertEquals("plan", agents.get(1).slug());
            assertEquals("general", agents.get(2).slug());
            assertEquals("explore", agents.get(3).slug());
            assertEquals("Build", agents.get(0).name());
            assertEquals("Plan", agents.get(1).name());
        }
    }

    // ── extractTaskSubAgentType ──────────────────────────────────────────────

    @Nested
    @DisplayName("extractTaskSubAgentType")
    class ExtractTaskSubAgentType {

        @Test
        @DisplayName("returns subagent_type from rawInput")
        void returnsExplore() {
            JsonObject params = new JsonObject();
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("subagent_type", "explore");
            params.add("rawInput", rawInput);

            assertEquals("explore", OpenCodeClient.extractTaskSubAgentType(params));
        }

        @Test
        @DisplayName("returns 'task' subagent_type")
        void returnsTask() {
            JsonObject params = new JsonObject();
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("subagent_type", "task");
            params.add("rawInput", rawInput);

            assertEquals("task", OpenCodeClient.extractTaskSubAgentType(params));
        }

        @Test
        @DisplayName("empty rawInput returns 'general'")
        void emptyRawInput() {
            JsonObject params = new JsonObject();
            params.add("rawInput", new JsonObject());

            assertEquals("general", OpenCodeClient.extractTaskSubAgentType(params));
        }

        @Test
        @DisplayName("no rawInput returns 'general'")
        void noRawInput() {
            JsonObject params = new JsonObject();

            assertEquals("general", OpenCodeClient.extractTaskSubAgentType(params));
        }

        @Test
        @DisplayName("rawInput as string (not object) returns 'general'")
        void rawInputAsString() {
            JsonObject params = new JsonObject();
            params.addProperty("rawInput", "string");

            assertEquals("general", OpenCodeClient.extractTaskSubAgentType(params));
        }
    }

    // ── extractRawInputArgs ──────────────────────────────────────────────────

    @Nested
    @DisplayName("extractRawInputArgs")
    class ExtractRawInputArgs {

        @Test
        @DisplayName("returns rawInput object with entries")
        void returnsRawInputWithEntries() {
            JsonObject params = new JsonObject();
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("file", "test.java");
            rawInput.addProperty("line", 5);
            params.add("rawInput", rawInput);

            JsonObject result = OpenCodeClient.extractRawInputArgs(params);

            assertNotNull(result);
            assertEquals(2, result.entrySet().size());
            assertEquals("test.java", result.get("file").getAsString());
            assertEquals(5, result.get("line").getAsInt());
        }

        @Test
        @DisplayName("empty rawInput returns null")
        void emptyRawInput() {
            JsonObject params = new JsonObject();
            params.add("rawInput", new JsonObject());

            assertNull(OpenCodeClient.extractRawInputArgs(params));
        }

        @Test
        @DisplayName("no rawInput returns null")
        void noRawInput() {
            JsonObject params = new JsonObject();

            assertNull(OpenCodeClient.extractRawInputArgs(params));
        }

        @Test
        @DisplayName("rawInput as string returns null")
        void rawInputAsString() {
            JsonObject params = new JsonObject();
            params.addProperty("rawInput", "string");

            assertNull(OpenCodeClient.extractRawInputArgs(params));
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

            OpenCodeClient.addMcpServerConfig(3000, params);

            assertTrue(params.has("mcpServers"));
            JsonArray servers = params.getAsJsonArray("mcpServers");
            assertEquals(1, servers.size());

            JsonObject server = servers.get(0).getAsJsonObject();
            assertEquals("agentbridge", server.get("name").getAsString());
            assertEquals("http", server.get("type").getAsString());
            assertEquals("http://127.0.0.1:3000/mcp", server.get("url").getAsString());
            assertTrue(server.get("headers").isJsonArray());
            assertEquals(0, server.getAsJsonArray("headers").size());
        }

        @Test
        @DisplayName("uses different port in URL")
        void port8080() {
            JsonObject params = new JsonObject();

            OpenCodeClient.addMcpServerConfig(8080, params);

            JsonObject server = params.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
            assertEquals("http://127.0.0.1:8080/mcp", server.get("url").getAsString());
        }
    }
}
