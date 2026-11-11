package com.github.catatafishen.agentbridge.psi.java;

import org.jetbrains.annotations.NotNull;

/**
 * Thread-safe text buffer that retains only the last {@code limit} characters.
 * <p>
 * Used to capture build output without keeping a verbose build's whole log in memory.
 */
final class BoundedTail {

    private final int limit;
    private final StringBuilder text = new StringBuilder();
    private boolean dropped;

    BoundedTail(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        this.limit = limit;
    }

    synchronized void append(@NotNull String chunk) {
        text.append(chunk);
        // Trim in batches (at 2x the limit) so a stream of small chunks does not copy the buffer every time.
        if (text.length() > 2 * limit) {
            text.delete(0, text.length() - limit);
            dropped = true;
        }
    }

    /**
     * @return the last {@code limit} characters appended
     */
    synchronized @NotNull String text() {
        return text.length() > limit ? text.substring(text.length() - limit) : text.toString();
    }

    /**
     * @return true if any earlier output is no longer present in {@link #text()}
     */
    synchronized boolean isTruncated() {
        return dropped || text.length() > limit;
    }
}
