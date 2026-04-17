package com.github.catatafishen.agentbridge.psi.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A unified view of a single file's review state within an {@link AgentEditSession}.
 * Derived from the session's snapshots, newFiles, and deletedFiles maps.
 *
 * @param path           absolute VFS path
 * @param relativePath   project-relative path for display
 * @param status         the file's review status
 * @param beforeContent  the original content (null for ADDED files)
 */
public record ReviewItem(
    @NotNull String path,
    @NotNull String relativePath,
    @NotNull Status status,
    @Nullable String beforeContent
) {

    public enum Status {
        /** File was created by the agent during this session. */
        ADDED,
        /** File existed before and was modified by the agent. */
        MODIFIED,
        /** File was deleted by the agent during this session. */
        DELETED
    }
}
