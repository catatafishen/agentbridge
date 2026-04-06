package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * Agent → Client: prompt turn completed.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/prompt-turn">ACP Prompt Turn</a>
 */
public record PromptResponse(
        String stopReason,
        @Nullable TurnUsage usage
) {

    /**
     * Token usage and cost for a completed prompt turn.
     */
    public record TurnUsage(
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Double costUsd
    ) {}
}
