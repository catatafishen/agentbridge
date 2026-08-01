package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void noFixesRenderNothing() {
        assertEquals("", GetHighlightsTool.formatFixLines(List.of()));
    }

    @Test
    void fixesUnderTheCapAreListedInFull() {
        assertEquals("\n    Fix: a\n    Fix: b", GetHighlightsTool.formatFixLines(List.of("a", "b")));
    }

    @Test
    void fixesAboveTheCapAreTruncatedWithARemainderCount() {
        // Regression for #908: inspections offering 8 fixes used to emit 8 lines per problem.
        String rendered = GetHighlightsTool.formatFixLines(
            List.of("a", "b", "c", "d", "e", "f", "g", "h"));
        String expected = """

                Fix: a
                Fix: b
                Fix: c
                Fix: … (5 more, use get_available_actions)\
            """;
        assertEquals(expected, rendered);
    }
}
