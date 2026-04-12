package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptBubbleBuilderTest {

    /** Unicode Object Replacement Character — same placeholder used by the builder. */
    private static final String ORC = "\uFFFC";

    // ── helpers ─────────────────────────────────────────────────────────

    private static ContextItemData fileItem(String path, String name) {
        return new ContextItemData(path, name, 0, 0, null, false);
    }

    private static ContextItemData selectionItem(String path, String name,
                                                  int startLine, int endLine) {
        return new ContextItemData(path, name, startLine, endLine, null, true);
    }

    // ── escapeHtml ──────────────────────────────────────────────────────

    @Test
    void escapeHtml_plainText_unchanged() {
        assertEquals("hello world", PromptBubbleBuilder.INSTANCE.escapeHtml("hello world"));
    }

    @Test
    void escapeHtml_emptyString_returnsEmpty() {
        assertEquals("", PromptBubbleBuilder.INSTANCE.escapeHtml(""));
    }

    @Test
    void escapeHtml_ampersand_escaped() {
        assertEquals("a &amp; b", PromptBubbleBuilder.INSTANCE.escapeHtml("a & b"));
    }

    @Test
    void escapeHtml_lessThan_escaped() {
        assertEquals("a &lt; b", PromptBubbleBuilder.INSTANCE.escapeHtml("a < b"));
    }

    @Test
    void escapeHtml_greaterThan_escaped() {
        assertEquals("a &gt; b", PromptBubbleBuilder.INSTANCE.escapeHtml("a > b"));
    }

    @Test
    void escapeHtml_singleQuote_escaped() {
        assertEquals("it&#39;s", PromptBubbleBuilder.INSTANCE.escapeHtml("it's"));
    }

    @Test
    void escapeHtml_allSpecialCharsTogether() {
        assertEquals("&amp;&lt;&gt;&#39;",
                PromptBubbleBuilder.INSTANCE.escapeHtml("&<>'"));
    }

    @Test
    void escapeHtml_doubleQuote_notEscaped() {
        // escapeHtml only escapes &, <, >, and ' — not double quotes
        assertEquals("\"quoted\"", PromptBubbleBuilder.INSTANCE.escapeHtml("\"quoted\""));
    }

    @Test
    void escapeHtml_backtick_notEscaped() {
        assertEquals("`code`", PromptBubbleBuilder.INSTANCE.escapeHtml("`code`"));
    }

    @Test
    void escapeHtml_multipleAmpersands_allEscaped() {
        assertEquals("&amp;&amp;&amp;", PromptBubbleBuilder.INSTANCE.escapeHtml("&&&"));
    }

    @Test
    void escapeHtml_mixedContentWithSpecialChars() {
        assertEquals("x &lt; y &amp;&amp; y &gt; z",
                PromptBubbleBuilder.INSTANCE.escapeHtml("x < y && y > z"));
    }

    // ── buildBubbleHtml — null return on empty items ────────────────────

    @Test
    void buildBubbleHtml_emptyItemsList_returnsNull() {
        assertNull(PromptBubbleBuilder.INSTANCE.buildBubbleHtml("hello", Collections.emptyList()));
    }

    @Test
    void buildBubbleHtml_emptyItemsListWithOrcsInText_returnsNull() {
        assertNull(PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "some " + ORC + " text", List.of()));
    }

    // ── buildBubbleHtml — single file item ──────────────────────────────

    @Test
    void buildBubbleHtml_singleFileItem_producesChipWithPath() {
        ContextItemData item = fileItem("src/Main.java", "Main.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "Look at " + ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("class='prompt-ctx-chip'"));
        assertTrue(result.contains("href='openfile://src/Main.java'"));
        assertTrue(result.contains("title='src/Main.java'"));
        assertTrue(result.contains(">Main.java</a>"));
        assertTrue(result.startsWith("Look at "));
    }

    @Test
    void buildBubbleHtml_fileItemWithFileType_fileTypeIgnored() {
        // fileTypeName is stored in ContextItemData but not used in buildBubbleHtml
        ContextItemData item = new ContextItemData(
                "src/App.kt", "App.kt", 0, 0, "Kotlin", false);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("href='openfile://src/App.kt'"));
        assertFalse(result.contains("Kotlin"), "fileTypeName should not appear in output");
    }

    // ── buildBubbleHtml — selection items with line ranges ──────────────

    @Test
    void buildBubbleHtml_selectionWithPositiveStartLine_includesLineInHref() {
        ContextItemData item = selectionItem("src/Foo.kt", "Foo.kt:10-20", 10, 20);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "Check " + ORC + " please", List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("href='openfile://src/Foo.kt:10'"));
        assertTrue(result.contains("title='src/Foo.kt:10'"));
        assertTrue(result.contains(">Foo.kt:10-20</a>"));
    }

    @Test
    void buildBubbleHtml_selectionWithStartLineZero_omitsLine() {
        ContextItemData item = new ContextItemData(
                "src/Bar.kt", "Bar.kt", 0, 5, null, true);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        // startLine is 0 → condition (startLine > 0) is false → no line suffix
        assertTrue(result.contains("href='openfile://src/Bar.kt'"));
        assertTrue(result.contains("title='src/Bar.kt'"));
    }

    @Test
    void buildBubbleHtml_selectionWithNegativeStartLine_omitsLine() {
        ContextItemData item = new ContextItemData(
                "src/X.kt", "X.kt", -1, 5, null, true);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("href='openfile://src/X.kt'"));
        assertTrue(result.contains("title='src/X.kt'"));
    }

    @Test
    void buildBubbleHtml_notSelectionWithPositiveStartLine_omitsLine() {
        // isSelection=false → line is never included regardless of startLine value
        ContextItemData item = new ContextItemData(
                "src/Baz.kt", "Baz.kt", 5, 10, "Kotlin", false);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("href='openfile://src/Baz.kt'"),
                "isSelection=false should not include line in href");
        assertTrue(result.contains("title='src/Baz.kt'"),
                "isSelection=false should not include line in title");
    }

    // ── buildBubbleHtml — multiple items ────────────────────────────────

    @Test
    void buildBubbleHtml_multipleItems_replacedInOrder() {
        ContextItemData item1 = fileItem("a.java", "a.java");
        ContextItemData item2 = fileItem("b.java", "b.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC + " and " + ORC, List.of(item1, item2));

        assertNotNull(result);
        int posA = result.indexOf(">a.java</a>");
        int posB = result.indexOf(">b.java</a>");
        assertTrue(posA >= 0, "First item should appear in output");
        assertTrue(posB > posA, "Second item should appear after first");
        assertTrue(result.contains(" and "));
    }

    @Test
    void buildBubbleHtml_mixedFileAndSelectionItems() {
        ContextItemData file = fileItem("readme.md", "readme.md");
        ContextItemData sel = selectionItem("src/Lib.kt", "Lib.kt:1-50", 1, 50);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC + " and " + ORC, List.of(file, sel));

        assertNotNull(result);
        assertTrue(result.contains("href='openfile://readme.md'"));
        assertTrue(result.contains("href='openfile://src/Lib.kt:1'"));
    }

    // ── buildBubbleHtml — ORC / item count mismatch ─────────────────────

    @Test
    void buildBubbleHtml_moreOrcsThanItems_extraOrcsAppendedAsChar() {
        ContextItemData item = fileItem("x.java", "x.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC + " " + ORC, List.of(item));

        assertNotNull(result);
        // First ORC replaced with chip
        assertTrue(result.contains(">x.java</a>"));
        // Second ORC passes through appendHtmlChar as a regular char
        assertTrue(result.contains("\uFFFC"));
    }

    @Test
    void buildBubbleHtml_fewerOrcsThanItems_extraItemsIgnored() {
        ContextItemData item1 = fileItem("first.java", "first.java");
        ContextItemData item2 = fileItem("second.java", "second.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "text " + ORC, List.of(item1, item2));

        assertNotNull(result);
        assertTrue(result.contains(">first.java</a>"));
        assertFalse(result.contains("second.java"), "Extra items should not appear in output");
    }

    @Test
    void buildBubbleHtml_noOrcsInText_noChipsRendered() {
        ContextItemData item = fileItem("file.java", "file.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "plain text", List.of(item));

        assertNotNull(result);
        assertEquals("plain text", result);
        assertFalse(result.contains("prompt-ctx-chip"));
    }

    // ── buildBubbleHtml — HTML escaping in prompt body ──────────────────

    @Test
    void buildBubbleHtml_htmlTagsInPromptText_escaped() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "<script>alert('xss')</script> " + ORC, List.of(item));

        assertNotNull(result);
        assertFalse(result.startsWith("<script>"), "HTML tags should be escaped");
        assertTrue(result.contains("&lt;script&gt;"));
        assertTrue(result.contains("&#39;xss&#39;"));
    }

    @Test
    void buildBubbleHtml_ampersandInPromptText_escaped() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "a & b " + ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("a &amp; b"));
    }

    @Test
    void buildBubbleHtml_doubleQuotesInPromptText_escapedViaAppendHtmlChar() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "say \"hello\" " + ORC, List.of(item));

        assertNotNull(result);
        // appendHtmlChar escapes " as &quot;
        assertTrue(result.contains("&quot;hello&quot;"));
    }

    @Test
    void buildBubbleHtml_newlineInPromptText_preserved() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "line1\nline2 " + ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("line1\nline2"));
    }

    // ── buildBubbleHtml — HTML in item name / path ──────────────────────

    @Test
    void buildBubbleHtml_htmlInItemName_escapedInChipText() {
        ContextItemData item = new ContextItemData(
                "safe/path.java", "<b>bold</b>", 0, 0, null, false);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains(">&lt;b&gt;bold&lt;/b&gt;</a>"));
    }

    @Test
    void buildBubbleHtml_htmlInItemPath_escapedInTitle() {
        ContextItemData item = new ContextItemData(
                "path/<script>.java", "file.java", 0, 0, null, false);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("title='path/&lt;script&gt;.java'"));
    }

    @Test
    void buildBubbleHtml_singleQuoteInItemPath_escapedInTitle() {
        ContextItemData item = new ContextItemData(
                "it's/path.java", "path.java", 0, 0, null, false);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.contains("title='it&#39;s/path.java'"));
    }

    @Test
    void buildBubbleHtml_selectionPathWithHtmlChars_escapedInTitle() {
        ContextItemData item = new ContextItemData(
                "a&b.kt", "a&b.kt:5-10", 5, 10, null, true);
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        // title for selection: escapeHtml("a&b.kt:5")
        assertTrue(result.contains("title='a&amp;b.kt:5'"));
    }

    // ── buildBubbleHtml — trimming ──────────────────────────────────────

    @Test
    void buildBubbleHtml_leadingAndTrailingWhitespace_trimmed() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "  hello " + ORC + "  ", List.of(item));

        assertNotNull(result);
        assertFalse(result.startsWith(" "), "Leading whitespace should be trimmed");
        assertFalse(result.endsWith(" "), "Trailing whitespace should be trimmed");
    }

    @Test
    void buildBubbleHtml_onlyWhitespaceAroundOrc_trimmed() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                "  " + ORC + "  ", List.of(item));

        assertNotNull(result);
        assertTrue(result.startsWith("<a "), "Result should start with chip after trim");
        assertTrue(result.endsWith("</a>"), "Result should end with chip closing tag after trim");
    }

    // ── buildBubbleHtml — empty text ────────────────────────────────────

    @Test
    void buildBubbleHtml_emptyTextWithItems_returnsEmptyString() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml("", List.of(item));

        assertNotNull(result);
        assertEquals("", result);
    }

    @Test
    void buildBubbleHtml_onlyOrc_returnsChipOnly() {
        ContextItemData item = fileItem("f.java", "f.java");
        String result = PromptBubbleBuilder.INSTANCE.buildBubbleHtml(
                ORC, List.of(item));

        assertNotNull(result);
        assertTrue(result.startsWith("<a "));
        assertTrue(result.endsWith("</a>"));
    }
}
