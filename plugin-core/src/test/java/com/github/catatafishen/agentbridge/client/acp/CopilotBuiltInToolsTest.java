package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotBuiltInToolsTest {

    private static final String DEFAULTS = CopilotBuiltInTools.DEFAULT_EXCLUDED;

    @Test
    void snakeCaseNamesLookLikeToolNames() {
        for (String name : List.of("bash", "read_bash", "tool_search_tool", "session_store_sql", "x2_y3")) {
            assertTrue(CopilotBuiltInTools.looksLikeToolName(name), name);
        }
    }

    @Test
    void humanReadableAndMcpTitlesDoNotLookLikeToolNames() {
        for (String title : List.of(
            "Update review todo", "Using skill: sonarqube", "Fetching raw.githubusercontent.com...x.ts",
            "agentbridge-read_file", "Git Stage", "Bash", "_leading", "trailing_", "double__underscore", "")) {
            assertFalse(CopilotBuiltInTools.looksLikeToolName(title), title);
        }
    }

    @Test
    void nonAsciiAndUppercaseLettersDoNotLookLikeToolNames() {
        for (String title : List.of("café", "tool_Name", "tôol", "a-b", "a b", "a.b", "1abc")) {
            assertFalse(CopilotBuiltInTools.looksLikeToolName(title), title);
        }
    }

    @Test
    void veryLongInputIsHandledWithoutStackOverflow() {
        assertTrue(CopilotBuiltInTools.looksLikeToolName("a_b".repeat(100_000)));
        assertFalse(CopilotBuiltInTools.looksLikeToolName("a_b".repeat(100_000) + "_"));
    }

    @Test
    void defaultExcludedToolsAreNeverUnknown() {
        for (String tool : CopilotBuiltInTools.parse(DEFAULTS)) {
            assertFalse(CopilotBuiltInTools.isUnknown(tool, ""), tool);
        }
    }

    @Test
    void deliberatelyKeptToolsAreNeverUnknown() {
        for (String tool : CopilotBuiltInTools.KNOWN_KEPT) {
            assertFalse(CopilotBuiltInTools.isUnknown(tool, ""), tool);
        }
    }

    @Test
    void unclassifiedToolNameIsUnknown() {
        assertTrue(CopilotBuiltInTools.isUnknown("brand_new_tool", DEFAULTS));
    }

    @Test
    void toolInUserConfiguredListIsNotUnknown() {
        assertTrue(CopilotBuiltInTools.isUnknown("brand_new_tool", "view,edit"));
        assertFalse(CopilotBuiltInTools.isUnknown("brand_new_tool", "view, brand_new_tool"));
    }

    @Test
    void customListReplacingDefaultsStillTreatsDefaultToolsAsKnown() {
        assertFalse(CopilotBuiltInTools.isUnknown("bash", "rg"));
    }

    @Test
    void humanReadableTitlesAreNeverUnknown() {
        assertFalse(CopilotBuiltInTools.isUnknown("Update review todo", DEFAULTS));
    }

    @Test
    void parseTrimsAndDropsBlanksKeepingOrder() {
        assertEquals(List.of("a", "b", "c"), List.copyOf(CopilotBuiltInTools.parse(" a, b ,,c ,")));
        assertEquals(Set.of(), CopilotBuiltInTools.parse(""));
    }

    @Test
    void withToolAppendsOnce() {
        assertEquals("a,b,new_tool", CopilotBuiltInTools.withTool("a,b", "new_tool"));
        assertEquals("a,b,new_tool", CopilotBuiltInTools.withTool("a,b,new_tool", "new_tool"));
        assertEquals("new_tool", CopilotBuiltInTools.withTool("", "new_tool"));
    }

    @Test
    void withToolOnDefaultsKeepsEveryDefault() {
        Set<String> result = CopilotBuiltInTools.parse(CopilotBuiltInTools.withTool(DEFAULTS, "new_tool"));
        assertTrue(result.containsAll(CopilotBuiltInTools.parse(DEFAULTS)));
        assertTrue(result.contains("new_tool"));
    }
}
