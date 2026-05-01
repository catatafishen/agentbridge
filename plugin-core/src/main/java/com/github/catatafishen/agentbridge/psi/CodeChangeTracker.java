package com.github.catatafishen.agentbridge.psi;

import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks cumulative lines added/removed by agent editing tools.
 * Per-turn counters are cleared at the start of each turn.
 * Session counters accumulate across all turns until explicitly reset.
 * Listeners are notified on each change for real-time UI updates.
 */
public final class CodeChangeTracker {

    private static final AtomicInteger linesAdded = new AtomicInteger();
    private static final AtomicInteger linesRemoved = new AtomicInteger();
    private static final AtomicInteger sessionLinesAdded = new AtomicInteger();
    private static final AtomicInteger sessionLinesRemoved = new AtomicInteger();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private CodeChangeTracker() {
    }

    public static void recordChange(int added, int removed) {
        if (added > 0) {
            linesAdded.addAndGet(added);
            sessionLinesAdded.addAndGet(added);
        }
        if (removed > 0) {
            linesRemoved.addAndGet(removed);
            sessionLinesRemoved.addAndGet(removed);
        }
        notifyListeners();
    }

    /** Returns current [added, removed] without clearing. */
    public static int[] get() {
        return new int[]{linesAdded.get(), linesRemoved.get()};
    }

    /** Returns [added, removed] and resets per-turn counters to zero. */
    public static int[] getAndClear() {
        return new int[]{linesAdded.getAndSet(0), linesRemoved.getAndSet(0)};
    }

    /** Resets per-turn counters without reading. */
    public static void clear() {
        linesAdded.set(0);
        linesRemoved.set(0);
    }

    /** Returns cumulative [added, removed] across all turns in this session. */
    public static int[] getSessionTotal() {
        return new int[]{sessionLinesAdded.get(), sessionLinesRemoved.get()};
    }

    /** Resets session-level counters (e.g. on conversation clear). */
    public static void clearSession() {
        sessionLinesAdded.set(0);
        sessionLinesRemoved.set(0);
    }

    /** Registers a listener called on each {@link #recordChange} invocation. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Removes a previously registered listener. */
    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /**
     * Computes exact lines added and removed between two content strings using IntelliJ's line diff.
     * Returns [added, removed].
     */
    public static int[] diffLines(String before, String after) {
        if (before.equals(after)) return new int[]{0, 0};
        String[] beforeLines = Diff.splitLines(before);
        String[] afterLines = Diff.splitLines(after);
        int added = 0;
        int removed = 0;
        try {
            Diff.Change change = Diff.buildChanges(beforeLines, afterLines);
            for (Diff.Change c = change; c != null; c = c.link) {
                added += c.inserted;
                removed += c.deleted;
            }
        } catch (FilesTooBigForDiffException ignored) {
            // Fall back to simple line count difference for very large files
            added = afterLines.length;
            removed = beforeLines.length;
        }
        return new int[]{added, removed};
    }

    /** Counts lines in a string (number of newlines + 1, or 0 for empty). */
    public static int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') count++;
        }
        return count;
    }
}
