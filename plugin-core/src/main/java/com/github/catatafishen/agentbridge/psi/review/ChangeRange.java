package com.github.catatafishen.agentbridge.psi.review;

/**
 * A contiguous range of changed lines in the current ("after") document.
 *
 * @param startLine       0-based start line in the current document (inclusive)
 * @param endLine         0-based end line in the current document (exclusive).
 *                        For {@link ChangeType#DELETED}, startLine == endLine (point range).
 * @param type            whether lines were added, modified, or deleted
 * @param deletedFromLine 0-based line in the "before" document where deleted lines started
 * @param deletedCount    number of lines removed from the "before" document for this range
 */
public record ChangeRange(
    int startLine,
    int endLine,
    ChangeType type,
    int deletedFromLine,
    int deletedCount
) {

    /** Number of lines in the current document occupied by this range. */
    public int insertedCount() {
        return endLine - startLine;
    }
}
