package com.github.catatafishen.agentbridge.model;

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

    /**
     * An embedded resource: file content (or similar) inlined directly into the prompt.
     * Per the ACP/MCP spec, {@link EmbeddedResourceContents} MUST carry either {@code text}
     * or {@code blob} — a pointer with neither populated is invalid and will be rejected by
     * agents that validate incoming content blocks. Use {@link ResourceLink} instead when you
     * only have a URI/mime type and no inline content to embed.
     */
    record Resource(EmbeddedResourceContents resource) implements ContentBlock {
    }

    /**
     * The contents nested inside a {@link Resource} block: either {@code text} (for text
     * resources) or {@code blob} (base64, for binary resources) must be non-null.
     */
    record EmbeddedResourceContents(
        String uri,
        @Nullable String name,
        @Nullable String mimeType,
        @Nullable String text,
        @Nullable String blob
    ) {
    }

    /**
     * A flat pointer to a resource that is NOT embedded inline — just a URI, display name,
     * and optional mime type, so agents that support file links can pull the bytes
     * themselves. This is the correct ACP content-block type for "file reference with no
     * inline content"; do NOT use {@link Resource} with null {@code text}/{@code blob} for
     * this purpose, since embedded resources require actual inline content.
     */
    record ResourceLink(String uri, String name, @Nullable String mimeType) implements ContentBlock {
    }
}
