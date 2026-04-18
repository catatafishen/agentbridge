package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pure data shape of {@link ReviewItem} with the v2 fields
 * ({@code approvalState}, {@code lastEditedMillis}, {@code linesAdded},
 * {@code linesRemoved}). Persistence and behavioural tests for
 * {@link AgentEditSession} live in integration tests because they need
 * the IntelliJ Project + DocumentManager infrastructure.
 */
class ReviewItemV2FieldsTest {

    @Test
    void approvedReturnsTrueOnlyForApprovedState() {
        ReviewItem pending = new ReviewItem("/p/Foo.java", "Foo.java",
            ReviewItem.Status.MODIFIED, "before",
            ApprovalState.PENDING, 1_700_000_000_000L, 5, 2);
        ReviewItem approved = new ReviewItem("/p/Foo.java", "Foo.java",
            ReviewItem.Status.MODIFIED, "before",
            ApprovalState.APPROVED, 1_700_000_000_000L, 5, 2);

        assertFalse(pending.approved());
        assertTrue(approved.approved());
    }

    @Test
    void linesCountsAreExposed() {
        ReviewItem item = new ReviewItem("/p/Foo.java", "Foo.java",
            ReviewItem.Status.MODIFIED, "before",
            ApprovalState.PENDING, 0L, 12, 7);
        assertEquals(12, item.linesAdded());
        assertEquals(7, item.linesRemoved());
    }

    @Test
    void lastEditedMillisIsExposed() {
        ReviewItem item = new ReviewItem("/p/Foo.java", "Foo.java",
            ReviewItem.Status.MODIFIED, null,
            ApprovalState.PENDING, 1_234_567_890L, 0, 0);
        assertEquals(1_234_567_890L, item.lastEditedMillis());
    }

    @Test
    void approvalStateRoundTripsThroughEnumName() {
        // Persistence stores the enum as its name() — round-trip safety check
        // for the load path in AgentEditSession.PersistedState.
        for (ApprovalState state : ApprovalState.values()) {
            assertEquals(state, ApprovalState.valueOf(state.name()));
        }
    }
}
