package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The extensible deadline of one {@code sleep} call.
 *
 * <p>The deadline is read by the pooled tool thread and moved by the EDT when the user presses
 * "+10s" / "+30s", so it lives in an {@link AtomicLong}. A sleep can never be stretched past
 * {@code maxDeadlineMs}: the tool's answer still has to reach the MCP client before the client
 * abandons the request (see {@link com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline}).
 *
 * <p>All times are absolute epoch milliseconds so the Java side and the UI countdown agree.
 */
final class SleepDeadline {

    private final long maxDeadlineMs;
    private final AtomicLong deadlineMs;

    /**
     * @param startMs    epoch millis at which the sleep started
     * @param seconds    initial length of the sleep; must already be clamped to {@code maxSeconds}
     * @param maxSeconds longest total sleep, measured from {@code startMs}
     */
    SleepDeadline(long startMs, int seconds, int maxSeconds) {
        this.maxDeadlineMs = startMs + maxSeconds * 1000L;
        this.deadlineMs = new AtomicLong(Math.min(startMs + seconds * 1000L, maxDeadlineMs));
    }

    long deadlineMs() {
        return deadlineMs.get();
    }

    long maxDeadlineMs() {
        return maxDeadlineMs;
    }

    long remainingMs(long nowMs) {
        return Math.max(0L, deadlineMs.get() - nowMs);
    }

    /**
     * Moves the deadline {@code seconds} later, never past {@link #maxDeadlineMs()}.
     *
     * @return the new absolute deadline
     */
    long extend(int seconds) {
        return deadlineMs.updateAndGet(current -> Math.min(current + seconds * 1000L, maxDeadlineMs));
    }
}
