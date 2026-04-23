package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetHighlightsToolNotificationFormatTest {

    @Test
    void agentEditReviewBannerIsHiddenFromAgent() {
        // The "Edited by agent" banner exists for the human reviewer; surfacing it back to
        // the agent that produced the edit is noise. The git-side gate still blocks
        // commit/push when there are pending changes — see AgentEditSession.isGateActive.
        assertFalse(GetHighlightsTool.isVisibleToAgent(
            "[BANNER] Edited by agent: File 1/2 · 3 changes"));
    }

    @Test
    void legacyReviewPendingBannerIsAlsoHiddenFromAgent() {
        // Defensive: older snapshots / cached editors may still render the previous wording.
        assertFalse(GetHighlightsTool.isVisibleToAgent(
            "[BANNER] Review pending: File 1/2 · 3 changes"));
    }

    @Test
    void unrelatedNotificationsArePassedToAgent() {
        assertTrue(GetHighlightsTool.isVisibleToAgent("[BANNER] SDK mismatch"));
    }
}
