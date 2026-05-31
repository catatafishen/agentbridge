package com.github.catatafishen.agentbridge.session.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Carries MCP tool completion stats for enriching an existing {@code tool_call_events} row.
 * Each field maps directly to a SQL column in the UPDATE performed by
 * {@link ConversationWriter#enrichToolCallStats}.
 *
 * @param dbEventId       stable event ID (primary key in tool_call_events)
 * @param inputSizeBytes  request payload size in bytes
 * @param outputSizeBytes response payload size in bytes
 * @param durationMs      wall-clock execution time
 * @param success         whether the tool completed successfully
 * @param errorMessage    error text on failure; null on success
 * @param category        tool category from ToolDefinition (e.g. "file", "git")
 * @param displayName     human-readable tool name for UI display
 * @param pluginVersion   plugin version string at call time (e.g. "1.2.3"); null if unavailable
 */
public record ToolCallStatsEnrichment(
    @NotNull String dbEventId,
    long inputSizeBytes,
    long outputSizeBytes,
    long durationMs,
    boolean success,
    @Nullable String errorMessage,
    @Nullable String category,
    @Nullable String displayName,
    @Nullable String pluginVersion
) {
}
