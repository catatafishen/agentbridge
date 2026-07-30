package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maps agent display names (e.g. "GitHub Copilot") to canonical profile IDs
 * (e.g. "copilot") that match {@link AgentProfileManager} identifiers.
 *
 * <p>Used by both chart statistics and tool-call backfill to normalize
 * session-level agent names into consistent client IDs.</p>
 */
public final class AgentIdMapper {

    private AgentIdMapper() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Converts an agent display name to a canonical profile ID.
     *
     * @param agentDisplayName the display name from session metadata (e.g. "GitHub Copilot", "Claude Code")
     * @return the canonical profile ID (e.g. "copilot", "claude-cli")
     */
    @NotNull
    public static String toAgentId(@Nullable String agentDisplayName) {
        if (agentDisplayName == null || agentDisplayName.isEmpty()) return "unknown";
        String lower = agentDisplayName.toLowerCase();
        if (lower.contains("copilot")) return "copilot";
        if (lower.contains("claude")) return "claude-cli";
        if (lower.contains("opencode")) return "opencode";
        if (lower.contains("junie")) return "junie";
        if (lower.contains("kiro")) return "kiro";
        if (lower.contains("codex")) return "codex";
        if (lower.contains("vibe")) return "vibe";
        return lower.replaceAll("[^a-z0-9]", "-");
    }
}
