package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the error wording returned by {@link AgentEditSession#awaitReviewCompletion}
 * when a git tool's blocking wait expires. The message must make clear that the block is
 * caused by files that have not been approved or rejected — not a generic timeout — so
 * the agent surfaces actionable guidance to the user instead of retrying blindly.
 */
class ReviewPendingMessageTest {

    @Test
    void mentionsFileCountAndOperation() {
        String msg = AgentEditSession.formatReviewTimeoutError("git commit", 3);
        assertTrue(msg.startsWith("Error:"),
            "Message must be flagged as an Error: " + msg);
        assertTrue(msg.contains("'git commit'"),
            "Message must name the blocked operation: " + msg);
        assertTrue(msg.contains("3 files"),
            "Message must report the file count (plural): " + msg);
        assertTrue(msg.contains("not been approved or rejected"),
            "Message must clarify the pending state: " + msg);
        assertTrue(msg.contains("Review panel"),
            "Message must direct the user to the Review panel: " + msg);
    }

    @Test
    void singularPhrasingForOneFile() {
        String msg = AgentEditSession.formatReviewTimeoutError("git merge 'main'", 1);
        assertTrue(msg.contains("1 file has"),
            "Singular form expected: " + msg);
        assertTrue(!msg.contains("1 files"),
            "Plural form must not appear for a single file: " + msg);
    }

    @Test
    void includesRetryGuidance() {
        String msg = AgentEditSession.formatReviewTimeoutError("git branch switch 'feat/x'", 2);
        assertTrue(msg.toLowerCase().contains("retry"),
            "Message must tell the agent it can retry after review completion: " + msg);
    }

    @Test
    void formatIsDeterministic() {
        String a = AgentEditSession.formatReviewTimeoutError("git commit", 5);
        String b = AgentEditSession.formatReviewTimeoutError("git commit", 5);
        assertEquals(a, b, "Formatter must be deterministic for the same inputs");
    }

    @Test
    void cannotProceedMeaning() {
        String msg = AgentEditSession.formatReviewTimeoutError("git commit", 2);
        assertTrue(msg.contains("cannot proceed"),
            "Message must say the operation cannot proceed: " + msg);
    }
}
