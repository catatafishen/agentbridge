package com.github.catatafishen.agentbridge.psi.review;

/**
 * Classifies a range of lines in the current document relative to the before-session snapshot.
 */
public enum ChangeType {
    /** New lines inserted that have no corresponding lines in the snapshot. */
    ADDED,
    /** Existing lines replaced with different content. */
    MODIFIED,
    /** Lines from the snapshot that no longer exist in the current document. */
    DELETED
}
