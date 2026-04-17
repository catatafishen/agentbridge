package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the timeout error wording returned by {@link AgentEditSession#awaitReviewCompletion}
 * when a git tool's blocking wait expires. The message must make clear that the block is
 * caused by unresolved review — not a generic timeout — so the agent surfaces actionable
 * guidance to the user instead of retrying blindly.
 */
class ReviewPendingMessageTest {

    @Test
    void mentionsTimeoutAndOperation() {
        String msg = AgentEditSession.formatReviewTimeoutError("git commit", 3);
        assertTrue(msg.startsWith("Error: Timed out"),
            "Message must be flagged as an Error and mention the timeout: " + msg);
        assertTrue(msg.contains("'git commit'"),
            "Message must name the blocked operation: " + msg);
        assertTrue(msg.contains("3 agent-edited files"),
            "Message must report the file count (plural): " + msg);
        assertTrue(msg.contains("Review panel"),
            "Message must direct the user to the Review panel: " + msg);
    }

    @Test
    void singularPhrasingForOneFile() {
        String msg = AgentEditSession.formatReviewTimeoutError("git merge 'main'", 1);
        assertTrue(msg.contains("1 agent-edited file"),
            "Singular form expected: " + msg);
        assertTrue(!msg.contains("1 agent-edited files"),
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
}
