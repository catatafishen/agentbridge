package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReviewDiffCountAnimator {

    static final long ANIMATION_DURATION_MS = 600L;

    private final Map<String, DiffCountState> states = new HashMap<>();

    void sync(@NotNull List<ReviewItem> items, long nowMillis) {
        Set<String> livePaths = new HashSet<>(items.size());
        for (ReviewItem item : items) {
            livePaths.add(item.path());
            DiffCountState state = states.get(item.path());
            if (state == null) {
                states.put(item.path(), DiffCountState.animateFromZero(item.linesAdded(), item.linesRemoved(), nowMillis));
            } else {
                state.retarget(item.linesAdded(), item.linesRemoved(), nowMillis);
            }
        }
        states.keySet().retainAll(livePaths);
    }

    @NotNull DiffCounts displayCounts(@NotNull ReviewItem item, long nowMillis) {
        DiffCountState state = states.get(item.path());
        return state != null ? state.displayCounts(nowMillis)
            : new DiffCounts(item.linesAdded(), item.linesRemoved());
    }

    boolean hasActiveAnimations(long nowMillis) {
        for (DiffCountState state : states.values()) {
            if (state.isAnimating(nowMillis)) {
                return true;
            }
        }
        return false;
    }

    void clear() {
        states.clear();
    }

    private static final class DiffCountState {
        private int startAdded;
        private int startRemoved;
        private int targetAdded;
        private int targetRemoved;
        private long startMillis;

        private DiffCountState(int startAdded, int startRemoved, int targetAdded, int targetRemoved, long startMillis) {
            this.startAdded = startAdded;
            this.startRemoved = startRemoved;
            this.targetAdded = targetAdded;
            this.targetRemoved = targetRemoved;
            this.startMillis = startMillis;
        }

        static @NotNull DiffCountState stationary(int added, int removed) {
            return new DiffCountState(added, removed, added, removed, 0L);
        }

        static @NotNull DiffCountState animateFromZero(int added, int removed, long startMillis) {
            return new DiffCountState(0, 0, added, removed, startMillis);
        }

        void retarget(int added, int removed, long nowMillis) {
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

        @NotNull DiffCounts displayCounts(long nowMillis) {
            if (!isAnimating(nowMillis)) {
                return new DiffCounts(targetAdded, targetRemoved);
            }
            double progress = Math.min(1.0d, (double) (nowMillis - startMillis) / ANIMATION_DURATION_MS);
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
    }

    record DiffCounts(int added, int removed) {
    }
}
