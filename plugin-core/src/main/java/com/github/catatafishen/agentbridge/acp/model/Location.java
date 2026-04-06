package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * A source code location referenced by a tool call.
 */
public record Location(String uri, @Nullable Range range) {

    public record Range(Position start, Position end) {}

    public record Position(int line, int character) {}
}
