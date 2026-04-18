package com.github.catatafishen.agentbridge.psi.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A unified view of a single file's review state within an {@link AgentEditSession}.
 * Derived from the session's tracked maps.
 *
 * @param path             absolute VFS path
 * @param relativePath     project-relative path for display
 * @param status           the kind of change the agent made
 * @param beforeContent    the original content (null for ADDED files)
 * @param approvalState    the user's review decision
 * @param lastEditedMillis epoch millis of the most recent agent edit
 * @param linesAdded       inserted line count (vs. the snapshot baseline)
 * @param linesRemoved     deleted line count (vs. the snapshot baseline)
 */
public record ReviewItem(
    @NotNull String path,
    @NotNull String relativePath,
    @NotNull Status status,
    @Nullable String beforeContent,
    @NotNull ApprovalState approvalState,
    long lastEditedMillis,
    int linesAdded,
    int linesRemoved
) {

    /**
     * Convenience: is this row currently approved?
     */
    public boolean approved() {
        return approvalState == ApprovalState.APPROVED;
    }

    public enum Status {
        /**
         * File was created by the agent during this session.
         */
        ADDED,
        /**
         * File existed before and was modified by the agent.
         */
        MODIFIED,
        /**
         * File was deleted by the agent during this session.
         */
        DELETED
    }
}
