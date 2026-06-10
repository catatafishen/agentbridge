package com.github.catatafishen.agentbridge.psi.graph;

/**
 * Shared SQL column labels and node-id prefixes used by the graph query classes.
 * Centralized so that any rename in the schema or id format is a one-place change.
 */
final class GraphColumns {

    static final String FILE_PREFIX = "file:";
    static final String TURN_PREFIX = "turn:";
    static final String COMMIT_PREFIX = "commit:";

    static final String COL_DEPENDENT_COUNT = "dependent_count";
    static final String COL_TIMESTAMP = "timestamp";
    static final String COL_FILE_PATH = "file_path";

    private GraphColumns() {
    }
}
