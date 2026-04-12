package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Immutable record of a single MCP tool call, capturing identity, sizing,
 * timing, and outcome. Stored in SQLite by {@link ToolCallStatisticsService}.
 *
 * @param toolName       MCP tool ID (e.g. "read_file", "search_text")
 * @param category       tool category from ToolDefinition (e.g. "FILE", "GIT")
 * @param inputSizeBytes byte length of the JSON arguments
 * @param outputSizeBytes byte length of the response text
 * @param durationMs     wall-clock execution time in milliseconds
 * @param success        true if the tool completed without error
 * @param clientId       active agent profile ID (e.g. "copilot", "opencode")
 * @param timestamp      instant when the call started
 */
public record ToolCallRecord(
    @NotNull String toolName,
    @Nullable String category,
    long inputSizeBytes,
    long outputSizeBytes,
    long durationMs,
    boolean success,
    @NotNull String clientId,
    @NotNull Instant timestamp
) {
}
