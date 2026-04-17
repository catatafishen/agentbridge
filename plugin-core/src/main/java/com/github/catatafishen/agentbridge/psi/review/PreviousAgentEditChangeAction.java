package com.github.catatafishen.agentbridge.psi.review;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * Phase 5 — moves the caret to the previous agent-edit change across the review
 * session, wrapping from the first file back to the last.
 */
public final class PreviousAgentEditChangeAction extends NextAgentEditChangeAction {

    @Override
    protected Optional<ChangeNavigator.Location> pick(
        @NotNull NavigableMap<String, List<ChangeRange>> byPath,
        String currentPath, int currentLine) {
        return ChangeNavigator.findPrevious(byPath, currentPath, currentLine);
    }
}
