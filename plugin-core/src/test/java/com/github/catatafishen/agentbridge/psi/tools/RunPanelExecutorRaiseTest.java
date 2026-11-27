package com.github.catatafishen.agentbridge.psi.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RunPanelExecutor#shouldRaiseRunWindow}: when a Run tab for an agent's command
 * brings the Run tool window forward.
 */
class RunPanelExecutorRaiseTest {

    @Test
    @DisplayName("the Run window comes forward when raising is allowed and the chat does not have focus")
    void raisesWhenAllowedAndChatInactive() {
        assertTrue(RunPanelExecutor.shouldRaiseRunWindow(true, false));
    }

    @Test
    @DisplayName("a focused chat keeps the Run window back even when raising is allowed")
    void focusedChatKeepsItBack() {
        assertFalse(RunPanelExecutor.shouldRaiseRunWindow(true, true));
    }

    @Test
    @DisplayName("the setting turned off keeps the Run window back when the chat does not have focus")
    void settingOffKeepsItBack() {
        assertFalse(RunPanelExecutor.shouldRaiseRunWindow(false, false));
    }

    @Test
    @DisplayName("the setting turned off keeps the Run window back when the chat has focus")
    void settingOffAndFocusedChatKeepItBack() {
        assertFalse(RunPanelExecutor.shouldRaiseRunWindow(false, true));
    }
}
