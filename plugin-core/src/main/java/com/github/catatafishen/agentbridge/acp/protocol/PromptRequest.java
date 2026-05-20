package com.github.catatafishen.agentbridge.acp.protocol;

import com.github.catatafishen.agentbridge.model.ContentBlock;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Client → Agent: send a user prompt within a session.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/prompt-turn">ACP Prompt Turn</a>
 */
public record PromptRequest(
        String sessionId,
        List<ContentBlock> prompt,
        @Nullable String modelId,
        @Nullable String modeSlug
) {}
