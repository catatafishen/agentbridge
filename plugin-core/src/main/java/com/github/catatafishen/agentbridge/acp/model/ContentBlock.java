package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * Content blocks used in prompts and agent messages.
 * Discriminated union — serialized with a "type" field.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/prompt-turn">ACP Content</a>
 */
public sealed interface ContentBlock {

    record Text(String text) implements ContentBlock {
    }

    record Thinking(String thinking) implements ContentBlock {
    }

    record Image(String data, String mimeType) implements ContentBlock {
    }

    record Audio(String data, String mimeType) implements ContentBlock {
    }

    record Resource(ResourceLink resource) implements ContentBlock {
    }

    /**
     * A reference to an embedded resource (file content, selection, etc.).
     */
    record ResourceLink(
        String uri,
        @Nullable String name,
        @Nullable String mimeType,
        @Nullable String text,
        @Nullable String blob
    ) {
    }
}
