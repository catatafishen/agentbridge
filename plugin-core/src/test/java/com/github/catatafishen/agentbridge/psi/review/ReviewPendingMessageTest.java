package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the message wording returned by {@link AgentEditSession#checkReviewPending}
 * when a git tool is gated. The message must explain to the agent that the block is
 * caused by pending review — not a generic timeout — so the agent surfaces actionable
 * guidance to the user instead of retrying blindly.
 */
class ReviewPendingMessageTest {

    @Test
    void mentionsPendingReviewAndOperation() {
        String msg = AgentEditSession.formatReviewPendingError("git commit", 3);
        assertTrue(msg.startsWith("Error: Review pending"),
            "Message must be flagged as an Error and mention review pending: " + msg);
        assertTrue(msg.contains("'git commit'"),
            "Message must name the blocked operation: " + msg);
        assertTrue(msg.contains("3 agent-edited files"),
            "Message must report the file count (plural): " + msg);
        assertTrue(msg.contains("Review panel"),
            "Message must direct the user to the Review panel: " + msg);
    }

    @Test
    void singularPhrasingForOneFile() {
        String msg = AgentEditSession.formatReviewPendingError("git merge 'main'", 1);
        assertTrue(msg.contains("1 agent-edited file"),
            "Singular form expected: " + msg);
        assertTrue(!msg.contains("1 agent-edited files"),
            "Plural form must not appear for a single file: " + msg);
    }

    @Test
    void includesRetryGuidance() {
        String msg = AgentEditSession.formatReviewPendingError("git branch switch 'feat/x'", 2);
        assertTrue(msg.toLowerCase().contains("retry"),
            "Message must tell the agent it can retry after review completion: " + msg);
    }

    @Test
    void formatIsDeterministic() {
        String a = AgentEditSession.formatReviewPendingError("git commit", 5);
        String b = AgentEditSession.formatReviewPendingError("git commit", 5);
        assertEquals(a, b, "Formatter must be deterministic for the same inputs");
    }
}
