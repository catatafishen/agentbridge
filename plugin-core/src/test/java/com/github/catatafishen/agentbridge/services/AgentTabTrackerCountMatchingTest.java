package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.CloseTerminalTool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.TerminalToolFactory;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests owner-scoped terminal lifecycle behavior in {@link AgentTabTracker}.
 * Uses mocked IntelliJ content objects and an injected open-content snapshot.
 */
@DisplayName("AgentTabTracker terminal ownership")
class AgentTabTrackerCountMatchingTest {

    private final List<Content> openContents = new ArrayList<>();
    private AgentTabTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new AgentTabTracker(mock(Project.class), () -> List.copyOf(openContents));
    }

    @Test
    @DisplayName("terminal factory exposes terminal_id on the close tool")
    void terminalFactoryExposesTerminalId() {
        List<Tool> tools = TerminalToolFactory.create(mock(Project.class));
        assertEquals(5, tools.size());

        Tool closeTool = tools.stream()
            .filter(CloseTerminalTool.class::isInstance)
            .findFirst()
            .orElseThrow();

        assertEquals("close_terminal", closeTool.id());
        assertEquals(Tool.Kind.EDIT, closeTool.kind());
        assertTrue(closeTool.isDestructive());
        assertTrue(closeTool.inputSchema().toString().contains("\"terminal_id\""));
        assertTrue(closeTool.inputSchema().toString().contains("\"tab_name\""));
    }

    @Test
    @DisplayName("generic tab tracking rejects terminal tabs without an owner")
    void genericTrackingRejectsTerminalTabs() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> tracker.trackTab("Terminal", "Agent: build"));

        assertTrue(error.getMessage().contains("trackTerminal"));
    }

    @Test
    @DisplayName("same tab name resolves to each owner's exact content")
    void isolatesDuplicateDisplayNamesByOwner() {
        Content first = openContent("Agent: test");
        Content second = openContent("Agent: test");
        String firstId = tracker.trackTerminal("session-a", first);
        String secondId = tracker.trackTerminal("session-b", second);

        assertNotEquals(firstId, secondId);
        assertSame(first, tracker.findOwnedTerminal("session-a", null, "Agent: test").content());
        assertSame(second, tracker.findOwnedTerminal("session-b", null, "Agent: test").content());
    }

    @Test
    @DisplayName("one IDE terminal content cannot be owned by two sessions")
    void rejectsDuplicateContentOwnership() {
        Content content = openContent("Agent: shared");
        tracker.trackTerminal("session-a", content);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> tracker.trackTerminal("session-b", content));

        assertTrue(error.getMessage().contains("another MCP session"));
    }

    @Test
    @DisplayName("tracking the same content twice for one owner reuses its stable id")
    void reusesStableIdForSameOwnerAndContent() {
        Content content = openContent("Agent: build");

        String firstId = tracker.trackTerminal("session-a", content);
        String secondId = tracker.trackTerminal("session-a", content);

        assertEquals(firstId, secondId);
        assertEquals(1, tracker.countOpenTerminalTabs("session-a"));
    }

    @Test
    @DisplayName("terminal id cannot cross an owner boundary")
    void rejectsForeignTerminalId() {
        Content first = openContent("Agent: build");
        Content second = openContent("Agent: build");
        String firstId = tracker.trackTerminal("session-a", first);
        String secondId = tracker.trackTerminal("session-b", second);

        assertSame(first, tracker.findOwnedTerminal("session-a", firstId, null).content());
        assertNull(tracker.findOwnedTerminal("session-a", secondId, null));
        assertNull(tracker.findOwnedTerminal("session-b", firstId, null));
    }

    @Test
    @DisplayName("stable id and display name must identify the same terminal")
    void rejectsMismatchedCombinedSelectors() {
        Content content = openContent("Agent: build");
        String terminalId = tracker.trackTerminal("session-a", content);

        assertNull(tracker.findOwnedTerminal(
            "session-a", terminalId, "Agent: another"));
    }

    @Test
    @DisplayName("omitted selector resolves the owner's most recent open terminal")
    void resolvesMostRecentTerminalWithinOwner() {
        Content first = openContent("Agent: first");
        Content foreign = openContent("Agent: foreign");
        Content latest = openContent("Agent: latest");
        tracker.trackTerminal("session-a", first);
        tracker.trackTerminal("session-b", foreign);
        String latestId = tracker.trackTerminal("session-a", latest);

        AgentTabTracker.AgentTerminal resolved =
            tracker.findOwnedTerminal("session-a", null, null);

        assertEquals(latestId, resolved.terminalId());
        assertSame(latest, resolved.content());
    }

    @Test
    @DisplayName("closed IDE content is pruned from ownership and capacity")
    void prunesClosedContent() {
        Content content = openContent("Agent: closed");
        String terminalId = tracker.trackTerminal("session-a", content);
        openContents.remove(content);

        assertNull(tracker.findOwnedTerminal("session-a", terminalId, null));
        assertEquals(0, tracker.countOpenTerminalTabs("session-a"));
        assertTrue(tracker.hasOpenTerminalCapacity("session-a"));
    }

    @Test
    @DisplayName("global terminal count prunes closed content")
    void globalCountPrunesClosedContent() {
        Content open = openContent("Agent: open");
        Content closed = openContent("Agent: closed");
        tracker.trackTerminal("session-a", open);
        tracker.trackTerminal("session-b", closed);
        openContents.remove(closed);

        assertEquals(1, tracker.countOpenTerminalTabs());
        assertSame(open, tracker.findOwnedTerminal("session-a", null, null).content());
        assertNull(tracker.findOwnedTerminal("session-b", null, null));
    }

    @Test
    @DisplayName("per-owner cap does not consume another owner's allowance")
    void appliesPerOwnerCap() {
        assertEquals(3, AgentTabTracker.MAX_OPEN_AGENT_TERMINALS);

        for (int i = 0; i < AgentTabTracker.MAX_OPEN_AGENT_TERMINALS; i++) {
            tracker.trackTerminal("session-a", openContent("A-" + i));
        }
        tracker.trackTerminal("session-b", openContent("B"));

        assertFalse(tracker.hasOpenTerminalCapacity("session-a"));
        assertTrue(tracker.hasOpenTerminalCapacity("session-b"));
        assertEquals(3, tracker.countOpenTerminalTabs("session-a"));
        assertEquals(1, tracker.countOpenTerminalTabs("session-b"));
    }

    @Test
    @DisplayName("global cap prevents unbounded terminals across short-lived owners")
    void appliesGlobalCap() {
        assertTrue(AgentTabTracker.MAX_OPEN_AGENT_TERMINALS_GLOBAL
            > AgentTabTracker.MAX_OPEN_AGENT_TERMINALS);

        for (int i = 0; i < AgentTabTracker.MAX_OPEN_AGENT_TERMINALS_GLOBAL; i++) {
            tracker.trackTerminal("session-" + i, openContent("Terminal-" + i));
        }

        assertFalse(tracker.hasOpenTerminalCapacity("new-session"));
    }

    @Test
    @DisplayName("untracking requires both owner and stable id")
    void untracksOnlyMatchingOwner() {
        Content content = openContent("Agent: build");
        String terminalId = tracker.trackTerminal("session-a", content);

        tracker.untrackTerminal("session-b", terminalId);
        assertSame(content, tracker.findOwnedTerminal("session-a", terminalId, null).content());

        tracker.untrackTerminal("session-a", terminalId);
        assertNull(tracker.findOwnedTerminal("session-a", terminalId, null));
    }

    @Test
    @DisplayName("list exposes only terminals owned by the caller")
    void listsOnlyOwnedTerminals() {
        Content first = openContent("A");
        Content second = openContent("B");
        tracker.trackTerminal("session-a", first);
        tracker.trackTerminal("session-b", second);

        List<AgentTabTracker.AgentTerminal> owned = tracker.listOpenTerminals("session-a");

        assertEquals(1, owned.size());
        assertSame(first, owned.getFirst().content());
    }

    @Test
    @DisplayName("dispose forgets every owner")
    void disposeForgetsTerminals() {
        tracker.trackTerminal("session-a", openContent("A"));
        tracker.trackTerminal("session-b", openContent("B"));

        tracker.dispose();

        assertTrue(tracker.listOpenTerminals("session-a").isEmpty());
        assertTrue(tracker.listOpenTerminals("session-b").isEmpty());
    }

    @Test
    @DisplayName("closing all terminals is a no-op when ownership is empty")
    void closingAllOnEmptyTrackerIsSafe() {
        assertDoesNotThrow(tracker::closeAllOwnedTerminalTabs);
        assertEquals(0, tracker.countOpenTerminalTabs());
    }

    @Test
    @DisplayName("legacy name matching still supports IDE numeric suffixes")
    void matchesDisplayNameSuffix() {
        assertTrue(AgentTabTracker.terminalTabNameMatches("Agent: build", "Agent: build (2)"));
        assertFalse(AgentTabTracker.terminalTabNameMatches("Agent", "Agent: build"));
        assertFalse(AgentTabTracker.terminalTabNameMatches(null, "Agent: build"));
    }

    private Content openContent(String displayName) {
        Content content = mock(Content.class);
        when(content.getDisplayName()).thenReturn(displayName);
        openContents.add(content);
        return content;
    }
}
