package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ToolTimeoutDialog}, focusing on the non-EDT code paths that
 * do not require a running Swing event loop.
 */
class ToolTimeoutDialogTest {

    @Test
    void returnsCompletedImmediatelyWhenFutureAlreadyDone() throws InterruptedException {
        // If the future is already done, askForExtension must fast-path and never open a dialog.
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        // project=null is safe: the fast path returns before the project is used.
        int result = ToolTimeoutDialog.askForExtension(null, "test-op", 30, done);
        assertEquals(ToolTimeoutDialog.COMPLETED, result);
    }

    @Test
    void completedConstantHasDistinctValueFromOtherConstants() {
        // COMPLETED must not collide with CANCEL (0) or any positive wait-time value.
        assertEquals(-1, ToolTimeoutDialog.COMPLETED);
        assertEquals(0, ToolTimeoutDialog.CANCEL);
        assertEquals(Integer.MAX_VALUE, ToolTimeoutDialog.INDEFINITE);
    }
}
