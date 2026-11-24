package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Decides when {@code read_terminal_output} may stop waiting. Pure logic with an injected clock, so it can be
 * unit-tested without a terminal.
 *
 * <p>With a {@code wait_for} pattern the wait ends as soon as the buffer matches it. Without one, it ends once
 * the output has changed since the wait started and then stayed unchanged for {@link #IDLE_MS}. In both cases the
 * deadline ends the wait.
 */
final class TerminalWaitTracker {

    /**
     * How long the output must stay unchanged, after having changed, to count as "finished".
     */
    static final long IDLE_MS = 2_000;

    /**
     * Why the wait ended.
     */
    enum Reason {MATCHED, IDLE, TIMEOUT, INTERRUPTED}

    private final @Nullable Pattern pattern;
    private final long deadlineMs;
    private final String baseline;
    private String last;
    private long lastChangeMs;
    private boolean changed;

    TerminalWaitTracker(@Nullable Pattern pattern, @NotNull String baseline, long nowMs, long timeoutMs) {
        this.pattern = pattern;
        this.baseline = baseline;
        this.last = baseline;
        this.lastChangeMs = nowMs;
        this.deadlineMs = nowMs + timeoutMs;
    }

    /**
     * Feeds the current buffer text and returns the reason to stop waiting, or null to keep waiting.
     * A pattern match takes precedence over the deadline.
     */
    @Nullable Reason observe(@NotNull String text, long nowMs) {
        if (pattern != null && pattern.matcher(text).find()) return Reason.MATCHED;
        if (!text.equals(last)) {
            last = text;
            lastChangeMs = nowMs;
            changed = !text.equals(baseline) || changed;
        }
        if (pattern == null && changed && nowMs - lastChangeMs >= IDLE_MS) return Reason.IDLE;
        return nowMs >= deadlineMs ? Reason.TIMEOUT : null;
    }

    static @NotNull String describe(@NotNull Reason reason, long waitedMs) {
        return switch (reason) {
            case MATCHED -> "Wait ended after " + waitedMs + " ms: wait_for pattern matched.";
            case IDLE -> "Wait ended after " + waitedMs + " ms: output stopped changing.";
            case INTERRUPTED -> "Wait interrupted after " + waitedMs + " ms.";
            case TIMEOUT -> "Wait ended after " + waitedMs + " ms: timeout reached, the command may still "
                + "be running.";
        };
    }
}
