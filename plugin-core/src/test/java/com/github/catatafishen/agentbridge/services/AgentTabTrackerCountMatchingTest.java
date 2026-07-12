package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.CloseTerminalTool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.TerminalToolFactory;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for pure terminal-tab lifecycle helpers in {@link AgentTabTracker}.
 * No IntelliJ platform dependencies — runs as a plain JUnit test.
 */
@DisplayName("AgentTabTracker terminal lifecycle")
class AgentTabTrackerCountMatchingTest {

    @Test
    @DisplayName("terminal factory exposes a destructive close_terminal tool")
    void terminalFactoryExposesCloseTerminal() {
        List<Tool> tools = TerminalToolFactory.create(mock(Project.class));
        assertEquals(5, tools.size());

        Tool closeTool = tools.stream()
            .filter(CloseTerminalTool.class::isInstance)
            .findFirst()
            .orElseThrow();

        assertEquals("close_terminal", closeTool.id());
        assertEquals(Tool.Kind.EDIT, closeTool.kind());
        assertTrue(closeTool.isDestructive());
        assertTrue(closeTool.inputSchema().toString().contains("\"tab_name\""));
    }

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
    @DisplayName("matches an IDE-appended numeric suffix")
    void matchesDisplayNameWithSuffix() {
        assertEquals(1, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build"), List.of("Agent: build (1)")));
    }

    @Test
    @DisplayName("does not match unrelated tabs containing the tracked name")
    void ignoresPartialNameCollisions() {
        assertEquals(0, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent"), List.of("Agent: build")));
    }

    @Test
    @DisplayName("counts each open tab at most once")
    void countsEachOpenTabOnce() {
        assertEquals(1, AgentTabTracker.countMatchingTerminalTabs(
            List.of("Agent: build", "Agent: build"), List.of("Agent: build")));
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

    @Test
    @DisplayName("returns the newest tracked terminal that is still open")
    void returnsMostRecentOpenTrackedTerminal() {
        assertEquals("Agent: test (1)", AgentTabTracker.mostRecentOpenTerminalTabName(
            List.of("Agent: build", "Agent: closed", "Agent: test"),
            List.of("Local", "Agent: build", "Agent: test (1)")));
    }

    @Test
    @DisplayName("returns null when every tracked terminal is closed")
    void returnsNullWhenNoTrackedTerminalIsOpen() {
        assertNull(AgentTabTracker.mostRecentOpenTerminalTabName(
            List.of("Agent: build"), List.of("Local")));
    }

    @Test
    @DisplayName("allows creation below the agent terminal limit")
    void allowsCreationBelowLimit() {
        assertEquals(3, AgentTabTracker.MAX_OPEN_AGENT_TERMINALS);
        assertTrue(AgentTabTracker.hasTerminalCapacity(2));
    }

    @Test
    @DisplayName("blocks creation at the agent terminal limit")
    void blocksCreationAtLimit() {
        assertFalse(AgentTabTracker.hasTerminalCapacity(3));
    }

    @Test
    @DisplayName("tracks, recognizes, and untracks an AgentBridge terminal")
    void tracksAndUntracksTerminalByDisplayName() {
        AgentTabTracker tracker = new AgentTabTracker(mock(Project.class));
        tracker.trackTab("Terminal", "Agent: build");
        tracker.trackTab("Run", "Agent: tests");

        assertTrue(tracker.isTrackedTerminalTab("Agent: build"));
        assertTrue(tracker.isTrackedTerminalTab("Agent: build (2)"));
        assertFalse(tracker.isTrackedTerminalTab("Agent: tests"));

        tracker.untrackTerminalTab("Agent: build (2)");

        assertFalse(tracker.isTrackedTerminalTab("Agent: build"));
    }

    @Test
    @DisplayName("dispose forgets tracked terminals")
    void disposeForgetsTrackedTerminals() {
        AgentTabTracker tracker = new AgentTabTracker(mock(Project.class));
        tracker.trackTab("Terminal", "Agent: build");

        tracker.dispose();

        assertFalse(tracker.isTrackedTerminalTab("Agent: build"));
    }

    @Test
    @DisplayName("terminal matching rejects null names")
    void terminalMatchingRejectsNullNames() {
        assertFalse(AgentTabTracker.terminalTabNameMatches(null, "Agent: build"));
        assertFalse(AgentTabTracker.terminalTabNameMatches("Agent: build", null));
    }
}
