package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundSubAgentsTest {

    private final BackgroundSubAgents subAgents = new BackgroundSubAgents();

    @Test
    @DisplayName("a tool call titled '<description>: <tool>' belongs to that sub-agent and keeps the tool's name")
    void matchesToolCallsByDescription() {
        subAgents.launched("sa1", "Run ping and count replies");

        var owner = subAgents.ownerOf("Run ping and count replies: execute");

        assertNotNull(owner);
        assertEquals("sa1", owner.getRecordId());
        assertEquals("execute", owner.getToolTitle());
    }

    @Test
    @DisplayName("tool calls of the main agent and of other sub-agents are not claimed")
    void ignoresOtherToolCalls() {
        subAgents.launched("sa1", "Run ping");

        assertNull(subAgents.ownerOf("read_file"));
        assertNull(subAgents.ownerOf("Run ping"), "the description alone is no tool call");
        assertNull(subAgents.ownerOf("Run ping: "), "a prefix without a tool name");
        assertNull(subAgents.ownerOf("Run pings: execute"));
    }

    @Test
    @DisplayName("when descriptions overlap, the longest matching one wins")
    void longestDescriptionWins() {
        subAgents.launched("short", "Fix tests");
        subAgents.launched("long", "Fix tests: unit");

        var owner = subAgents.ownerOf("Fix tests: unit: execute");

        assertNotNull(owner);
        assertEquals("long", owner.getRecordId());
        assertEquals("execute", owner.getToolTitle());
        var shortOwner = subAgents.ownerOf("Fix tests: execute");
        assertNotNull(shortOwner);
        assertEquals("short", shortOwner.getRecordId());
    }

    @Test
    @DisplayName("a sub-agent without a description claims no tool calls")
    void noDescriptionMatchesNothing() {
        subAgents.launched("sa1", null);
        subAgents.launched("sa2", "  ");

        assertNull(subAgents.ownerOf(": execute"));
        assertNull(subAgents.ownerOf("  : execute"));
    }

    @Test
    @DisplayName("with one sub-agent running, the next report is its own")
    void takesTheOnlyOne() {
        subAgents.launched("sa1", "Run ping");
        subAgents.addChildTool("sa1", new BackgroundSubAgents.ChildTool("acp-1", "rec-1"));

        var finished = subAgents.takeIfOnlyOne();

        assertNotNull(finished);
        assertEquals("sa1", finished.getRecordId());
        assertEquals(List.of(new BackgroundSubAgents.ChildTool("acp-1", "rec-1")), finished.getChildren());
        assertNull(subAgents.ownerOf("Run ping: execute"), "a finished sub-agent is no longer tracked");
    }

    @Test
    @DisplayName("with none or several running, a report cannot be attributed")
    void noneOrSeveralCannotBeAttributed() {
        assertNull(subAgents.takeIfOnlyOne());

        subAgents.launched("sa1", "One");
        subAgents.launched("sa2", "Two");

        assertNull(subAgents.takeIfOnlyOne());
        assertNotNull(subAgents.ownerOf("One: execute"), "both are still tracked");
    }

    @Test
    @DisplayName("takeAll returns every running sub-agent in launch order and forgets them")
    void takeAllEmptiesTheTracker() {
        subAgents.launched("sa2", "Second");
        subAgents.launched("sa1", "First");
        subAgents.addChildTool("sa1", new BackgroundSubAgents.ChildTool("acp-1", "rec-1"));

        var all = subAgents.takeAll();

        assertEquals(List.of("sa2", "sa1"), all.stream().map(BackgroundSubAgents.Finished::getRecordId).toList());
        assertEquals(1, all.get(1).getChildren().size());
        assertTrue(subAgents.takeAll().isEmpty());
    }

    @Test
    @DisplayName("tool calls reported for an unknown or finished sub-agent are ignored")
    void childOfUnknownSubAgentIsIgnored() {
        subAgents.addChildTool("missing", new BackgroundSubAgents.ChildTool("acp-1", "rec-1"));
        subAgents.launched("sa1", "Run ping");

        var finished = subAgents.takeIfOnlyOne();

        assertNotNull(finished);
        assertTrue(finished.getChildren().isEmpty());
    }
}
