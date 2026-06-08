package com.github.catatafishen.agentbridge.psi.graph;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plain data record for a named PSI element extracted into the code graph.
 * Immutable value object — no behaviour.
 */
public final class NodeData {

    @NotNull public final String id;
    @NotNull public final String label;
    @NotNull public final String kind;       // class|interface|method|function|field|file|symbol
    @Nullable public final String fqn;
    @NotNull public final String sourceFile; // project-relative path
    public final int sourceLine;             // 1-based; 0 = unknown
    @NotNull public final String language;   // Language.getID()

    public NodeData(
        @NotNull String id,
        @NotNull String label,
        @NotNull String kind,
        @Nullable String fqn,
        @NotNull String sourceFile,
        int sourceLine,
        @NotNull String language
    ) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.fqn = fqn;
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
        this.language = language;
    }

    @Override
    public String toString() {
        return "NodeData{id='" + id + "', kind='" + kind + "', file='" + sourceFile + "'}";
    }
}
