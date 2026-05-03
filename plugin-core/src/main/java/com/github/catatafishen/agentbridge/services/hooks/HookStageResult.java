package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a single hook execution within the MCP tool call pipeline.
 * Captured during execution and stored on {@link com.github.catatafishen.agentbridge.services.LiveToolCallEntry}
 * for display in the tool call pipeline visualization.
 *
 * @param trigger    the pipeline stage (e.g., "permission", "pre", "success", "failure")
 * @param scriptName the script file name (e.g., "enforce-commit-author.sh"), or "static" for text-only entries
 * @param outcome    human-readable outcome (e.g., "allowed", "denied", "modified", "unchanged", "blocked", "appended")
 * @param durationMs execution time in milliseconds
 * @param detail     optional detail text (e.g., denial reason, modification summary, error message)
 */
public record HookStageResult(
    @NotNull String trigger,
    @NotNull String scriptName,
    @NotNull String outcome,
    long durationMs,
    @Nullable String detail
) {
    private static final int MAX_DETAIL_CHARS = 500;

    public HookStageResult {
        if (detail != null && detail.length() > MAX_DETAIL_CHARS) {
            detail = detail.substring(0, MAX_DETAIL_CHARS) + "…";
        }
    }
}
