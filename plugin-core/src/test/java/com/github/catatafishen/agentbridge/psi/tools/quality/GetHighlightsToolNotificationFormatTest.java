package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetHighlightsToolNotificationFormatTest {

    @Test
    void reviewBannerGetsAgentOnlyReviewNote() {
        String formatted = GetHighlightsTool.formatEditorNotificationForAgent(
            "[BANNER] Review pending: File 1/2 · 3 changes");

        assertTrue(formatted.contains("[REVIEW_PENDING]"),
            "Review banners should be expanded with an agent-only review note: " + formatted);
        assertTrue(formatted.contains("must wait for approval or rejection"),
            "The note should explicitly block agent-side git operations: " + formatted);
    }

    @Test
    void nonReviewNotificationsPassThroughUnchanged() {
        String notification = "[BANNER] SDK mismatch";

        assertEquals(notification, GetHighlightsTool.formatEditorNotificationForAgent(notification));
    }
}
