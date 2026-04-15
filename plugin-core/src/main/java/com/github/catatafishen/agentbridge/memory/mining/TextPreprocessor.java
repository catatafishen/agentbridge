package com.github.catatafishen.agentbridge.memory.mining;

import org.jetbrains.annotations.NotNull;

/**
 * Shared text preprocessing for the mining pipeline. Provides markdown
 * stripping and combined-text construction used by both the embedding
 * path (in {@link ExchangeChunker.Exchange}) and the triple extraction
 * path (in {@link com.github.catatafishen.agentbridge.memory.kg.TripleExtractor}).
 */
public final class TextPreprocessor {

    private TextPreprocessor() {
    }

    /**
     * Strip markdown formatting and tool-call fragments from text,
     * preserving the underlying conversational words.
     *
     * <p>Code blocks are removed entirely (code is not conversational prose).
     * Bold/italic markers are unwrapped, keeping the emphasized text.
     * Tool evidence brackets {@code [tool:...]} and {@code [...result:...]}
     * are removed to prevent false pattern matches.
     */
    public static @NotNull String stripMarkdown(@NotNull String text) {
        // Remove fenced code blocks entirely (content is code, not prose)
        String result = text.replaceAll("```[\\s\\S]*?```", " ");
        // Remove inline code spans
        result = result.replaceAll("`[^`]+`", " ");
        // Remove tool evidence brackets: [tool:...], [...result:...]
        result = result.replaceAll("\\[tool:[^]]*]", " ");
        result = result.replaceAll("\\[[^]]{0,40} result:[^]]*]", " ");
        // Unwrap bold/italic — keep the text, remove the markers
        result = result.replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1");
        result = result.replaceAll("_{1,3}([^_]+)_{1,3}", "$1");
        // Remove header markers
        result = result.replaceAll("(?m)^#{1,6}\\s+", "");
        // Remove bullet/list markers
        result = result.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        result = result.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        // Unwrap markdown links: [text](url) → text
        result = result.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
        // Remove bare URLs
        result = result.replaceAll("https?://\\S+", "");
        // Remove blockquote markers
        result = result.replaceAll("(?m)^>+\\s*", "");
        // Normalize runs of horizontal whitespace (preserve newlines for splitting)
        result = result.replaceAll("[ \\t]+", " ");
        return result.strip();
    }

    /**
     * Build the combined text for embedding and classification.
     * Uses response-first ordering because the response typically contains
     * more information-dense content (decisions, explanations) than the
     * prompt (short commands/questions). Embedding models weight earlier
     * tokens more heavily due to truncation, so response-first maximizes
     * information retained within the 256-token limit.
     *
     * <p>Markdown is stripped before combining so that formatting artifacts
     * don't consume embedding token budget.
     */
    public static @NotNull String forEmbedding(@NotNull String prompt, @NotNull String response) {
        String cleanResponse = stripMarkdown(response);
        String cleanPrompt = stripMarkdown(prompt);
        return cleanResponse + "\n\n" + cleanPrompt;
    }
}
