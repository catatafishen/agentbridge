package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpToolFilter")
class McpToolFilterTest {

    // ── isAlwaysHidden ───────────────────────────────────────────────────────

    @Test
    @DisplayName("get_chat_html is always hidden")
    void getChatHtmlIsAlwaysHidden() {
        assertTrue(McpToolFilter.isAlwaysHidden("get_chat_html"));
    }

    @Test
    @DisplayName("regular tool is not always hidden")
    void regularToolNotAlwaysHidden() {
        assertFalse(McpToolFilter.isAlwaysHidden("git_status"));
        assertFalse(McpToolFilter.isAlwaysHidden("read_file"));
        assertFalse(McpToolFilter.isAlwaysHidden("memory_search"));
    }

    @Test
    @DisplayName("empty string is not always hidden")
    void emptyStringNotHidden() {
        assertFalse(McpToolFilter.isAlwaysHidden(""));
    }

    // ── isDefaultDisabled ────────────────────────────────────────────────────

    @Test
    @DisplayName("get_notifications is default-disabled")
    void getNotificationsDefaultDisabled() {
        assertTrue(McpToolFilter.isDefaultDisabled("get_notifications"));
    }

    @Test
    @DisplayName("set_theme is default-disabled")
    void setThemeDefaultDisabled() {
        assertTrue(McpToolFilter.isDefaultDisabled("set_theme"));
    }

    @Test
    @DisplayName("list_themes is default-disabled")
    void listThemesDefaultDisabled() {
        assertTrue(McpToolFilter.isDefaultDisabled("list_themes"));
    }

    @Test
    @DisplayName("git_status is not default-disabled")
    void gitStatusNotDefaultDisabled() {
        assertFalse(McpToolFilter.isDefaultDisabled("git_status"));
    }

    @Test
    @DisplayName("read_file is not default-disabled")
    void readFileNotDefaultDisabled() {
        assertFalse(McpToolFilter.isDefaultDisabled("read_file"));
    }

    // ── DEFAULT_DISABLED set contract ────────────────────────────────────────

    @Test
    @DisplayName("DEFAULT_DISABLED is not empty")
    void defaultDisabledNotEmpty() {
        assertFalse(McpToolFilter.DEFAULT_DISABLED.isEmpty());
    }

    @Test
    @DisplayName("no overlap between always-hidden and DEFAULT_DISABLED")
    void noOverlapBetweenSets() {
        for (String toolId : McpToolFilter.DEFAULT_DISABLED) {
            assertFalse(McpToolFilter.isAlwaysHidden(toolId),
                toolId + " should not be in both always-hidden and DEFAULT_DISABLED");
        }
    }

    // ── MAX_TOOLS constant ───────────────────────────────────────────────────

    @Test
    @DisplayName("MAX_TOOLS is 128")
    void maxToolsIs128() {
        assertEquals(128, McpToolFilter.MAX_TOOLS);
    }
}
