package com.github.catatafishen.agentbridge.custommcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
