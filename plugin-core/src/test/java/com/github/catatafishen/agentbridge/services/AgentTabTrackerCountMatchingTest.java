package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the pure helper {@link AgentTabTracker#countMatchingTerminalTabs}.
 * No IntelliJ platform dependencies — runs as a plain JUnit test.
 */
@DisplayName("AgentTabTracker.countMatchingTerminalTabs")
class AgentTabTrackerCountMatchingTest {

    @Test
    @DisplayName("returns 0 when no tabs are tracked")
    void returnsZeroWhenNoTrackedTabs() {
        assertEquals(0, AgentTabTracker.countMatchingTerminalTabs(
            List.of(), List.of("Agent: build", "Local")));
    }

    @Test
    @DisplayName("returns 0 when no open tabs match a tracked tab")
    void returnsZeroWhenNoOpenTabsMatch() {
        assertEquals(0, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build"), List.of("Local", "zsh")));
    }

    @Test
    @DisplayName("counts an exact display-name match")
    void countsExactMatch() {
        assertEquals(1, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build"), List.of("Agent: build", "Local")));
    }

    @Test
    @DisplayName("matches when the display name contains the tracked tab name with an IDE-appended suffix")
    void matchesDisplayNameWithSuffix() {
        assertEquals(1, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build"), List.of("Agent: build (1)")));
    }

    @Test
    @DisplayName("counts each open tab at most once even if several tracked names match")
    void countsEachOpenTabOnce() {
        // Both tracked names are substrings of the single open tab — it must still count once.
        assertEquals(1, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent", "build"), List.of("Agent: build")));
    }

    @Test
    @DisplayName("counts multiple distinct matching open tabs")
    void countsMultipleMatchingTabs() {
        assertEquals(2, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build", "Agent: test"),
            List.of("Agent: build", "Agent: test", "Local")));
    }

    @Test
    @DisplayName("ignores null display names")
    void ignoresNullDisplayNames() {
        assertEquals(1, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build"), Arrays.asList(null, "Agent: build", null)));
    }
}
