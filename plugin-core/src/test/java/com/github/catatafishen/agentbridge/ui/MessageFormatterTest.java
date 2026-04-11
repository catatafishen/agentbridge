package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MessageFormatterTest {

    // ── formatTimestamp ──────────────────────────────────────────────────

    @Test
    void formatTimestamp_compact() {
        String iso = "2024-01-15T14:30:00Z";
        String result = MessageFormatter.INSTANCE.formatTimestamp(iso, MessageFormatter.TimestampStyle.COMPACT);
        var zdt = Instant.parse(iso).atZone(ZoneId.systemDefault());
        assertEquals(String.format("%02d:%02d", zdt.getHour(), zdt.getMinute()), result);
    }

    @Test
    void formatTimestamp_full() {
        String iso = "2024-06-15T10:30:00Z";
        String result = MessageFormatter.INSTANCE.formatTimestamp(iso, MessageFormatter.TimestampStyle.FULL);
        var zdt = Instant.parse(iso).atZone(ZoneId.systemDefault());
        assertEquals(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").format(zdt), result);
    }

    @Test
    void formatTimestamp_fallbackOnBadInput() {
        assertEquals("not-a-timestamp",
            MessageFormatter.INSTANCE.formatTimestamp("not-a-timestamp", MessageFormatter.TimestampStyle.COMPACT));
    }

    @Test
    void formatTimestamp_emptyString() {
        assertEquals("", MessageFormatter.INSTANCE.formatTimestamp("", MessageFormatter.TimestampStyle.COMPACT));
    }

    // ── timestamp ───────────────────────────────────────────────────────

    @Test
    void timestamp_isParseable() {
        String ts = MessageFormatter.INSTANCE.timestamp();
        assertDoesNotThrow(() -> Instant.parse(ts));
    }

    // ── escapeHtml ──────────────────────────────────────────────────────

    @Test
    void escapeHtml_ampersand() {
        assertEquals("a &amp; b", MessageFormatter.INSTANCE.escapeHtml("a & b"));
    }

    @Test
    void escapeHtml_allSpecialChars() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;&#96;", MessageFormatter.INSTANCE.escapeHtml("&<>\"'`"));
    }

    @Test
    void escapeHtml_plainText() {
        assertEquals("hello world", MessageFormatter.INSTANCE.escapeHtml("hello world"));
    }

    @Test
    void escapeHtml_empty() {
        assertEquals("", MessageFormatter.INSTANCE.escapeHtml(""));
    }

    // ── escapeJs ────────────────────────────────────────────────────────

    @Test
    void escapeJs_singleQuote() {
        assertEquals("it\\'s", MessageFormatter.INSTANCE.escapeJs("it's"));
    }

    @Test
    void escapeJs_backslash() {
        assertEquals("a\\\\b", MessageFormatter.INSTANCE.escapeJs("a\\b"));
    }

    @Test
    void escapeJs_backtick() {
        assertEquals("a\\`b", MessageFormatter.INSTANCE.escapeJs("a`b"));
    }

    @Test
    void escapeJs_newline() {
        assertEquals("a\\nb", MessageFormatter.INSTANCE.escapeJs("a\nb"));
    }

    @Test
    void escapeJs_carriageReturn() {
        assertEquals("a\\rb", MessageFormatter.INSTANCE.escapeJs("a\rb"));
    }

    @Test
    void escapeJs_empty() {
        assertEquals("", MessageFormatter.INSTANCE.escapeJs(""));
    }

    // ── encodeBase64 ────────────────────────────────────────────────────

    @Test
    void encodeBase64_simpleString() {
        String expected = Base64.getEncoder().encodeToString("hello".getBytes());
        assertEquals(expected, MessageFormatter.INSTANCE.encodeBase64("hello"));
    }

    @Test
    void encodeBase64_unicode() {
        String input = "héllo wörld";
        String expected = Base64.getEncoder().encodeToString(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, MessageFormatter.INSTANCE.encodeBase64(input));
    }

    @Test
    void encodeBase64_empty() {
        assertEquals("", MessageFormatter.INSTANCE.encodeBase64(""));
    }

    // ── formatToolSubtitle ──────────────────────────────────────────────

    @Test
    void formatToolSubtitle_nullArgs() {
        assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", null));
    }

    @Test
    void formatToolSubtitle_blankArgs() {
        assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "  "));
    }

    @Test
    void formatToolSubtitle_unknownTool() {
        assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("unknown_tool_xyz", "{\"path\": \"test.txt\"}"));
    }

    @Test
    void formatToolSubtitle_readFile() {
        assertEquals("test.txt",
            MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"path\": \"test.txt\"}"));
    }

    @Test
    void formatToolSubtitle_longPathTruncated() {
        String longPath = "a".repeat(50);
        String result = MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"path\": \"" + longPath + "\"}");
        assertNotNull(result);
        assertTrue(result.startsWith("\u2026")); // starts with "…"
        assertEquals(38, result.length()); // "…" (1 char) + last 37 chars
    }

    @Test
    void formatToolSubtitle_shortPathNotTruncated() {
        assertEquals("src/main/Foo.java",
            MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"path\": \"src/main/Foo.java\"}"));
    }

    @Test
    void formatToolSubtitle_invalidJson() {
        assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "not json"));
    }

    @Test
    void formatToolSubtitle_missingKey() {
        assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"other\": \"value\"}"));
    }

    @Test
    void formatToolSubtitle_searchTextQuery() {
        assertEquals("TODO",
            MessageFormatter.INSTANCE.formatToolSubtitle("search_text", "{\"query\": \"TODO\"}"));
    }

    // ── ChipStatus constants ────────────────────────────────────────────

    @Test
    void chipStatusConstants() {
        assertEquals("pending", MessageFormatter.ChipStatus.PENDING);
        assertEquals("running", MessageFormatter.ChipStatus.RUNNING);
        assertEquals("complete", MessageFormatter.ChipStatus.COMPLETE);
        assertEquals("failed", MessageFormatter.ChipStatus.FAILED);
        assertEquals("denied", MessageFormatter.ChipStatus.DENIED);
        assertEquals("thinking", MessageFormatter.ChipStatus.THINKING);
    }
}
