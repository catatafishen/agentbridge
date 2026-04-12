package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link SonarRuleDescriptions}.
 * Uses reflection since the methods are private.
 */
class SonarRuleDescriptionsTest {

    // ── htmlToText ─────────────────────────────────────────

    @Test
    void htmlToText_nullReturnsEmpty() throws Exception {
        assertEquals("", invokeHtmlToText(null));
    }

    @Test
    void htmlToText_emptyReturnsEmpty() throws Exception {
        assertEquals("", invokeHtmlToText(""));
    }

    @Test
    void htmlToText_stripsSimpleTags() throws Exception {
        assertEquals("Hello world", invokeHtmlToText("<b>Hello</b> <i>world</i>"));
    }

    @Test
    void htmlToText_convertsBlockTagsToNewlines() throws Exception {
        String result = invokeHtmlToText("<p>First</p><p>Second</p>");
        assertTrue(result.contains("First"), "Should contain First");
        assertTrue(result.contains("Second"), "Should contain Second");
        assertTrue(result.indexOf("First") < result.indexOf("Second"), "First before Second");
    }

    @Test
    void htmlToText_decodesHtmlEntities() throws Exception {
        assertEquals("a & b < c > d \"e\" 'f'",
            invokeHtmlToText("a &amp; b &lt; c &gt; d &quot;e&quot; &#39;f&#39;"));
    }

    @Test
    void htmlToText_replacesNbsp() throws Exception {
        assertEquals("no break", invokeHtmlToText("no&nbsp;break"));
    }

    @Test
    void htmlToText_collapsesWhitespace() throws Exception {
        assertEquals("hello world", invokeHtmlToText("hello   \t  world"));
    }

    @Test
    void htmlToText_collapsesMultipleBlankLines() throws Exception {
        String result = invokeHtmlToText("<p>A</p><p></p><p></p><p></p><p>B</p>");
        // Should not have more than 2 consecutive newlines
        assertFalse(result.contains("\n\n\n"), "Should collapse 3+ newlines to 2");
    }

    @Test
    void htmlToText_truncatesLongContent() throws Exception {
        String longHtml = "x".repeat(10_000);
        String result = invokeHtmlToText(longHtml);
        assertTrue(result.endsWith("..."), "Should truncate with ...");
        assertTrue(result.length() <= 4003 + 3, "Should not exceed MAX_DESCRIPTION_CHARS + ...");
    }

    @Test
    void htmlToText_handlesListItems() throws Exception {
        String result = invokeHtmlToText("<ul><li>Alpha</li><li>Beta</li></ul>");
        assertTrue(result.contains("Alpha"));
        assertTrue(result.contains("Beta"));
    }

    @Test
    void htmlToText_handlesHeadings() throws Exception {
        String result = invokeHtmlToText("<h2>Title</h2>Content");
        assertTrue(result.contains("Title"));
        assertTrue(result.contains("Content"));
    }

    // ── formatEntry ────────────────────────────────────────

    @Test
    void formatEntry_headerOnlyWhenTextEmpty() throws Exception {
        assertEquals("java:S3776 · Cognitive Complexity",
            invokeFormatEntry("java:S3776", "Cognitive Complexity", ""));
    }

    @Test
    void formatEntry_headerOnlyRuleKeyWhenNameNull() throws Exception {
        assertEquals("java:S3776",
            invokeFormatEntry("java:S3776", null, ""));
    }

    @Test
    void formatEntry_headerOnlyRuleKeyWhenNameEmpty() throws Exception {
        assertEquals("java:S3776",
            invokeFormatEntry("java:S3776", "", ""));
    }

    @Test
    void formatEntry_includesIndentedText() throws Exception {
        String result = invokeFormatEntry("java:S1192", "String literals", "Avoid duplicates");
        assertEquals("java:S1192 · String literals\n  Avoid duplicates", result);
    }

    @Test
    void formatEntry_indentsMultilineText() throws Exception {
        String result = invokeFormatEntry("java:S1192", "Rule", "Line1\nLine2\nLine3");
        assertEquals("java:S1192 · Rule\n  Line1\n  Line2\n  Line3", result);
    }

    // ── Reflection helpers ─────────────────────────────────

    private static String invokeHtmlToText(String html) throws Exception {
        Method m = SonarRuleDescriptions.class.getDeclaredMethod("htmlToText", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, html);
    }

    private static String invokeFormatEntry(String ruleKey, String name, String text) throws Exception {
        Method m = SonarRuleDescriptions.class.getDeclaredMethod("formatEntry", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, ruleKey, name, text);
    }
}
