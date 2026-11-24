package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The poll loop behind the terminal waits: reads the terminal text repeatedly and asks a
 * {@link TerminalWaitTracker} whether to stop. The text source, the clock and the sleep are injected, so the loop
 * is testable without a terminal and without real time passing.
 */
final class TerminalWaitLoop {

    /**
     * Sleeps between two polls.
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * What the wait produced: why it ended, how long it took, the last text read, and the text the
     * {@code wait_for} pattern matched (null unless the reason is {@link TerminalWaitTracker.Reason#MATCHED}).
     */
    record Outcome(
        @NotNull TerminalWaitTracker.Reason reason,
        long waitedMs,
        @NotNull String text,
        @Nullable String matched
    ) {
    }

    private TerminalWaitLoop() {
    }

    /**
     * Polls until the tracker says stop. The first read is the baseline for idle detection. An interrupted sleep
     * restores the interrupt flag and ends the wait with {@link TerminalWaitTracker.Reason#INTERRUPTED}.
     */
    static @NotNull Outcome run(
        @NotNull Supplier<String> reader,
        @Nullable Pattern pattern,
        long timeoutMs,
        long pollIntervalMs,
        @NotNull LongSupplier clock,
        @NotNull Sleeper sleeper
    ) {
        long start = clock.getAsLong();
        String baseline = orEmpty(reader.get());
        var tracker = new TerminalWaitTracker(pattern, baseline, start, timeoutMs);
        String text;
        while (true) {
            text = orEmpty(reader.get());
            long now = clock.getAsLong();
            var reason = tracker.observe(text, now);
            if (reason != null) return new Outcome(reason, now - start, text, matchedText(reason, pattern, text));
            try {
                sleeper.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(TerminalWaitTracker.Reason.INTERRUPTED, clock.getAsLong() - start, text, null);
            }
        }
    }

    private static @Nullable String matchedText(
        @NotNull TerminalWaitTracker.Reason reason, @Nullable Pattern pattern, @NotNull String text
    ) {
        if (reason != TerminalWaitTracker.Reason.MATCHED || pattern == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static @NotNull String orEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }
}
