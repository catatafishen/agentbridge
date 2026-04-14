package com.github.catatafishen.agentbridge.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.catatafishen.agentbridge.ui.MarkdownRenderer;

/**
 * Jazzer fuzz target for {@link MarkdownRenderer}.
 *
 * <p>Validates that rendering arbitrary Markdown to HTML never causes uncaught exceptions,
 * infinite loops, or other unexpected failures. This is a high-value target because the
 * chat panel renders untrusted output from AI agent processes.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.catatafishen.agentbridge.fuzz.MarkdownRendererFuzz}
 */
public class MarkdownRendererFuzz {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();
        // All lambda parameters have no-op defaults matching the production defaults.
        // Any uncaught exception is a Jazzer finding.
        MarkdownRenderer.INSTANCE.markdownToHtml(text, s -> null, s -> null, s -> false);
    }
}
