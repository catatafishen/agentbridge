package com.github.catatafishen.agentbridge.permissions;

/**
 * Result of an abuse detection check.
 *
 * @param category short label for the detected abuse (e.g. "git", "cat", "grep")
 * @param reason   human-readable denial message with MCP tool suggestion
 */
public record AbuseResult(String category, String reason) {}
