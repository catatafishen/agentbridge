package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
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
    @DisplayName("v2ActionsToDeny")
    class V2ActionsToDeny {

        @Test
        @DisplayName("v2 action list has 8 entries with v2 names only")
        void v2ActionNames() {
            List<String> actions = OpenCodeClient.v2ActionsToDeny();

            assertEquals(8, actions.size());
            assertTrue(actions.contains("shell"), "v2 renamed bash to shell");
            assertTrue(actions.contains("question"), "question is denied in favor of prompt_user");
            // v1-only names must not leak into the v2 ruleset.
            for (String v1Name : List.of("bash", "write", "patch", "ls", "lsp", "codesearch", "todoread", "todowrite")) {
                assertFalse(actions.contains(v1Name), v1Name + " is not a v2 action");
            }
        }
    }

    @Nested
    @DisplayName("builtInAgents")
    class BuiltInAgents {

        @Test
        @DisplayName("returns OpenCode's 5 native agents in display order")
        void returnsNativeAgents() {
            var agents = OpenCodeClient.builtInAgents();

            assertEquals(5, agents.size());
            assertEquals("build", agents.get(0).slug());
            assertEquals("plan", agents.get(1).slug());
            assertEquals("general", agents.get(2).slug());
            assertEquals("explore", agents.get(3).slug());
            assertEquals("scout", agents.get(4).slug());
            assertEquals("Build", agents.get(0).name());
            assertEquals("Plan", agents.get(1).name());
            assertEquals("Scout", agents.get(4).name());
        }
    }

    @Nested
    @DisplayName("builtInAgentsFor")
    class BuiltInAgentsFor {

        @Test
        @DisplayName("v2 keeps only the primary session modes (build, plan)")
        void v2KeepsOnlyPrimaryModes() {
            var agents = OpenCodeClient.builtInAgentsFor("2.0.27");

            assertEquals(2, agents.size());
            assertEquals("build", agents.get(0).slug());
            assertEquals("plan", agents.get(1).slug());
            // general/explore are Task-tool subagents on v2 (rejected with -32602 mode
            // not found as primaries); scout does not exist on v2 at all.
            assertTrue(agents.stream().noneMatch(a -> "scout".equals(a.slug())),
                "scout must not be advertised on v2 - it cannot be launched");
            assertTrue(agents.stream().noneMatch(a -> "general".equals(a.slug())),
                "general must not be advertised as a primary on v2");
            assertTrue(agents.stream().noneMatch(a -> "explore".equals(a.slug())),
                "explore must not be advertised as a primary on v2");
        }

        @Test
        @DisplayName("unknown or v1 versions keep the full list (conservative default)")
        void unknownOrV1KeepsScout() {
            assertEquals(5, OpenCodeClient.builtInAgentsFor(null).size());
            assertEquals(5, OpenCodeClient.builtInAgentsFor("").size());
            assertEquals(5, OpenCodeClient.builtInAgentsFor("1.4.10").size());
            assertTrue(OpenCodeClient.builtInAgentsFor("1.4.10").stream()
                .anyMatch(a -> "scout".equals(a.slug())));
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
        @DisplayName("returns the 'agent' of OpenCode 2.3's subagent tool")
        void returnsSubagentToolAgent() {
            JsonObject params = new JsonObject();
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("agent", "explore");
            rawInput.addProperty("description", "Find usages");
            params.add("rawInput", rawInput);

            assertEquals("explore", OpenCodeClient.extractTaskSubAgentType(params));
        }

        @Test
        @DisplayName("subagent_type takes precedence over agent")
        void subagentTypeBeforeAgent() {
            JsonObject params = new JsonObject();
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("agent", "general");
            rawInput.addProperty("subagent_type", "explore");
            params.add("rawInput", rawInput);

            assertEquals("explore", OpenCodeClient.extractTaskSubAgentType(params));
        }

        @Test
        @DisplayName("blank or non-text agent returns 'general'")
        void blankOrNonTextAgent() {
            JsonObject blank = new JsonObject();
            JsonObject blankInput = new JsonObject();
            blankInput.addProperty("agent", " ");
            blank.add("rawInput", blankInput);
            JsonObject nested = new JsonObject();
            JsonObject nestedInput = new JsonObject();
            nestedInput.add("agent", new JsonObject());
            nested.add("rawInput", nestedInput);

            assertEquals("general", OpenCodeClient.extractTaskSubAgentType(blank));
            assertEquals("general", OpenCodeClient.extractTaskSubAgentType(nested));
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

    @Nested
    @DisplayName("mergeAgents")
    class MergeAgents {

        private static AbstractClient.AgentMode agent(String slug, String name) {
            return new AbstractClient.AgentMode(slug, name, null);
        }

        @Test
        @DisplayName("lists a global custom agent alongside built-ins and project agents")
        void globalAgentAppears() {
            var merged = OpenCodeClient.mergeAgents(
                List.of(agent("build", "Build"), agent("plan", "Plan")),
                List.of(agent("global-test", "Global Test")),
                List.of(agent("project-test", "Project Test"))
            );
            assertEquals(
                List.of("build", "plan", "global-test", "project-test"),
                merged.stream().map(AbstractClient.AgentMode::slug).toList());
        }

        @Test
        @DisplayName("project agent with the same slug as a global one wins")
        void projectOverridesGlobal() {
            var merged = OpenCodeClient.mergeAgents(
                List.of(agent("build", "Build")),
                List.of(agent("shared", "Global Shared")),
                List.of(agent("shared", "Project Shared"))
            );
            assertEquals(List.of("build", "shared"), merged.stream().map(AbstractClient.AgentMode::slug).toList());
            assertEquals("Project Shared", merged.get(1).name());
        }

        @Test
        @DisplayName("built-ins are never shadowed by same-slug discoveries")
        void builtInsNotShadowed() {
            var merged = OpenCodeClient.mergeAgents(
                List.of(agent("build", "Build")),
                List.of(agent("build", "Fake Build")),
                List.of(agent("build", "Other Build"))
            );
            assertEquals(1, merged.size());
            assertEquals("Build", merged.getFirst().name());
        }
    }

    @Nested
    @DisplayName("globalOpenCodeAgentDirs")
    class GlobalOpenCodeAgentDirs {

        @Test
        @DisplayName("covers both global agent locations, .opencode first (it wins on collisions)")
        void coversBothGlobalDirsInPrecedenceOrder() {
            var dirs = OpenCodeClient.globalOpenCodeAgentDirs();
            assertEquals(2, dirs.size());
            Path home = Path.of(System.getProperty("user.home"));
            assertEquals(home.resolve(".opencode/agents"), dirs.get(0));
            assertEquals(home.resolve(".config/opencode/agents"), dirs.get(1));
        }
    }
}
