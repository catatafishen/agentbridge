package com.github.catatafishen.ideagentforcopilot.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveAgentManagerTest {

    @Test
    void normalizeSharedTurnTimeoutMinutesUsesStoredValueOrDefaultAndClamps() {
        assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("2000"));
        assertEquals(120, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("bad"));
        assertEquals(120, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(null));
    }

    @Test
    void normalizeSharedInactivityTimeoutSecondsUsesStoredValueOrDefaultAndClamps() {
        assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("10"));
        assertEquals(3000, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("bad"));
        assertEquals(3000, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds(null));
    }

    @Test
    void normalizeSharedMaxToolCallsPerTurnUsesStoredValueAndClamps() {
        assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("-5", 17));
        assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("bad", 17));
        assertEquals(17, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, 17));
    }
}
