package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card #111 harness — OpenCode v2 inline subagent definition.
 *
 * <p>The agent dropdown advertises an "Explore" subagent, but nothing ever defined
 * it for OpenCode v2: the bundled ide-explore.md was written for v1's deploy-to-
 * directory mechanism and never deployed to a v2-readable location, so every task
 * started with the explore agent failed. The fix defines the agent inline in the
 * OPENCODE_CONFIG_CONTENT launch config (v2 config schema supports an
 * {@code agents} map merged from that env var), so the definition travels with
 * the process and needs no files in the user's project.
 */
class OpenCodeInlineAgentConfigTest {

    private static final int MCP_PORT = 8642;

    private static JsonObject configFor(String version) {
        Map<String, String> env = OpenCodeClient.buildInitConfig(version, MCP_PORT);
        String content = env.get("OPENCODE_CONFIG_CONTENT");
        assertNotNull(content, "OPENCODE_CONFIG_CONTENT must always be set");
        return JsonParser.parseString(content).getAsJsonObject();
    }

    @Test
    void v2GetsInlineExploreSubAgent() {
        JsonObject config = configFor("2.0.27");
        assertTrue(config.has("agents"), "v2 config must define agents inline");

        JsonObject explore = config.getAsJsonObject("agents").getAsJsonObject("explore");
        assertNotNull(explore);
        assertEquals("subagent", explore.get("mode").getAsString());
        assertTrue(explore.get("description").getAsString().contains("read-only"));
        // No model pin: the subagent inherits the session model.
        assertNull(explore.get("model"), "explore must inherit the session model");

        // v2 Permission.Ruleset: an ordered array of {action, resource, effect} rules.
        assertTrue(explore.get("permissions").isJsonArray(),
            "v2 permissions must be a Ruleset array, got: " + explore.get("permissions"));
        var rules = explore.getAsJsonArray("permissions");
        // Exact rule count: 1 execute + 18 read-only MCP allows + 7 mutating MCP denies
        // + 3 native denies (edit, shell, question). Pinning the exact number so a
        // dropped category fails.
        assertEquals(29, rules.size(), "expected the full rule list, got " + rules.size());

        // First rule enables execute — the only reachability path for MCP tools
        // in v2 Code Mode.
        JsonObject first = rules.get(0).getAsJsonObject();
        assertEquals("execute", first.get("action").getAsString());
        assertEquals("allow", first.get("effect").getAsString());
        assertEquals("*", first.get("resource").getAsString());

        // Spot-check rules by action: read-only MCP tools allowed, mutating ones
        // and mutating native tools denied.
        assertEquals("allow", effectOf(rules, "agentbridge_read_file"));
        assertEquals("allow", effectOf(rules, "agentbridge_search_symbols"));
        // All 7 mutating plugin MCP tools must be denied, not just a sample.
        for (String mutating : List.of(
                "agentbridge_write_file", "agentbridge_edit_text", "agentbridge_delete_file",
                "agentbridge_git_commit", "agentbridge_git_stage", "agentbridge_run_command",
                "agentbridge_build_project")) {
            assertEquals("deny", effectOf(rules, mutating), mutating + " must be denied");
        }
        assertEquals("deny", effectOf(rules, "edit"));
        assertEquals("deny", effectOf(rules, "shell"));
        assertEquals("deny", effectOf(rules, "question"));
        // write/patch/bash are not v2 actions (edit covers the write and patch tools,
        // bash was renamed to shell), so they must not appear as rules.
        assertNull(effectOf(rules, "write"));
        assertNull(effectOf(rules, "patch"));
        assertNull(effectOf(rules, "bash"));
        // Allowlist semantics: native read tools are intentionally NOT listed
        // (unlisted = not exposed to the subagent).
        assertNull(effectOf(rules, "read"));
        assertNull(effectOf(rules, "bash_mcp"));

        // v2 ConfigAgent.Info.system is a plain string carrying the bundled
        // explore prompt, with its v1 frontmatter stripped.
        assertTrue(explore.get("system").isJsonPrimitive(),
            "system must be a string, got: " + explore.get("system"));
        String prompt = explore.get("system").getAsString();
        assertFalse(prompt.startsWith("---"), "frontmatter must be stripped, got: "
            + prompt.substring(0, Math.min(40, prompt.length())));
        assertTrue(prompt.contains("read-only"), "bundled explore prompt expected");
        assertFalse(prompt.contains("model:"), "v1 model pin must not leak into the prompt");
    }

    /** Effect of the LAST rule matching the action (rules are an ordered list). */
    private static String effectOf(com.google.gson.JsonArray rules, String action) {
        String effect = null;
        for (var e : rules) {
            JsonObject r = e.getAsJsonObject();
            if (action.equals(r.get("action").getAsString())) {
                effect = r.get("effect").getAsString();
            }
        }
        return effect;
    }

