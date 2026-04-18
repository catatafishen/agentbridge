package com.github.catatafishen.agentbridge.psi.review;

/**
 * The user-controlled review state of a tracked file.
 *
 * <p>This is orthogonal to {@link ReviewItem.Status}: a row's {@code Status} describes the
 * <i>kind</i> of change the agent made (added / modified / deleted), while
 * {@code ApprovalState} describes whether the user has signed off on it. A file always has
 * exactly one {@code Status} <i>and</i> exactly one {@code ApprovalState}.
 *
 * <p>New rows always start as {@link #PENDING}; toggling Auto-Approve on flips every
 * pending row to {@link #APPROVED} immediately. The git-gate only blocks on pending rows.
 */
public enum ApprovalState {
    /** The user has not yet acknowledged the change. Blocks git gates. */
    PENDING,
    /**
     * The user has approved the change. The row stays visible in the panel until it is
     * cleaned up (DEL key, "Clean Approved" toolbar action, post-commit prune, or
     * worktree-changing git op). Approved rows do not block git gates.
     */
    APPROVED
}
