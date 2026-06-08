package com.github.catatafishen.agentbridge.psi.graph;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plain data record for a typed directed edge between two graph nodes.
 * Immutable value object — no behaviour.
 */
public final class EdgeData {

    @NotNull public final String sourceId;
    @NotNull public final String targetId;
    @NotNull public final String relation;   // calls|extends|implements|imports|uses|overrides
    @Nullable public final String sourceFile;
    public final int sourceLine;             // 1-based; 0 = unknown

    public EdgeData(
        @NotNull String sourceId,
        @NotNull String targetId,
        @NotNull String relation,
        @Nullable String sourceFile,
        int sourceLine
    ) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relation = relation;
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
    }

    @Override
    public String toString() {
        return "EdgeData{" + sourceId + " -[" + relation + "]-> " + targetId + "}";
    }
}
