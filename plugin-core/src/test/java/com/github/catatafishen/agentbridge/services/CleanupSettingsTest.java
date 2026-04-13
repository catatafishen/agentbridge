package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CleanupSettings} — persistent cleanup configuration.
 * Constructs CleanupSettings directly (no Project needed for these tests).
 */
class CleanupSettingsTest {

    @Test
    void stateDefaults_scratchRetentionHoursIs24() {
        CleanupSettings settings = new CleanupSettings();
        assertEquals(24, settings.getScratchRetentionHours());
    }

    @Test
    void stateDefaults_autoCloseAgentTabsIsTrue() {
        CleanupSettings settings = new CleanupSettings();
        assertTrue(settings.isAutoCloseAgentTabs());
    }

    @Test
    void stateDefaults_autoCloseRunningTerminalsIsFalse() {
        CleanupSettings settings = new CleanupSettings();
        assertFalse(settings.isAutoCloseRunningTerminals());
    }

    @Test
    void setScratchRetentionHours_positiveValue() {
        CleanupSettings settings = new CleanupSettings();
        settings.setScratchRetentionHours(48);
        assertEquals(48, settings.getScratchRetentionHours());
    }

    @Test
    void setScratchRetentionHours_zero() {
        CleanupSettings settings = new CleanupSettings();
        settings.setScratchRetentionHours(0);
        assertEquals(0, settings.getScratchRetentionHours());
    }

    @Test
    void setScratchRetentionHours_negativeClampedToZero() {
        CleanupSettings settings = new CleanupSettings();
        settings.setScratchRetentionHours(-5);
        assertEquals(0, settings.getScratchRetentionHours());
    }

    @Test
    void loadState_gettersReflectLoadedState() {
        CleanupSettings settings = new CleanupSettings();
        CleanupSettings.State loaded = new CleanupSettings.State();
        loaded.scratchRetentionHours = 72;
        loaded.autoCloseAgentTabs = false;
        loaded.autoCloseRunningTerminals = true;

        settings.loadState(loaded);

        assertEquals(72, settings.getScratchRetentionHours());
        assertFalse(settings.isAutoCloseAgentTabs());
        assertTrue(settings.isAutoCloseRunningTerminals());
    }

    @Test
    void getState_returnsNonNull() {
        CleanupSettings settings = new CleanupSettings();
        assertNotNull(settings.getState());
    }

    @Test
    void getState_reflectsCurrentValues() {
        CleanupSettings settings = new CleanupSettings();
        settings.setScratchRetentionHours(12);
        settings.setAutoCloseAgentTabs(false);
        settings.setAutoCloseRunningTerminals(true);

        CleanupSettings.State state = settings.getState();
        assertEquals(12, state.scratchRetentionHours);
        assertFalse(state.autoCloseAgentTabs);
        assertTrue(state.autoCloseRunningTerminals);
    }

    @Test
    void autoCloseAgentTabs_setAndGetRoundTrip() {
        CleanupSettings settings = new CleanupSettings();
        settings.setAutoCloseAgentTabs(false);
        assertFalse(settings.isAutoCloseAgentTabs());

        settings.setAutoCloseAgentTabs(true);
        assertTrue(settings.isAutoCloseAgentTabs());
    }

    @Test
    void autoCloseRunningTerminals_setAndGetRoundTrip() {
        CleanupSettings settings = new CleanupSettings();
        settings.setAutoCloseRunningTerminals(true);
        assertTrue(settings.isAutoCloseRunningTerminals());

        settings.setAutoCloseRunningTerminals(false);
        assertFalse(settings.isAutoCloseRunningTerminals());
    }
}
