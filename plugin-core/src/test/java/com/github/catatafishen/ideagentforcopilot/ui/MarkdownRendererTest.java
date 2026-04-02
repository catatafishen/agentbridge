package com.github.catatafishen.ideagentforcopilot.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    // ── Existing tests ────────────────────────────────────────────────────────

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

    // ── New tests for issue #33: Markdown rendering correctness ──────────────

    @Test
    void boldTextRendersAsStrongElement() {
        String html = render("This is **bold** text.");
        assertTrue(html.contains("<b>bold</b>"), "Expected **bold** to render as <b>bold</b>, got: " + html);
    }

    @Test
    void inlineCodeRendersAsCodeElement() {
        String html = render("Use `System.out.println()` to print.");
        assertTrue(html.contains("<code>System.out.println()</code>"),
            "Expected `...` to render as <code>...</code>, got: " + html);
    }

    @Test
    void fencedCodeBlockRendersAsPreCode() {
        String md = "```java\nSystem.out.println(\"Hello\");\n```";
        String html = render(md);
        assertTrue(html.contains("<pre><code"), "Expected fenced code to open with <pre><code>, got: " + html);
        assertTrue(html.contains("</code></pre>"), "Expected fenced code to close with </code></pre>, got: " + html);
        assertTrue(html.contains("data-lang=\"java\""), "Expected data-lang attribute, got: " + html);
    }

    @Test
    void headingsRenderWithCorrectLevel() {
        // Kotlin renderer maps # → h2, ## → h3, ### → h4, #### → h5
        assertTrue(render("# Top").contains("<h2>"), "# should map to h2");
        assertTrue(render("## Second").contains("<h3>"), "## should map to h3");
        assertTrue(render("### Third").contains("<h4>"), "### should map to h4");
        assertTrue(render("#### Fourth").contains("<h5>"), "#### should map to h5");
    }

    @Test
    void unorderedListRendersAsUlLi() {
        String html = render("- item one\n- item two");
        assertTrue(html.contains("<ul>"), "Expected <ul> in list output, got: " + html);
        assertTrue(html.contains("<li>"), "Expected <li> in list output, got: " + html);
        assertTrue(html.contains("</ul>"), "Expected </ul> in list output, got: " + html);
        assertTrue(html.contains("item one"), html);
        assertTrue(html.contains("item two"), html);
    }

    @Test
    void horizontalRuleRendersAsHr() {
        String html = render("---");
        assertTrue(html.contains("<hr>"), "Expected <hr> for ---, got: " + html);
    }

    @Test
    void blockquoteRendersAsBlockquote() {
        String html = render("> This is quoted.");
        assertTrue(html.contains("<blockquote>"), "Expected <blockquote> for > prefix, got: " + html);
        assertTrue(html.contains("This is quoted"), html);
    }

    @Test
    void plainParagraphWrappedInPTag() {
        String html = render("This is a plain sentence.");
        assertTrue(html.contains("<p>"), "Expected plain text to be wrapped in <p>, got: " + html);
        assertTrue(html.contains("This is a plain sentence"), html);
    }

    @Test
    void htmlSpecialCharsEscapedInsideParagraph() {
        String html = render("Result: x < y && y > 0");
        assertTrue(html.contains("&lt;"), "Expected < to be escaped, got: " + html);
        assertTrue(html.contains("&gt;"), "Expected > to be escaped, got: " + html);
        assertTrue(html.contains("&amp;"), "Expected & to be escaped, got: " + html);
    }

    @Test
    void bareUrlRendersAsAnchor() {
        String html = render("See https://example.com for details.");
        assertTrue(html.contains("<a href='https://example.com'>"),
            "Expected bare URL to render as anchor, got: " + html);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static String render(String markdown) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(
            markdown,
            ref -> null,
            ref -> null,
            sha -> false
        );
    }
}
