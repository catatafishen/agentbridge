package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeBackgroundSubAgentTest {

    /** The result OpenCode 2.3.2's subagent tool returned in a live session. */
    private static final String LAUNCH_RESULT = """
        The subagent is working in the background (sessionID: ses_1f2e3d4c5b6aABCdef). You will receive a \
        <subagent> message when it goes idle. DO NOT sleep, poll, or wait for it; continue with other work \
        or end your turn. To send it follow-ups, call subagent again with this sessionID.""";

    @Test
    @DisplayName("the note of a sub-agent started in the background gives the sub-agent's session id")
    void readsTheSessionIdOfTheLaunchNote() {
        assertEquals("ses_1f2e3d4c5b6aABCdef", OpenCodeBackgroundSubAgent.sessionIdOfLaunch(LAUNCH_RESULT));
        assertEquals("ses_1f2e3d4c5b6aABCdef", OpenCodeBackgroundSubAgent.sessionIdOfLaunch("\n  " + LAUNCH_RESULT),
            "leading blank lines");
    }

    @Test
    @DisplayName("a finished sub-agent's answer is not a launch result, even when it quotes the note")
    void finishedAnswerIsNotALaunch() {
        assertNull(OpenCodeBackgroundSubAgent.sessionIdOfLaunch("""
            <task id="ses_abc" state="completed">
            <task_result>
            Found 3 usages.
            </task_result>
            </task>"""));
        assertNull(OpenCodeBackgroundSubAgent.sessionIdOfLaunch("OpenCode said: " + LAUNCH_RESULT));
    }

    @Test
    @DisplayName("no result, an empty one, or the note without a session id is not a launch result")
    void missingOrIncompleteResult() {
        assertNull(OpenCodeBackgroundSubAgent.sessionIdOfLaunch(null));
        assertNull(OpenCodeBackgroundSubAgent.sessionIdOfLaunch(""));
        assertNull(OpenCodeBackgroundSubAgent.sessionIdOfLaunch("The subagent is working in the background."));
    }
}
