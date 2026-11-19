package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentSessionsTest {

    private static final SubAgentSessions.SessionEnd COMPLETED =
        new SubAgentSessions.SessionEnd(SessionUpdate.ChildSessionState.COMPLETED, null);

    private final SubAgentSessions sessions = new SubAgentSessions();

    @Test
    @DisplayName("a session belongs to the sub-agent whose description is its title")
    void matchesByDescription() {
        sessions.started("sa1", "Search the repo");
        sessions.started("sa2", "Run the tests");

        assertEquals("sa2", sessions.ownerOf("ses_b", "Run the tests"));
        assertEquals("sa1", sessions.ownerOf("ses_a", "Search the repo"));
    }

    @Test
    @DisplayName("a session keeps its owner, even when later updates carry no title")
    void remembersTheOwner() {
        sessions.started("sa1", "Search the repo");
        sessions.ownerOf("ses_a", "Search the repo");

        assertEquals("sa1", sessions.ownerOf("ses_a", null));
        assertEquals("sa1", sessions.ownerOf("ses_a", "Something else"));
    }

    @Test
    @DisplayName("a sub-agent that already has a session does not take another with the same title")
    void oneSessionPerSubAgent() {
        sessions.started("sa1", "Search the repo");
        sessions.ownerOf("ses_a", "Search the repo");

        assertNull(sessions.ownerOf("ses_b", "Search the repo"));
    }

    @Test
    @DisplayName("of two sub-agents with the same description, the most recently started takes a new session")
    void sameDescriptionGoesToTheNewest() {
        sessions.started("stale", "Search the repo");
        sessions.started("new", "Search the repo");

        assertEquals("new", sessions.ownerOf("ses_a", "Search the repo"));
        assertEquals("stale", sessions.ownerOf("ses_b", "Search the repo"));
    }

    @Test
    @DisplayName("a session whose title matches no description has no owner")
    void unknownTitle() {
        sessions.started("sa1", "Search the repo");

        assertNull(sessions.ownerOf("ses_a", "Run the tests"));
    }

    @Test
    @DisplayName("a session without a title goes to the only sub-agent waiting for one, else to none")
    void untitledSession() {
        sessions.started("sa1", "Search the repo");
        assertEquals("sa1", sessions.ownerOf("ses_a", null));

        sessions.started("sa2", "Run the tests");
        sessions.started("sa3", "Lint");
        assertNull(sessions.ownerOf("ses_b", " "));
    }

    @Test
    @DisplayName("a later description replaces the first, a blank one keeps it")
    void descriptionUpdates() {
        sessions.started("sa1", null);
        sessions.describe("sa1", "Search the repo");
        sessions.describe("sa1", "  ");

        assertEquals("sa1", sessions.ownerOf("ses_a", "  Search the repo "));
    }

    @Test
    @DisplayName("a description for a sub-agent no longer tracked does not track it again")
    void describeIgnoresAForgottenSubAgent() {
        sessions.started("sa1", "Search the repo");
        sessions.forget("sa1");

        sessions.describe("sa1", "Search the repo");

        assertNull(sessions.ownerOf("ses_a", "Search the repo"));
        assertNull(sessions.ownerOf("ses_b", null));
    }

    @Test
    @DisplayName("the launch result binds the session, whatever the title said")
    void launchBindsTheSession() {
        sessions.started("sa1", "Search the repo");

        assertNull(sessions.launchedInBackground("sa1", "ses_a"));

        assertEquals("sa1", sessions.ownerOf("ses_a", "Renamed by the agent"));
    }

    @Test
    @DisplayName("a background sub-agent finishes with the tool calls it made")
    void finishReturnsTheBackgroundSubAgent() {
        sessions.started("sa1", "Search the repo");
        sessions.launchedInBackground("sa1", "ses_a");
        sessions.addChildTool("sa1", new SubAgentSessions.ChildTool("acp-1", "rec-1"));

        var finished = sessions.finish("ses_a", COMPLETED);

        assertNotNull(finished);
        assertEquals("sa1", finished.getRecordId());
        assertEquals(List.of(new SubAgentSessions.ChildTool("acp-1", "rec-1")), finished.getChildren());
        assertNull(sessions.finish("ses_a", COMPLETED), "finished only once");
        assertNull(sessions.ownerOf("ses_a", "Search the repo"), "its session is no longer routed");
    }

    @Test
    @DisplayName("a sub-agent still in its launch tool call does not finish through its session")
    void finishWaitsForTheLaunch() {
        sessions.started("sa1", "Search the repo");
        sessions.ownerOf("ses_a", "Search the repo");

        assertNull(sessions.finish("ses_a", COMPLETED));
        assertEquals("sa1", sessions.ownerOf("ses_a", null), "still tracked");
        assertNull(sessions.takeEndedBeforeLaunch("sa1"), "not in the background");
    }

    @Test
    @DisplayName("a session that ended before the launch result ends its sub-agent once it is in the background")
    void endBeforeTheLaunchResult() {
        var failed = new SubAgentSessions.SessionEnd(SessionUpdate.ChildSessionState.FAILED, "rate limited");
        sessions.started("sa1", "Search the repo");
        sessions.ownerOf("ses_a", "Search the repo");
        sessions.addChildTool("sa1", new SubAgentSessions.ChildTool("acp-1", "rec-1"));
        sessions.finish("ses_a", failed);

        sessions.launchedInBackground("sa1", "ses_a");
        var ended = sessions.takeEndedBeforeLaunch("sa1");

        assertNotNull(ended);
        assertEquals("sa1", ended.getFirst().getRecordId());
        assertEquals(List.of(new SubAgentSessions.ChildTool("acp-1", "rec-1")), ended.getFirst().getChildren());
        assertEquals(failed, ended.getSecond());
        assertTrue(sessions.takeAll().isEmpty(), "no longer tracked");
    }

    @Test
    @DisplayName("a background sub-agent whose session has not ended stays tracked after its launch")
    void noEndBeforeTheLaunchResult() {
        sessions.started("sa1", "Search the repo");
        sessions.launchedInBackground("sa1", "ses_a");

        assertNull(sessions.takeEndedBeforeLaunch("sa1"));
        assertNotNull(sessions.finish("ses_a", COMPLETED));
    }

    @Test
    @DisplayName("a follow-up's entry takes the session over and the earlier background entry is returned")
    void followUpTakesTheSessionOver() {
        sessions.started("sa1", "Search the repo");
        sessions.launchedInBackground("sa1", "ses_a");
        sessions.started("sa2", "Search the tests too");

        var previous = sessions.launchedInBackground("sa2", "ses_a");

        assertNotNull(previous);
        assertEquals("sa1", previous.getRecordId());
        assertEquals("sa2", sessions.ownerOf("ses_a", null));
        assertEquals("sa2", sessions.finish("ses_a", COMPLETED).getRecordId());
    }

    @Test
    @DisplayName("a follow-up after the sub-agent finished binds the session again")
    void followUpAfterFinish() {
        sessions.started("sa1", "Search the repo");
        sessions.launchedInBackground("sa1", "ses_a");
        sessions.finish("ses_a", COMPLETED);
        sessions.started("sa2", "Search the tests too");

        assertNull(sessions.launchedInBackground("sa2", "ses_a"));

        assertEquals("sa2", sessions.ownerOf("ses_a", null));
    }

    @Test
    @DisplayName("takeAll returns the background sub-agents in start order and forgets every sub-agent")
    void takeAll() {
        sessions.started("sa1", "First");
        sessions.started("sa2", "Second");
        sessions.started("sa3", "Still launching");
        sessions.launchedInBackground("sa2", "ses_b");
        sessions.launchedInBackground("sa1", "ses_a");

        var all = sessions.takeAll();

        assertEquals(List.of("sa1", "sa2"), all.stream().map(SubAgentSessions.Finished::getRecordId).toList());
        assertTrue(sessions.takeAll().isEmpty());
        assertNull(sessions.ownerOf("ses_c", "Still launching"));
    }

    @Test
    @DisplayName("nothing is matched to a forgotten sub-agent, and its tool calls are ignored")
    void forget() {
        sessions.started("sa1", "Search the repo");
        sessions.ownerOf("ses_a", "Search the repo");

        sessions.forget("sa1");
        sessions.addChildTool("sa1", new SubAgentSessions.ChildTool("acp-1", "rec-1"));

        assertNull(sessions.ownerOf("ses_a", "Search the repo"));
        assertTrue(sessions.takeAll().isEmpty());
    }

    @Test
    @DisplayName("a tool title loses the '<session title>: ' prefix OpenCode adds")
    void toolTitleStripsThePrefix() {
        assertEquals("grep", SubAgentSessions.toolTitle("Search the repo", "Search the repo: grep"));
    }

    @Test
    @DisplayName("a tool title without the prefix, or with only the prefix, is kept as it is")
    void toolTitleWithoutPrefix() {
        assertEquals("grep", SubAgentSessions.toolTitle("Search the repo", "grep"));
        assertEquals("Search the repo: ", SubAgentSessions.toolTitle("Search the repo", "Search the repo: "));
        assertEquals("Other: grep", SubAgentSessions.toolTitle(null, "Other: grep"));
    }
}
