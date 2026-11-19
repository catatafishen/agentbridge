package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Waits for a tool call with a timeout that counts only time the tool spends running, not time
 * it spends waiting for the user.
 * <p>
 * An MCP call whose permission is ASK blocks its worker on the approval card (up to 120 s, plus
 * any time queued behind earlier prompts in {@code PermissionPromptQueue}). The generic "tool is
 * still running" dialog fires after {@code ChatInputSettings.toolTimeoutSeconds} (60 s by
 * default), so without this it would pop up over the approval the user is still reading. While
 * {@code waitingForUser} reports true the clock is held at a full window; it starts running once
 * the user has answered.
 * <p>
 * Pure logic, no platform dependencies, so it is unit-testable on its own.
 */
final class ToolRunTimeout {

    /** How often the wait re-checks whether the worker is (still) waiting for the user. */
    static final long DEFAULT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private ToolRunTimeout() {
    }

    /**
     * Returns the result of {@code future} once it completes, or throws {@link TimeoutException}
     * after {@code timeoutNanos} of run time.
     *
     * @param waitingForUser true while the call is blocked on the user (approval shown or queued);
     *                       that time does not count towards the timeout
     * @param pollNanos      how often {@code waitingForUser} is re-checked
     */
    static <T> T await(@NotNull CompletableFuture<T> future, long timeoutNanos,
                       @NotNull BooleanSupplier waitingForUser, long pollNanos)
        throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            if (waitingForUser.getAsBoolean()) {
                deadline = System.nanoTime() + timeoutNanos;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("tool ran longer than " + TimeUnit.NANOSECONDS.toSeconds(timeoutNanos) + "s");
            }
            try {
                return future.get(Math.min(remaining, pollNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException slice) {
                // Only this slice expired: re-check whether the worker is waiting for the user.
            }
        }
    }
}
