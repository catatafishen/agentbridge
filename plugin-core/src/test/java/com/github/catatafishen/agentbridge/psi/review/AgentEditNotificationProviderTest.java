package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditNotificationProviderTest {

    @Test
    void bannerTextExplicitlyBlocksCommitAndPush() {
        String msg = AgentEditNotificationProvider.formatBannerText(1, 3, 2);

        assertTrue(msg.startsWith("REVIEW PENDING"),
            "Banner should lead with the pending-review state: " + msg);
        assertTrue(msg.contains("user has not reviewed this file yet"),
            "Banner should say the user has not reviewed the file yet: " + msg);
        assertTrue(msg.contains("Do not commit or push"),
            "Banner should explicitly block commit/push: " + msg);
        assertTrue(msg.contains("File 1/3"),
            "Banner should still include the file counter: " + msg);
        assertTrue(msg.contains("2 changes"),
            "Banner should still include the change counter: " + msg);
    }

    @Test
    void bannerTextFallsBackWhenCountersAreEmpty() {
        String msg = AgentEditNotificationProvider.formatBannerText(0, 0, 0);

        assertTrue(msg.contains("No outstanding changes"),
            "Banner should remain readable when counters are unavailable: " + msg);
    }

    @Test
    void bannerTextIsDeterministic() {
        assertEquals(
            AgentEditNotificationProvider.formatBannerText(2, 5, 4),
            AgentEditNotificationProvider.formatBannerText(2, 5, 4)
        );
    }
}
