package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure predicate {@link AgentTabTracker#shouldSkipClose}.
 * No IntelliJ platform dependencies — runs as a plain JUnit test.
 */
@DisplayName("AgentTabTracker.shouldSkipClose")
class AgentTabTrackerShouldSkipCloseTest {

    // ── Terminal tab behaviour ──────────────────────────────────────────

    @Nested
    @DisplayName("Terminal tabs")
    class TerminalTabs {

        @Test
        @DisplayName("skips Terminal tab when closeRunningTerminals is false")
        void skipsTerminalWhenCloseRunningTerminalsIsFalse() {
            assertTrue(AgentTabTracker.shouldSkipClose("Terminal", false, false));
        }

        @Test
        @DisplayName("skips Terminal tab when closeRunningTerminals is false even if process active flag is true")
        void skipsTerminalRegardlessOfProcessActiveFlag() {
            // isProcessActive is irrelevant for Terminal tabs — the flag is only meaningful for Run tabs
            assertTrue(AgentTabTracker.shouldSkipClose("Terminal", false, true));
        }

        @Test
        @DisplayName("does not skip Terminal tab when closeRunningTerminals is true")
        void doesNotSkipTerminalWhenCloseRunningTerminalsIsTrue() {
            assertFalse(AgentTabTracker.shouldSkipClose("Terminal", true, false));
        }

        @Test
        @DisplayName("does not skip Terminal tab when closeRunningTerminals is true even with process active")
        void doesNotSkipTerminalWhenCloseRunningTerminalsIsTrueAndProcessActive() {
            assertFalse(AgentTabTracker.shouldSkipClose("Terminal", true, true));
        }
    }

    // ── Run tab behaviour ───────────────────────────────────────────────

    @Nested
    @DisplayName("Run tabs")
    class RunTabs {

        @Test
        @DisplayName("skips Run tab when process is still active")
        void skipsRunTabWhenProcessActive() {
            assertTrue(AgentTabTracker.shouldSkipClose("Run", true, true));
        }

        @Test
        @DisplayName("skips Run tab when process active and closeRunningTerminals false")
        void skipsRunTabWhenProcessActiveAndCloseTerminalsFalse() {
            assertTrue(AgentTabTracker.shouldSkipClose("Run", false, true));
        }

        @Test
        @DisplayName("does not skip Run tab when process has terminated")
        void doesNotSkipRunTabWhenProcessTerminated() {
            assertFalse(AgentTabTracker.shouldSkipClose("Run", true, false));
        }

        @Test
        @DisplayName("does not skip Run tab when process terminated and closeRunningTerminals false")
        void doesNotSkipRunTabWhenProcessTerminatedAndCloseTerminalsFalse() {
            assertFalse(AgentTabTracker.shouldSkipClose("Run", false, false));
        }
    }

    // ── Other tool window IDs ───────────────────────────────────────────

    @Nested
    @DisplayName("Other tool windows")
    class OtherToolWindows {

        @ParameterizedTest(name = "toolWindowId=\"{0}\" — never skipped")
        @ValueSource(strings = {"Build", "Debug", "Problems", "Version Control", "TODO"})
        @DisplayName("never skips non-Terminal, non-Run tool windows")
        void neverSkipsOtherToolWindows(String toolWindowId) {
            assertFalse(AgentTabTracker.shouldSkipClose(toolWindowId, false, false));
            assertFalse(AgentTabTracker.shouldSkipClose(toolWindowId, false, true));
            assertFalse(AgentTabTracker.shouldSkipClose(toolWindowId, true, false));
            assertFalse(AgentTabTracker.shouldSkipClose(toolWindowId, true, true));
        }

        @Test
        @DisplayName("does not skip null tool window id")
        void doesNotSkipNullToolWindowId() {
            assertFalse(AgentTabTracker.shouldSkipClose(null, false, false));
            assertFalse(AgentTabTracker.shouldSkipClose(null, true, true));
        }

        @Test
        @DisplayName("does not match case-insensitive variants")
        void doesNotMatchCaseInsensitiveVariants() {
            // "terminal" (lowercase) should NOT match "Terminal"
            assertFalse(AgentTabTracker.shouldSkipClose("terminal", false, false));
            // "run" (lowercase) should NOT match "Run"
            assertFalse(AgentTabTracker.shouldSkipClose("run", false, true));
        }
    }
}
