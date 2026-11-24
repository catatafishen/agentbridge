package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalWaitTrackerTest {

    private static final Pattern DONE = Pattern.compile("AB_DONE_\\d+");

    @Test
    void patternMatchEndsWaitImmediately() {
        var t = new TerminalWaitTracker(DONE, "", 0, 10_000);
        assertNull(t.observe("running", 100));
        assertEquals(TerminalWaitTracker.Reason.MATCHED, t.observe("running\nAB_DONE_0", 200));
    }

    @Test
    void echoedMarkerCommandDoesNotMatchPattern() {
        var t = new TerminalWaitTracker(DONE, "", 0, 10_000);
        assertNull(t.observe("$ make; echo AB_DONE_$?", 100));
    }

    @Test
    void patternMatchTakesPrecedenceOverDeadline() {
        var t = new TerminalWaitTracker(DONE, "", 0, 1_000);
        assertEquals(TerminalWaitTracker.Reason.MATCHED, t.observe("AB_DONE_1", 5_000));
    }

    @Test
    void withoutPatternIdleAfterChangeEndsWait() {
        var t = new TerminalWaitTracker(null, "prompt", 0, 60_000);
        assertNull(t.observe("prompt\nout", 500));
        assertNull(t.observe("prompt\nout", 2_000));
        assertEquals(TerminalWaitTracker.Reason.IDLE, t.observe("prompt\nout", 2_500));
    }

    @Test
    void withoutPatternUnchangedOutputWaitsUntilTimeout() {
        var t = new TerminalWaitTracker(null, "prompt", 0, 5_000);
        assertNull(t.observe("prompt", 4_000));
        assertEquals(TerminalWaitTracker.Reason.TIMEOUT, t.observe("prompt", 5_000));
    }

    @Test
    void newOutputResetsIdleTimer() {
        var t = new TerminalWaitTracker(null, "", 0, 60_000);
        assertNull(t.observe("a", 100));
        assertNull(t.observe("ab", 2_000));
        assertNull(t.observe("ab", 3_000));
        assertEquals(TerminalWaitTracker.Reason.IDLE, t.observe("ab", 4_000));
    }

    @Test
    void describeMentionsReason() {
        assertTrue(TerminalWaitTracker.describe(TerminalWaitTracker.Reason.TIMEOUT, 5).contains("timeout"));
    }
}
