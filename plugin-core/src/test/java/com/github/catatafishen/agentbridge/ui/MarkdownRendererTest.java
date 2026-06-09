package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MarkdownRenderer} — a pure markdown-to-HTML converter.
 * Uses no-op lambdas for file resolution and git detection.
 */
@DisplayName("MarkdownRenderer")
class MarkdownRendererTest {

    private String render(String markdown) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(
            markdown,
            s -> null,      // resolveFileReference
            s -> null,      // resolveFilePath
            s -> false      // isGitCommit
        );
    }

    @Nested
    @DisplayName("markdownToHtml")
    class MarkdownToHtml {

        @Test
        void emptyStringReturnsEmpty() {
            assertEquals("", render(""));
        }

        @Test
        void plainTextWrappedInParagraph() {
            String result = render("Hello world");
            assertTrue(result.contains("Hello world"));
        }

        @Test
        void headingsRendered() {
            assertTrue(render("# Title").contains("<h2>"));
            assertTrue(render("## Section").contains("<h3>"));
            assertTrue(render("### Sub").contains("<h4>"));
        }

        @Test
        void boldTextRendered() {
            String result = render("This is **bold** text");
            assertTrue(result.contains("<b>bold</b>"));
        }

        @Test
        void italicTextRendered() {
            String result = render("This is *italic* text");
            assertTrue(result.contains("<em>italic</em>"));
        }

        @Test
        void inlineCodeRendered() {
            String result = render("Use `println()` here");
            assertTrue(result.contains("<code"));
            assertTrue(result.contains("println()"));
        }

        @Test
        void codeBlockRendered() {
            String result = render("```java\nint x = 1;\n```");
            assertTrue(result.contains("<pre"));
            assertTrue(result.contains("<code"));
            assertTrue(result.contains("int x = 1;"));
        }

        @Test
        void unorderedListRendered() {
            String result = render("- item 1\n- item 2");
            assertTrue(result.contains("<ul>"));
            assertTrue(result.contains("<li>"));
            assertTrue(result.contains("item 1"));
            assertTrue(result.contains("item 2"));
        }

        @Test
        void horizontalRuleRendered() {
            String result = render("above\n---\nbelow");
            assertTrue(result.contains("<hr"));
        }

        @Test
        void blockquoteRendered() {
            String result = render("> quoted text");
            assertTrue(result.contains("<blockquote"));
            assertTrue(result.contains("quoted text"));
        }

        @Test
        void markdownLinkRendered() {
            String result = render("[click here](https://example.com)");
            assertTrue(result.contains("href"));
            assertTrue(result.contains("https://example.com"));
            assertTrue(result.contains("click here"));
        }

        @Test
        void bareUrlRendered() {
            String result = render("Visit https://example.com for more");
            assertTrue(result.contains("href"));
            assertTrue(result.contains("https://example.com"));
        }

        @Test
        void tableRendered() {
            String md = "| Col1 | Col2 |\n|------|------|\n| a | b |";
            String result = render(md);
            assertTrue(result.contains("<table"));
            assertTrue(result.contains("<tr"));
        }

        @Test
        void htmlEntitiesInCodeBlockPreserved() {
            String result = render("```\n<div>&amp;</div>\n```");
            assertTrue(result.contains("&lt;div&gt;"));
        }

        @Test
        void multipleBlocksCombined() {
            String md = "# Title\n\nParagraph text.\n\n```\ncode\n```\n\n- list item";
            String result = render(md);
            assertTrue(result.contains("<h2>"));
            assertTrue(result.contains("Paragraph text."));
            assertTrue(result.contains("code"));
            assertTrue(result.contains("<li>"));
        }
    }
}
