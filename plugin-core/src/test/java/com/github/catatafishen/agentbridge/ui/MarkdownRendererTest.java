package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @Test
    void codexStyleAbsoluteFileMarkdownLinkResolvesToOpenFileAnchor() {
        String path = "/home/catatafishen/IdeaProjects/intellij-copilot-plugin/plugin-core/src/main/resources/icons/expui/codex.svg";

        String html = MarkdownRenderer.INSTANCE.markdownToHtml(
            "[" + path + "](" + path + ")",
            ref -> null,
            ref -> path.equals(ref) ? path : null,
            sha -> false
        );

        assertTrue(
            html.contains("<a href='openfile://" + path + "'>" + path + "</a>"),
            "Expected markdown file link to become an openfile anchor, got: " + html
        );
    }

    @Test
    void httpMarkdownLinkStillRendersAsWebLink() {
        String html = MarkdownRenderer.INSTANCE.markdownToHtml(
            "[OpenAI](https://openai.com)",
            ref -> null,
            ref -> null,
            sha -> false
        );

        assertTrue(html.contains("<a href='https://openai.com'>OpenAI</a>"), html);
    }
}
