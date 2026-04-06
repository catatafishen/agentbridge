package com.github.catatafishen.agentbridge.custommcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CustomMcpServerConfig}.
 */
class CustomMcpServerConfigTest {

    @Test
    void toolPrefix_simpleAsciiName_addsCmcpPrefix() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setName("database");
        assertEquals("cmcp_database", config.toolPrefix());
    }

    @Test
    void toolPrefix_mixedCaseWithSpaces_normalisedToLowerUnderscore() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setName("My Custom Server");
        assertEquals("cmcp_my_custom_server", config.toolPrefix());
    }

    @Test
    void toolPrefix_specialCharsCollapsed() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setName("---db & tools---");
        assertEquals("cmcp_db_tools", config.toolPrefix());
    }

    @Test
    void toolPrefix_emptyName_fallsBackToIdPrefix() {
        CustomMcpServerConfig config = new CustomMcpServerConfig();
        config.setId("abcd1234-efgh");
        config.setName("");
        assertTrue(config.toolPrefix().startsWith("cmcp_"), "prefix should always start with cmcp_");
        assertFalse(config.toolPrefix().equals("cmcp_"), "prefix should have non-empty suffix");
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
}
