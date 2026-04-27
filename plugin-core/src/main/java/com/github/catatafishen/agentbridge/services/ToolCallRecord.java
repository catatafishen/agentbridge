package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Immutable record of a single MCP tool call, capturing identity, sizing,
 * timing, and outcome. Stored in SQLite by {@link ToolCallStatisticsService}.
 *
 * @param toolName        canonical MCP tool id (e.g. "read_file", "search_text") —
 *                        the bare name from the JSON-RPC {@code tools/call} request,
 *                        used for aggregation. Never the agent-supplied chip title.
 * @param category        tool category from ToolDefinition (e.g. "FILE", "GIT")
 * @param inputSizeBytes  byte length of the JSON arguments
 * @param outputSizeBytes byte length of the response text
 * @param durationMs      wall-clock execution time in milliseconds
 * @param success         true if the tool completed without error
 * @param errorMessage    error text when success is false (null on success)
 * @param clientId        active agent profile ID (e.g. "copilot", "opencode")
 * @param timestamp       instant when the call started
 * @param displayName     optional original chip title from the agent ("Tail full log",
 *                        "Run summary"), kept for debugging only — not used for aggregation.
 *                        Null when the live MCP path recorded the call (no display title
 *                        is available there) or when the title equals the canonical id.
 */
public record ToolCallRecord(
    @NotNull String toolName,
    @Nullable String category,
    long inputSizeBytes,
    long outputSizeBytes,
    long durationMs,
    boolean success,
    @Nullable String errorMessage,
    @NotNull String clientId,
    @NotNull Instant timestamp,
    @Nullable String displayName
) {
    /**
     * Convenience constructor for callers (and the many existing tests) that don't
     * supply a display name. The {@link #displayName} field defaults to {@code null}.
     */
    public ToolCallRecord(@NotNull String toolName,
                          @Nullable String category,
                          long inputSizeBytes,
                          long outputSizeBytes,
                          long durationMs,
                          boolean success,
                          @Nullable String errorMessage,
                          @NotNull String clientId,
                          @NotNull Instant timestamp) {
        this(toolName, category, inputSizeBytes, outputSizeBytes, durationMs, success,
            errorMessage, clientId, timestamp, null);
    }
}
