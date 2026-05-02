package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the versioned defaults migration in {@link McpServerSettings#ensureDefaultsApplied()}.
 * Creates raw instances (without IntelliJ service locator) to test the state machine
 * without a live IDE environment.
 */
@DisplayName("McpServerSettings defaults migration")
class McpServerSettingsDefaultsMigrationTest {

    @Test
    @DisplayName("fresh install: all DEFAULT_DISABLED applied, version set to CURRENT")
    void freshInstallAppliesAllDefaults() {
        var settings = new McpServerSettings();
        settings.ensureDefaultsApplied();

        var state = settings.getState();
        assertTrue(state.isDefaultsApplied());
        assertEquals(McpToolFilter.CURRENT_DEFAULTS_VERSION, state.getDefaultsVersion());

        for (String toolId : McpToolFilter.DEFAULT_DISABLED) {
            assertTrue(state.getDisabledToolIds().contains(toolId),
                toolId + " should be disabled after fresh install");
        }
    }

    @Test
    @DisplayName("fresh install: idempotent — calling twice doesn't duplicate entries")
    void freshInstallIdempotent() {
        var settings = new McpServerSettings();
        settings.ensureDefaultsApplied();
        settings.ensureDefaultsApplied();

        var state = settings.getState();
        assertEquals(McpToolFilter.DEFAULT_DISABLED.size(), state.getDisabledToolIds().size());
    }

    @Test
    @DisplayName("pre-versioned install (defaultsApplied=true, version=0): migrates to v2")
    void preVersionedInstallMigratesToV2() {
        var settings = new McpServerSettings();

        // Simulate state from before versioning was added:
        // defaultsApplied=true, version=0, only v1 defaults present
        var oldState = new McpServerSettings.State();
        oldState.setDefaultsApplied(true);
        oldState.setDefaultsVersion(0);
        var v1Defaults = McpToolFilter.DEFAULTS_BY_VERSION.get(1);
        oldState.getDisabledToolIds().addAll(v1Defaults);
        settings.loadState(oldState);

        settings.ensureDefaultsApplied();

        var state = settings.getState();
        assertEquals(McpToolFilter.CURRENT_DEFAULTS_VERSION, state.getDefaultsVersion());

        // v1 defaults preserved
        for (String toolId : v1Defaults) {
            assertTrue(state.getDisabledToolIds().contains(toolId),
                toolId + " (v1) should still be disabled");
        }

        // v2 defaults applied
        var v2Defaults = McpToolFilter.DEFAULTS_BY_VERSION.get(2);
        for (String toolId : v2Defaults) {
            assertTrue(state.getDisabledToolIds().contains(toolId),
                toolId + " (v2) should now be disabled after migration");
        }
    }

    @Test
    @DisplayName("pre-versioned install preserves user-enabled tools from v1")
    void preVersionedInstallPreservesUserChoices() {
        var settings = new McpServerSettings();

        // Simulate: user had v1 defaults but manually re-enabled set_theme
        var oldState = new McpServerSettings.State();
        oldState.setDefaultsApplied(true);
        oldState.setDefaultsVersion(0);
        var disabled = new LinkedHashSet<String>();
        disabled.add("get_notifications");
        disabled.add("list_themes");
        // NOTE: set_theme intentionally NOT in disabled — user enabled it
        oldState.setDisabledToolIds(disabled);
        settings.loadState(oldState);

        settings.ensureDefaultsApplied();

        var state = settings.getState();
        // set_theme should still NOT be in disabled — user choice preserved
        assertTrue(!state.getDisabledToolIds().contains("set_theme"),
            "User's choice to enable set_theme should be preserved");

        // But v2 defaults should be applied
        assertTrue(state.getDisabledToolIds().contains("run_sonarqube_analysis"));
        assertTrue(state.getDisabledToolIds().contains("get_sonar_rule_description"));
    }

    @Test
    @DisplayName("current version: no-op")
    void currentVersionIsNoOp() {
        var settings = new McpServerSettings();

        var state = new McpServerSettings.State();
        state.setDefaultsApplied(true);
        state.setDefaultsVersion(McpToolFilter.CURRENT_DEFAULTS_VERSION);
        // Only v1 defaults — user intentionally removed v2
        var v1Defaults = McpToolFilter.DEFAULTS_BY_VERSION.get(1);
        state.getDisabledToolIds().addAll(v1Defaults);
        settings.loadState(state);

        int sizeBefore = state.getDisabledToolIds().size();
        settings.ensureDefaultsApplied();

        assertEquals(sizeBefore, settings.getState().getDisabledToolIds().size(),
            "No new defaults should be applied when already at current version");
    }
}
