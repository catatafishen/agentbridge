package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopilotPolicyTest {

    @Test
    void autopilotPromotesAskToAllow() {
        assertEquals(ToolPermission.ALLOW, AutopilotPolicy.apply(ToolPermission.ASK, true));
    }

    @Test
    void autopilotNeverRelaxesDeny() {
        assertEquals(ToolPermission.DENY, AutopilotPolicy.apply(ToolPermission.DENY, true));
    }

    @Test
    void autopilotLeavesAllowUnchanged() {
        assertEquals(ToolPermission.ALLOW, AutopilotPolicy.apply(ToolPermission.ALLOW, true));
    }

    @Test
    void supervisedLeavesEveryPermissionUnchanged() {
        for (ToolPermission permission : ToolPermission.values()) {
            assertEquals(permission, AutopilotPolicy.apply(permission, false));
        }
    }

    @Test
    void onlyTheAutopilotValueEnablesAutopilot() {
        assertTrue(AutopilotPolicy.isAutopilot(AutopilotPolicy.AUTOPILOT));
        assertFalse(AutopilotPolicy.isAutopilot(AutopilotPolicy.SUPERVISED));
        assertFalse(AutopilotPolicy.isAutopilot(""));
        assertFalse(AutopilotPolicy.isAutopilot(null));
    }
}
