package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.session.db.ConversationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionChoiceTest {

    private static final String NEWEST = "session-newest";
    private static final String OLDER = "session-older";

    private static ConversationService.SessionRecord record(String id) {
        return new ConversationService.SessionRecord(id, "OpenCode", "title", 0L, 0L, 1);
    }

    @Test
    void latestKeepsTheCurrentSessionWhenItIsTheLatest() {
        assertNull(new AcpConnectPanel.SessionChoice.Latest(record(NEWEST)).switchTarget(NEWEST));
    }

    @Test
    void latestSwitchesBackAfterAnOlderSessionWasResumed() {
        assertEquals(NEWEST, new AcpConnectPanel.SessionChoice.Latest(record(NEWEST)).switchTarget(OLDER));
    }

    @Test
    void latestSwitchesWhenNoSessionIsCurrent() {
        assertEquals(NEWEST, new AcpConnectPanel.SessionChoice.Latest(record(NEWEST)).switchTarget(null));
    }

    @Test
    void olderAlwaysSwitches() {
        assertEquals(OLDER, new AcpConnectPanel.SessionChoice.Older(record(OLDER)).switchTarget(NEWEST));
        assertEquals(OLDER, new AcpConnectPanel.SessionChoice.Older(record(OLDER)).switchTarget(OLDER));
    }

    @Test
    void noneNeverSwitches() {
        assertNull(AcpConnectPanel.SessionChoice.None.INSTANCE.switchTarget(NEWEST));
    }
}
