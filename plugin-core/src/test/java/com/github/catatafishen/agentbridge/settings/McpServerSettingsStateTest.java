package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpServerSettings.State} (pure POJO round-trips and
 * default values) and the service-level helper methods that operate on the
 * state without touching IntelliJ services.
 */
@DisplayName("McpServerSettings state")
class McpServerSettingsStateTest {

    private McpServerSettings settings;

    @BeforeEach
    void setUp() {
        settings = new McpServerSettings();
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    @DisplayName("default port is 8642")
    void defaultPort() {
        assertEquals(McpServerSettings.DEFAULT_PORT, settings.getPort());
    }

    @Test
    @DisplayName("autoStart defaults to false")
    void defaultAutoStart() {
        assertFalse(settings.isAutoStart());
    }

    @Test
    @DisplayName("debugLogging defaults to false")
    void defaultDebugLogging() {
        assertFalse(settings.isDebugLoggingEnabled());
    }

    @Test
    @DisplayName("smoothScroll defaults to false")
    void defaultSmoothScroll() {
        assertFalse(settings.isSmoothScrollEnabled());
    }

    @Test
    @DisplayName("showTurnStats defaults to true")
    void defaultShowTurnStats() {
        assertTrue(settings.isShowTurnStats());
    }

    @Test
    @DisplayName("disabledToolIds defaults to empty")
    void defaultDisabledToolIds() {
        assertTrue(settings.getDisabledToolIds().isEmpty());
    }

    @Test
    @DisplayName("staticPort defaults to false")
    void defaultStaticPort() {
        assertFalse(settings.isStaticPort());
    }

    @Test
    @DisplayName("color keys default to null")
    void defaultColorKeys() {
        assertNull(settings.getKindReadColorKey());
        assertNull(settings.getKindEditColorKey());
        assertNull(settings.getKindExecuteColorKey());
        assertNull(settings.getKindSearchColorKey());
    }

    // ── Round-trips ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("port round-trips correctly")
    void portRoundTrip() {
        settings.setPort(9000);
        assertEquals(9000, settings.getPort());
    }

    @Test
    @DisplayName("staticPort round-trips correctly")
    void staticPortRoundTrip() {
        settings.setStaticPort(true);
        assertTrue(settings.isStaticPort());
        settings.setStaticPort(false);
        assertFalse(settings.isStaticPort());
    }

    @Test
    @DisplayName("autoStart round-trips correctly")
    void autoStartRoundTrip() {
        settings.setAutoStart(true);
        assertTrue(settings.isAutoStart());
        settings.setAutoStart(false);
        assertFalse(settings.isAutoStart());
    }

    @Test
    @DisplayName("debugLogging round-trips correctly")
    void debugLoggingRoundTrip() {
        settings.setDebugLoggingEnabled(true);
        assertTrue(settings.isDebugLoggingEnabled());
    }

    @Test
    @DisplayName("smoothScroll round-trips correctly")
    void smoothScrollRoundTrip() {
        settings.setSmoothScrollEnabled(true);
        assertTrue(settings.isSmoothScrollEnabled());
    }

    @Test
    @DisplayName("showTurnStats round-trips correctly")
    void showTurnStatsRoundTrip() {
        settings.setShowTurnStats(false);
        assertFalse(settings.isShowTurnStats());
    }

    @Test
    @DisplayName("kindReadColorKey round-trips correctly")
    void kindReadColorKeyRoundTrip() {
        settings.setKindReadColorKey("EditorInfo.foreground");
        assertEquals("EditorInfo.foreground", settings.getKindReadColorKey());
        settings.setKindReadColorKey(null);
        assertNull(settings.getKindReadColorKey());
    }

    // ── isToolEnabled / setToolEnabled ───────────────────────────────────────

    @Test
    @DisplayName("all tools enabled by default")
    void allToolsEnabledByDefault() {
        assertTrue(settings.isToolEnabled("git_status"));
        assertTrue(settings.isToolEnabled("read_file"));
        assertTrue(settings.isToolEnabled("any_tool_id"));
    }

    @Test
    @DisplayName("setToolEnabled(false) disables tool")
    void disableToolEnabled() {
        settings.setToolEnabled("git_status", false);
        assertFalse(settings.isToolEnabled("git_status"));
    }

    @Test
    @DisplayName("setToolEnabled(true) re-enables a disabled tool")
    void reenableTool() {
        settings.setToolEnabled("git_status", false);
        settings.setToolEnabled("git_status", true);
        assertTrue(settings.isToolEnabled("git_status"));
    }

    @Test
    @DisplayName("disabling one tool does not affect others")
    void disablingOneToolDoesNotAffectOthers() {
        settings.setToolEnabled("git_status", false);
        assertTrue(settings.isToolEnabled("read_file"));
    }

    @Test
    @DisplayName("setDisabledToolIds replaces the entire set")
    void setDisabledToolIds() {
        settings.setToolEnabled("git_status", false);
        settings.setDisabledToolIds(Set.of("read_file", "write_file"));
        assertFalse(settings.isToolEnabled("read_file"));
        assertFalse(settings.isToolEnabled("write_file"));
        assertTrue(settings.isToolEnabled("git_status"), "git_status should be re-enabled after replacing set");
    }

    // ── ensureDefaultsApplied ────────────────────────────────────────────────

    @Test
    @DisplayName("ensureDefaultsApplied disables DEFAULT_DISABLED tools on first call")
    void ensureDefaultsAppliedFirstCall() {
        settings.ensureDefaultsApplied();
        for (String toolId : McpToolFilter.DEFAULT_DISABLED) {
            assertFalse(settings.isToolEnabled(toolId),
                toolId + " should be disabled after ensureDefaultsApplied");
        }
    }

    @Test
    @DisplayName("ensureDefaultsApplied is idempotent — second call is a no-op")
    void ensureDefaultsAppliedIdempotent() {
        settings.ensureDefaultsApplied();
        // Re-enable a default-disabled tool manually
        String toolId = McpToolFilter.DEFAULT_DISABLED.iterator().next();
        settings.setToolEnabled(toolId, true);
        // Second call must NOT re-disable it
        settings.ensureDefaultsApplied();
        assertTrue(settings.isToolEnabled(toolId),
            "Second ensureDefaultsApplied must not override manual re-enable");
    }

    // ── loadState / getState round-trip ──────────────────────────────────────

    @Test
    @DisplayName("loadState replaces internal state")
    void loadStateReplacesState() {
        McpServerSettings.State state = new McpServerSettings.State();
        state.setPort(1234);
        state.setAutoStart(true);
        settings.loadState(state);
        assertEquals(1234, settings.getPort());
        assertTrue(settings.isAutoStart());
    }

    @Test
    @DisplayName("getState returns the live state object")
    void getStateReturnsLiveState() {
        settings.setPort(7777);
        assertEquals(7777, settings.getState().getPort());
    }

    // ── TransportMode ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("transportMode defaults to STREAMABLE_HTTP")
    void defaultTransportMode() {
        assertEquals(TransportMode.STREAMABLE_HTTP, settings.getTransportMode());
    }

    @Test
    @DisplayName("transportMode round-trips correctly")
    void transportModeRoundTrip() {
        settings.setTransportMode(TransportMode.SSE);
        assertEquals(TransportMode.SSE, settings.getTransportMode());
        settings.setTransportMode(TransportMode.STREAMABLE_HTTP);
        assertEquals(TransportMode.STREAMABLE_HTTP, settings.getTransportMode());
    }

    // ── Color key round-trips (edit / execute / search) ───────────────────────

    @Test
    @DisplayName("kindEditColorKey round-trips correctly")
    void kindEditColorKeyRoundTrip() {
        settings.setKindEditColorKey("EditorError.foreground");
        assertEquals("EditorError.foreground", settings.getKindEditColorKey());
        settings.setKindEditColorKey(null);
        assertNull(settings.getKindEditColorKey());
    }

    @Test
    @DisplayName("kindExecuteColorKey round-trips correctly")
    void kindExecuteColorKeyRoundTrip() {
        settings.setKindExecuteColorKey("EditorWarning.foreground");
        assertEquals("EditorWarning.foreground", settings.getKindExecuteColorKey());
        settings.setKindExecuteColorKey(null);
        assertNull(settings.getKindExecuteColorKey());
    }

    @Test
    @DisplayName("kindSearchColorKey round-trips correctly")
    void kindSearchColorKeyRoundTrip() {
        settings.setKindSearchColorKey("EditorInfo.foreground");
        assertEquals("EditorInfo.foreground", settings.getKindSearchColorKey());
        settings.setKindSearchColorKey(null);
        assertNull(settings.getKindSearchColorKey());
    }

    // ── Output template API ──────────────────────────────────────────────────

    @Test
    @DisplayName("getToolOutputTemplate returns empty string by default")
    void defaultOutputTemplate() {
        assertEquals("", settings.getToolOutputTemplate("git_commit"));
    }

    @Test
    @DisplayName("setToolOutputTemplate round-trips correctly")
    void outputTemplateRoundTrip() {
        settings.setToolOutputTemplate("git_commit", "Always use conventional commits");
        assertEquals("Always use conventional commits", settings.getToolOutputTemplate("git_commit"));
    }

    @Test
    @DisplayName("setting template to null clears it")
    void clearTemplateWithNull() {
        settings.setToolOutputTemplate("git_commit", "something");
        settings.setToolOutputTemplate("git_commit", null);
        assertEquals("", settings.getToolOutputTemplate("git_commit"));
    }

    @Test
    @DisplayName("setting template to empty clears it")
    void clearTemplateWithEmpty() {
        settings.setToolOutputTemplate("git_commit", "something");
        settings.setToolOutputTemplate("git_commit", "");
        assertEquals("", settings.getToolOutputTemplate("git_commit"));
    }

    @Test
    @DisplayName("clearing template removes the entry from the map")
    void clearingTemplatePrunesMap() {
        settings.setToolOutputTemplate("git_commit", "test");
        settings.setToolOutputTemplate("git_commit", "");
        assertTrue(settings.getAllToolOptions().isEmpty(),
            "Empty ToolOptions entries should be pruned from the map");
    }

    @Test
    @DisplayName("getToolOutputHookCommand returns empty string by default")
    void defaultOutputHookCommand() {
        assertEquals("", settings.getToolOutputHookCommand("git_commit"));
    }

    @Test
    @DisplayName("setToolOutputHookCommand round-trips correctly")
    void outputHookCommandRoundTrip() {
        settings.setToolOutputHookCommand("git_commit", "python hooks/git_commit.py");
        assertEquals("python hooks/git_commit.py", settings.getToolOutputHookCommand("git_commit"));
    }

    @Test
    @DisplayName("clearing hook command preserves template on same tool")
    void clearingHookPreservesTemplate() {
        settings.setToolOutputTemplate("git_commit", "template");
        settings.setToolOutputHookCommand("git_commit", "python hook.py");
        settings.setToolOutputHookCommand("git_commit", "");
        assertEquals("template", settings.getToolOutputTemplate("git_commit"));
        assertEquals("", settings.getToolOutputHookCommand("git_commit"));
        assertFalse(settings.getAllToolOptions().isEmpty());
    }

    @Test
    @DisplayName("clearing hook command prunes map when it is the only option")
    void clearingHookPrunesMap() {
        settings.setToolOutputHookCommand("git_commit", "python hook.py");
        settings.setToolOutputHookCommand("git_commit", null);
        assertTrue(settings.getAllToolOptions().isEmpty());
    }

    @Test
    @DisplayName("templates for different tools are independent")
    void templatesAreIndependent() {
        settings.setToolOutputTemplate("git_commit", "template A");
        settings.setToolOutputTemplate("edit_text", "template B");
        assertEquals("template A", settings.getToolOutputTemplate("git_commit"));
        assertEquals("template B", settings.getToolOutputTemplate("edit_text"));
    }

    @Test
    @DisplayName("template survives loadState round-trip")
    void templateSurvivesLoadState() {
        settings.setToolOutputTemplate("run_tests", "Check CI dashboard");
        McpServerSettings.State state = settings.getState();
        McpServerSettings restored = new McpServerSettings();
        restored.loadState(state);
        assertEquals("Check CI dashboard", restored.getToolOutputTemplate("run_tests"));
    }
}
