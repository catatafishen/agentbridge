package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.ApprovalState;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewDiffCountAnimatorTest {

    @Test
    void retargetsDiffCountsOverTime() {
        ReviewDiffCountAnimator animator = new ReviewDiffCountAnimator();
        ReviewItem initial = new ReviewItem(
            "/tmp/Foo.java",
            "Foo.java",
            ReviewItem.Status.MODIFIED,
            null,
            ApprovalState.PENDING,
            1L,
            0,
            0
        );
        ReviewItem updated = new ReviewItem(
            "/tmp/Foo.java",
            "Foo.java",
            ReviewItem.Status.MODIFIED,
            null,
            ApprovalState.PENDING,
            2L,
            10,
            4
        );

        animator.sync(List.of(initial), 0L);
        animator.sync(List.of(updated), 100L);

        long midpoint = 100L + (ReviewDiffCountAnimator.ANIMATION_DURATION_MS / 2);
        ReviewDiffCountAnimator.DiffCounts mid = animator.displayCounts(updated, midpoint);
        assertTrue(mid.added() > 0 && mid.added() < 10);
        assertTrue(mid.removed() > 0 && mid.removed() < 4);
        assertTrue(animator.hasActiveAnimations(midpoint));

        long finished = 100L + ReviewDiffCountAnimator.ANIMATION_DURATION_MS + 1L;
        ReviewDiffCountAnimator.DiffCounts end = animator.displayCounts(updated, finished);
        assertEquals(10, end.added());
        assertEquals(4, end.removed());
        assertFalse(animator.hasActiveAnimations(finished));
    }

    @Test
    void newItemAnimatesFromZero() {
        ReviewDiffCountAnimator animator = new ReviewDiffCountAnimator();
        ReviewItem item = new ReviewItem(
            "/tmp/Bar.java",
            "Bar.java",
            ReviewItem.Status.ADDED,
            null,
            ApprovalState.PENDING,
            1L,
            20,
            0
        );

        animator.sync(List.of(item), 0L);

        ReviewDiffCountAnimator.DiffCounts atStart = animator.displayCounts(item, 0L);
        assertEquals(0, atStart.added(), "new item should start at 0");
        assertTrue(animator.hasActiveAnimations(0L));

        long midpoint = ReviewDiffCountAnimator.ANIMATION_DURATION_MS / 2;
        ReviewDiffCountAnimator.DiffCounts mid = animator.displayCounts(item, midpoint);
        assertTrue(mid.added() > 0 && mid.added() < 20, "midpoint should be between 0 and target");

        long finished = ReviewDiffCountAnimator.ANIMATION_DURATION_MS + 1L;
        ReviewDiffCountAnimator.DiffCounts end = animator.displayCounts(item, finished);
        assertEquals(20, end.added());
        assertFalse(animator.hasActiveAnimations(finished));
    }
}
