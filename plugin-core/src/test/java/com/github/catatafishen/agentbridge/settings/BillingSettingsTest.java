package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BillingSettingsTest {

    private BillingSettings settings;

    @BeforeEach
    void setUp() {
        settings = new BillingSettings();
    }

    @Test
    @DisplayName("default showCopilotUsage is true")
    void defaultShowCopilotUsageIsTrue() {
        assertTrue(settings.isShowCopilotUsage());
    }

    @Test
    @DisplayName("default ghBinaryPath is null (blank stored value normalizes to null)")
    void defaultGhBinaryPathIsNull() {
        assertNull(settings.getGhBinaryPath());
    }

    @Test
    @DisplayName("setShowCopilotUsage round-trip")
    void setShowCopilotUsageRoundTrip() {
        settings.setShowCopilotUsage(false);
        assertFalse(settings.isShowCopilotUsage());
        settings.setShowCopilotUsage(true);
        assertTrue(settings.isShowCopilotUsage());
    }

    @Test
    @DisplayName("setGhBinaryPath stores and retrieves non-blank path")
    void setGhBinaryPathNonBlank() {
        settings.setGhBinaryPath("/usr/local/bin/gh");
        assertEquals("/usr/local/bin/gh", settings.getGhBinaryPath());
    }

    @Test
    @DisplayName("setGhBinaryPath with null stores null and getGhBinaryPath returns null")
    void setGhBinaryPathNull() {
        settings.setGhBinaryPath("/some/path");
        settings.setGhBinaryPath(null);
        assertNull(settings.getGhBinaryPath());
    }

    @Test
    @DisplayName("setGhBinaryPath with blank string normalizes to null on getter")
    void setGhBinaryPathBlankNormalizesToNull() {
        settings.setGhBinaryPath("   ");
        assertNull(settings.getGhBinaryPath());
    }

    @Test
    @DisplayName("setGhBinaryPath trims whitespace from stored value but getter still returns null for blank")
    void setGhBinaryPathEmptyStringNormalizesToNull() {
        settings.setGhBinaryPath("");
        assertNull(settings.getGhBinaryPath());
    }

    @Test
    @DisplayName("getState returns the current state object")
    void getStateReturnsCurrentState() {
        settings.setShowCopilotUsage(false);
        assertFalse(settings.getState().isShowCopilotUsage());
    }

    @Test
    @DisplayName("loadState replaces the internal state")
    void loadStateReplacesState() {
        var newState = new BillingSettings.State();
        newState.setShowCopilotUsage(false);
        newState.setGhBinaryPath("/custom/gh");
        settings.loadState(newState);
        assertFalse(settings.isShowCopilotUsage());
        // The State stores the path directly; getter normalizes blank/null
        assertEquals("/custom/gh", settings.getGhBinaryPath());
    }

    @Test
    @DisplayName("State inner class setters and getters are consistent")
    void stateInnerClassConsistent() {
        var state = new BillingSettings.State();
        state.setShowCopilotUsage(false);
        state.setGhBinaryPath("/path");
        assertFalse(state.isShowCopilotUsage());
        assertEquals("/path", state.getGhBinaryPath());
    }
}
