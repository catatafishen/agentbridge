package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditNotificationProviderTest {

    @Test
    void bannerTextLeadsWithReviewPendingState() {
        String msg = AgentEditNotificationProvider.formatBannerText(1, 3, 2);

        assertTrue(msg.startsWith("Review pending:"),
            "Banner should lead with the pending-review state: " + msg);
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
