package com.github.catatafishen.agentbridge.ui.side;

import org.jetbrains.annotations.NotNull;

/**
 * Animates a single pair of (added, removed) diff counts using linear interpolation.
 * Used by {@link SessionStatsPanel} to animate lines-changed numbers when they update.
 * <p>
 * Mirrors the interpolation approach used by the diff tab's per-file animator
 * but tracks a single value pair instead of per-file state.
 */
final class SessionDiffAnimator {

    static final long ANIMATION_DURATION_MS = 600L;

    private int startAdded;
    private int startRemoved;
    private int targetAdded;
    private int targetRemoved;
    private long startMillis;

    /**
     * Updates the target values. If they changed, captures the current display value
     * as the new start and begins a fresh animation.
     */
    void update(int added, int removed, long nowMillis) {
        if (targetAdded == added && targetRemoved == removed) {
            return;
        }
        DiffCounts current = displayCounts(nowMillis);
        startAdded = current.added();
        startRemoved = current.removed();
        targetAdded = added;
        targetRemoved = removed;
        startMillis = nowMillis;
    }

    /**
     * Returns the interpolated counts for the current moment.
     */
    @NotNull DiffCounts displayCounts(long nowMillis) {
        if (!isAnimating(nowMillis)) {
            return new DiffCounts(targetAdded, targetRemoved);
        }
        double progress = Math.max(0.0d, Math.min(1.0d, (double) (nowMillis - startMillis) / ANIMATION_DURATION_MS));
        return new DiffCounts(
            interpolate(startAdded, targetAdded, progress),
            interpolate(startRemoved, targetRemoved, progress)
        );
    }

    boolean isAnimating(long nowMillis) {
        return (startAdded != targetAdded || startRemoved != targetRemoved)
            && nowMillis - startMillis < ANIMATION_DURATION_MS;
    }

    private static int interpolate(int start, int target, double progress) {
        return (int) Math.round(start + (target - start) * progress);
    }

    record DiffCounts(int added, int removed) {
    }
}