    @Test
    void v2GetsTopLevelPermissionsRuleset() {
        JsonObject config = configFor("2.0.27");
        assertTrue(config.has("permissions"), "v2 must get the permissions ruleset");
        assertTrue(config.get("permissions").isJsonArray(),
            "v2 permissions must be a Ruleset array, got: " + config.get("permissions"));

        var rules = config.getAsJsonArray("permissions");
        assertEquals(OpenCodeClient.v2ActionsToDeny().size(), rules.size());
        for (String action : OpenCodeClient.v2ActionsToDeny()) {
            assertEquals("deny", effectOf(rules, action), action + " must be denied");
        }
        // question denied so agents ask through the plugin's prompt_user MCP tool.
        assertEquals("deny", effectOf(rules, "question"));
    }

    @Test
    void v2DeclaresAgentBridgeMcpServerInConfig() {
        JsonObject server = configFor("2.2.5").getAsJsonObject("mcp")
            .getAsJsonObject("servers").getAsJsonObject("agentbridge");
        assertEquals("remote", server.get("type").getAsString());
        assertEquals("http://127.0.0.1:" + MCP_PORT + "/mcp", server.get("url").getAsString());
        assertFalse(server.get("oauth").getAsBoolean(), "loopback endpoint must not start OAuth");
    }

    @Test
    void v2SessionParamsCarryEmptyMcpServersNotMissing() {
        JsonObject params = new JsonObject();
        OpenCodeClient.applySessionMcpServers("2.2.5", MCP_PORT, params);
        assertTrue(params.has("mcpServers"), "v2 rejects session/new without mcpServers (-32602)");
        assertEquals(0, params.getAsJsonArray("mcpServers").size(),
            "AgentBridge is declared in the launch config, so it must not be registered twice");
    }

    @Test
    void v1AndUnknownSessionParamsStillRegisterAgentBridge() {
        for (String version : new String[] {"1.19.0", null}) {
            JsonObject params = new JsonObject();
            OpenCodeClient.applySessionMcpServers(version, MCP_PORT, params);
            var servers = params.getAsJsonArray("mcpServers");
            assertEquals(1, servers.size(), "version: " + version);
            assertEquals("agentbridge", servers.get(0).getAsJsonObject().get("name").getAsString());
        }
    }

    @Test
    void v1AndUnknownVersionsGetNoMcpInConfig() {
        for (String version : new String[] {"1.19.0", null}) {
            assertFalse(configFor(version).has("mcp"),
                "only a confirmed v2 declares MCP in the launch config, version: " + version);
        }
    }

    @Test
    void v1AndUnknownVersionsGetNoPermissionsRuleset() {
        for (String version : new String[] {"1.19.0", null}) {
            JsonObject config = configFor(version);
            assertFalse(config.has("permissions"),
                "only a confirmed v2 gets the permissions ruleset, version: " + version);
        }
    }

    @Test
    void v1ConfigIsUntouched() {
        JsonObject config = configFor("1.19.0");
        assertFalse(config.has("agents"),
            "v1 must not receive the v2 inline agents key (unknown keys may be rejected)");
        // The pre-existing deny list is still present in every variant.
        assertEquals("deny", config.getAsJsonObject("permission").get("bash").getAsString());
    }

    @Test
    void unknownVersionFailsSafe() {
        // An unresolvable version must leave the config exactly as before the
        // change: deny list only, no agents key (v1 installs must not break).
        JsonObject config = configFor(null);
        assertFalse(config.has("agents"));
        assertEquals("deny", config.getAsJsonObject("permission").get("grep").getAsString());
    }

    @Test
    void legacyBuildPermissionConfigStillAvailable() {
        // Existing behavior (pinned by OpenCodeClientBehaviorTest) is unchanged.
        Map<String, String> env = OpenCodeClient.buildPermissionConfig();
        JsonObject config = JsonParser.parseString(env.get("OPENCODE_CONFIG_CONTENT")).getAsJsonObject();
        assertFalse(config.has("agents"));
        assertFalse(config.has("default_agent"));
        assertEquals("deny", config.getAsJsonObject("permission").get("edit").getAsString());
    }

    @Test
    void stripFrontmatterRemovesFenceAndKeepsBody() {
        String prompt = OpenCodeClient.stripFrontmatter(
            "---\nname: explore\nmodel: pinned\n---\n\nYou are a read-only explorer.\n");
        assertEquals("You are a read-only explorer.", prompt);
    }

    @Test
    void stripFrontmatterUnclosedFenceFailsSafe() {
        assertNull(OpenCodeClient.stripFrontmatter("---\nname: explore\nmodel: pinned\n"),
            "an unclosed frontmatter fence must yield no prompt (no inline agent)");
    }

    @Test
    void stripFrontmatterKeepsBodyHorizontalRules() {
        // A '---' line in the BODY (markdown horizontal rule) must survive; only a
        // line before the closing fence ends the frontmatter.
        String prompt = OpenCodeClient.stripFrontmatter(
            "---\nname: explore\n---\nIntro\n\n---\n\nAfter the rule\n");
        assertEquals("Intro\n\n---\n\nAfter the rule", prompt);
    }

    @Test
    void stripFrontmatterWithoutFrontmatterIsTrimmed() {
        assertEquals("Just a prompt", OpenCodeClient.stripFrontmatter("  Just a prompt  "));
        assertNull(OpenCodeClient.stripFrontmatter("   "), "blank content yields no prompt");
    }
}
