package com.github.catatafishen.agentbridge.custommcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CustomMcpServerConfig}.
 */
class CustomMcpServerConfigTest {

    @ParameterizedTest(name = "toolPrefix(\"{0}\") = \"{1}\"")
    @CsvSource({
        "database, cmcp_database",
        "My Custom Server, cmcp_my_custom_server",
        "'---db & tools---', cmcp_db_tools"
    })
    void toolPrefix_normalizesName(String name, String expected) {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setName(name);
        assertEquals(expected, config.toolPrefix());
    }

    @Test
    void toolPrefix_emptyName_fallsBackToIdPrefix() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setId("abcd1234-efgh");
        config.setName("");
        assertTrue(config.toolPrefix().startsWith("cmcp_"), "prefix should always start with cmcp_");
        assertNotEquals("cmcp_", config.toolPrefix(), "prefix should have non-empty suffix");
    }

    @Test
    void toolPrefix_onlySpecialChars_fallsBackToIdPrefix() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setId("abc12345-0000");
        config.setName("!@#$%");
        assertTrue(config.toolPrefix().startsWith("cmcp_"));
    }

    @Test
    void copy_isEqualButIndependent() {
        CustomMcpServerConfig original = new CustomMcpServerConfig(
            "id-1", "Server", "http://localhost:3000/mcp", "Use for DB queries", true);
        CustomMcpServerConfig copy = original.copy();
        assertEquals(original, copy);
        copy.setName("Changed");
        assertNotEquals(original, copy);
    }

    @Test
    void defaultConstructor_defaults() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        assertEquals("", config.getName());
        assertEquals("", config.getUrl());
        assertEquals("", config.getInstructions());
        assertTrue(config.isEnabled());
        assertTrue(config.isDefaultEnabled());
        assertFalse(config.getId().isEmpty());
    }

    @Test
    void equals_sameFields_isEqual() {
        CustomMcpServerConfig a = new CustomMcpServerConfig("id", "name", "url", "instr", true);
        CustomMcpServerConfig b = new CustomMcpServerConfig("id", "name", "url", "instr", true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentUrl_notEqual() {
        CustomMcpServerConfig a = new CustomMcpServerConfig("id", "name", "http://a/mcp", "instr", true);
        CustomMcpServerConfig b = new CustomMcpServerConfig("id", "name", "http://b/mcp", "instr", true);
        assertNotEquals(a, b);
    }

    // ── Type detection ───────────────────────────────────────────

    @Test
    void defaultType_isHttp() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        assertEquals(CustomMcpServerConfig.TYPE_HTTP, config.getType());
        assertTrue(config.isHttp());
        assertFalse(config.isStdio());
    }

    @Test
    void setType_stdio_setsStdio() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setType("stdio");
        assertEquals(CustomMcpServerConfig.TYPE_STDIO, config.getType());
        assertTrue(config.isStdio());
        assertFalse(config.isHttp());
    }

    @Test
    void setType_sse_preserved() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setType("sse");
        assertEquals(CustomMcpServerConfig.TYPE_SSE, config.getType());
        assertTrue(config.isHttp());
        assertFalse(config.isStdio());
    }

    @Test
    void setType_unknownValue_defaultsToHttp() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setType("tcp");
        assertEquals(CustomMcpServerConfig.TYPE_HTTP, config.getType());
        assertTrue(config.isHttp());
    }

    // ── isConfigured ─────────────────────────────────────────────

    @Test
    void isConfigured_httpWithUrl_returnsTrue() {
        CustomMcpServerConfig config = new CustomMcpServerConfig("id", "n", "http://h/mcp", "", true);
        assertTrue(config.isConfigured());
    }

    @Test
    void isConfigured_httpWithBlankUrl_returnsFalse() {
        CustomMcpServerConfig config = new CustomMcpServerConfig("id", "n", "", "", true);
        assertFalse(config.isConfigured());
    }

    @Test
    void isConfigured_stdioWithCommand_returnsTrue() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setType("stdio");
        config.setCommand("npx");
        assertTrue(config.isConfigured());
    }

    @Test
    void isConfigured_stdioWithBlankCommand_returnsFalse() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setType("stdio");
        assertFalse(config.isConfigured());
    }

    // ── getEnvironmentMap ────────────────────────────────────────

    @Test
    void getEnvironmentMap_empty_returnsEmptyMap() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        assertTrue(config.getEnvironmentMap().isEmpty());
    }

    @Test
    void getEnvironmentMap_returnsMappedEntries() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setEnvironment(List.of(
            new CustomMcpServerConfig.McpEnvVar("KEY1", "val1"),
            new CustomMcpServerConfig.McpEnvVar("KEY2", "val2")
        ));
        Map<String, String> map = config.getEnvironmentMap();
        assertEquals(2, map.size());
        assertEquals("val1", map.get("KEY1"));
        assertEquals("val2", map.get("KEY2"));
    }

    @Test
    void getEnvironmentMap_skipsBlankNames() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setEnvironment(List.of(
            new CustomMcpServerConfig.McpEnvVar("", "val1"),
            new CustomMcpServerConfig.McpEnvVar("KEY2", "val2")
        ));
        Map<String, String> map = config.getEnvironmentMap();
        assertEquals(1, map.size());
        assertFalse(map.containsKey(""));
    }

    @Test
    void getEnvironmentMap_nullValueBecomesEmpty() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setEnvironment(List.of(
            new CustomMcpServerConfig.McpEnvVar("KEY", null)
        ));
        assertEquals("", config.getEnvironmentMap().get("KEY"));
    }

    @Test
    void getHeadersMap_returnsMappedEntries() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setHeaders(List.of(
            new CustomMcpServerConfig.McpHeader("Authorization", "Bearer token"),
            new CustomMcpServerConfig.McpHeader("X-Test", "1")
        ));
        Map<String, String> map = config.getHeadersMap();
        assertEquals(2, map.size());
        assertEquals("Bearer token", map.get("Authorization"));
        assertEquals("1", map.get("X-Test"));
    }

    @Test
    void setCommandParts_splitsExecutableAndArgs() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setCommandParts(List.of("npx", "@playwright/mcp@latest", "--output-dir", "data/.playwright-mcp"));
        assertEquals("npx", config.getCommand());
        assertEquals(List.of("@playwright/mcp@latest", "--output-dir", "data/.playwright-mcp"), config.getArgs());
        assertEquals(List.of("npx", "@playwright/mcp@latest", "--output-dir", "data/.playwright-mcp"), config.getCommandParts());
    }

    @Test
    void getCommandParts_splitsLegacyCombinedCommand() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setType("stdio");
        config.setCommand("npx @playwright/mcp@latest --output-dir data/.playwright-mcp");
        assertEquals("npx", config.getEffectiveCommand());
        assertEquals(List.of("@playwright/mcp@latest", "--output-dir", "data/.playwright-mcp"), config.getEffectiveArgs());
    }

    // ── McpEnvVar ────────────────────────────────────────────────

    @Test
    void mcpEnvVar_defaultConstructor() {
        CustomMcpServerConfig.McpEnvVar v = new CustomMcpServerConfig.McpEnvVar();
        assertEquals("", v.getName());
        assertEquals("", v.getValue());
    }

    @Test
    void mcpEnvVar_parameterizedConstructor() {
        CustomMcpServerConfig.McpEnvVar v = new CustomMcpServerConfig.McpEnvVar("TOKEN", "secret");
        assertEquals("TOKEN", v.getName());
        assertEquals("secret", v.getValue());
    }

    @Test
    void mcpEnvVar_nullBecomesEmpty() {
        CustomMcpServerConfig.McpEnvVar v = new CustomMcpServerConfig.McpEnvVar(null, null);
        assertEquals("", v.getName());
        assertEquals("", v.getValue());
    }

    @Test
    void mcpEnvVar_equals_hashCode() {
        var a = new CustomMcpServerConfig.McpEnvVar("K", "V");
        var b = new CustomMcpServerConfig.McpEnvVar("K", "V");
        var c = new CustomMcpServerConfig.McpEnvVar("K", "X");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    // ── copy with new fields ─────────────────────────────────────

    @Test
    void copy_includesTypeCommandAndEnv() {
        CustomMcpServerConfig original = new CustomMcpServerConfig();
        original.setType("stdio");
        original.setCommand("npx");
        original.setArgs(List.of("-y", "mcp-server"));
        original.setEnvironment(List.of(new CustomMcpServerConfig.McpEnvVar("K", "V")));
        original.setHeaders(List.of(new CustomMcpServerConfig.McpHeader("Authorization", "Bearer token")));
        original.setDefaultEnabled(false);

        CustomMcpServerConfig copy = original.copy();
        assertEquals(original, copy);
        assertTrue(copy.isStdio());
        assertEquals("npx", copy.getCommand());
        assertEquals(List.of("-y", "mcp-server"), copy.getArgs());
        assertEquals(1, copy.getEnvironment().size());
        assertEquals(1, copy.getHeaders().size());
        assertFalse(copy.isDefaultEnabled());

        copy.setCommand("changed");
        assertNotEquals(original, copy);
    }
}
