package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ConversationFileUtilsTest {

    // ── formatFileSize ──────────────────────────────────────────────────

    @Test
    void formatFileSize_zeroBytes() {
        assertEquals("0 B", ConversationFileUtils.formatFileSize(0));
    }

    @Test
    void formatFileSize_singleByte() {
        assertEquals("1 B", ConversationFileUtils.formatFileSize(1));
    }

    @Test
    void formatFileSize_bytesBelow1024() {
        assertEquals("512 B", ConversationFileUtils.formatFileSize(512));
    }

    @Test
    void formatFileSize_maxBytes() {
        assertEquals("1023 B", ConversationFileUtils.formatFileSize(1023));
    }

    @Test
    void formatFileSize_exactly1KB() {
        assertEquals("1.0 KB", ConversationFileUtils.formatFileSize(1024));
    }

    @Test
    void formatFileSize_fractionalKB() {
        // 1536 bytes = 1.5 KB
        assertEquals("1.5 KB", ConversationFileUtils.formatFileSize(1536));
    }

    @Test
    void formatFileSize_largeKB() {
        // 1023 * 1024 = 1047552 bytes = 1023.0 KB (just under 1 MB)
        assertEquals("1023.0 KB", ConversationFileUtils.formatFileSize(1023 * 1024L));
    }

    @Test
    void formatFileSize_exactly1MB() {
        assertEquals("1.0 MB", ConversationFileUtils.formatFileSize(1024 * 1024L));
    }

    @Test
    void formatFileSize_fractionalMB() {
        // 1.5 MB = 1572864 bytes
        assertEquals("1.5 MB", ConversationFileUtils.formatFileSize(1572864));
    }

    @Test
    void formatFileSize_largeMB() {
        // 100 MB
        assertEquals("100.0 MB", ConversationFileUtils.formatFileSize(100L * 1024 * 1024));
    }

    @Test
    void formatFileSize_veryLargeValue() {
        // 1 GB = 1024 MB
        assertEquals("1024.0 MB", ConversationFileUtils.formatFileSize(1024L * 1024 * 1024));
    }

    // ── formatDateMillis ────────────────────────────────────────────────

    @Test
    void formatDateMillis_zero() {
        assertEquals("—", ConversationFileUtils.formatDateMillis(0));
    }

    @Test
    void formatDateMillis_negative() {
        assertEquals("—", ConversationFileUtils.formatDateMillis(-1));
    }

    @Test
    void formatDateMillis_largeNegative() {
        assertEquals("—", ConversationFileUtils.formatDateMillis(Long.MIN_VALUE));
    }

    @Test
    void formatDateMillis_validEpoch() {
        // Use a known millis value and compute expected with the same formatter
        long millis = 1705312200000L; // some fixed timestamp
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        String expected = dateTime.format(ConversationFileUtils.DISPLAY_FORMATTER);
        assertEquals(expected, ConversationFileUtils.formatDateMillis(millis));
    }

    @Test
    void formatDateMillis_positiveOne() {
        // millis = 1 (epoch + 1ms) should produce a valid date string, not "—"
        String result = ConversationFileUtils.formatDateMillis(1);
        assertNotEquals("—", result);
        assertFalse(result.isEmpty());
    }

    @Test
    void formatDateMillis_recentTimestamp() {
        // 2025-06-15 12:00:00 UTC approx
        long millis = 1750000000000L;
        String result = ConversationFileUtils.formatDateMillis(millis);
        assertNotEquals("—", result);
        // The result should contain a year
        assertTrue(result.matches(".*\\d{4}.*"));
    }

    // ── formatTimestamp ─────────────────────────────────────────────────

    @Test
    void formatTimestamp_validFormat() {
        String input = "2025-01-15T14-30-00";
        LocalDateTime dateTime = LocalDateTime.parse(input, ConversationFileUtils.TIMESTAMP_PARSER);
        String expected = dateTime.format(ConversationFileUtils.DISPLAY_FORMATTER);
        assertEquals(expected, ConversationFileUtils.formatTimestamp(input));
    }

    @Test
    void formatTimestamp_anotherValidTimestamp() {
        String input = "2024-12-31T23-59-59";
        LocalDateTime dateTime = LocalDateTime.parse(input, ConversationFileUtils.TIMESTAMP_PARSER);
        String expected = dateTime.format(ConversationFileUtils.DISPLAY_FORMATTER);
        assertEquals(expected, ConversationFileUtils.formatTimestamp(input));
    }

    @Test
    void formatTimestamp_midnightTimestamp() {
        String input = "2025-06-01T00-00-00";
        LocalDateTime dateTime = LocalDateTime.parse(input, ConversationFileUtils.TIMESTAMP_PARSER);
        String expected = dateTime.format(ConversationFileUtils.DISPLAY_FORMATTER);
        assertEquals(expected, ConversationFileUtils.formatTimestamp(input));
    }

    @Test
    void formatTimestamp_invalidFormat_returnsRaw() {
        String input = "not-a-timestamp";
        assertEquals("not-a-timestamp", ConversationFileUtils.formatTimestamp(input));
    }

    @Test
    void formatTimestamp_emptyString_returnsRaw() {
        assertEquals("", ConversationFileUtils.formatTimestamp(""));
    }

    @Test
    void formatTimestamp_isoFormat_returnsRaw() {
        // Standard ISO format uses colons, not hyphens for time — should fail to parse
        String input = "2025-01-15T14:30:00";
        assertEquals(input, ConversationFileUtils.formatTimestamp(input));
    }

    @Test
    void formatTimestamp_partialTimestamp_returnsRaw() {
        String input = "2025-01-15";
        assertEquals(input, ConversationFileUtils.formatTimestamp(input));
    }

    // ── parseTimestampMillis ────────────────────────────────────────────

    @Test
    void parseTimestampMillis_validTimestamp() {
        String input = "2025-01-15T14-30-00";
        LocalDateTime dateTime = LocalDateTime.parse(input, ConversationFileUtils.TIMESTAMP_PARSER);
        long expected = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, ConversationFileUtils.parseTimestampMillis(input, -1));
    }

    @Test
    void parseTimestampMillis_anotherValidTimestamp() {
        String input = "2024-06-30T08-15-45";
        LocalDateTime dateTime = LocalDateTime.parse(input, ConversationFileUtils.TIMESTAMP_PARSER);
        long expected = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, ConversationFileUtils.parseTimestampMillis(input, 0));
    }

    @Test
    void parseTimestampMillis_invalidTimestamp_returnsFallback() {
        assertEquals(-1, ConversationFileUtils.parseTimestampMillis("garbage", -1));
    }

    @Test
    void parseTimestampMillis_emptyString_returnsFallback() {
        assertEquals(0, ConversationFileUtils.parseTimestampMillis("", 0));
    }

    @Test
    void parseTimestampMillis_isoFormat_returnsFallback() {
        assertEquals(42, ConversationFileUtils.parseTimestampMillis("2025-01-15T14:30:00", 42));
    }

    @Test
    void parseTimestampMillis_fallbackIsZero() {
        assertEquals(0, ConversationFileUtils.parseTimestampMillis("bad", 0));
    }

    @Test
    void parseTimestampMillis_fallbackIsLongMax() {
        assertEquals(Long.MAX_VALUE, ConversationFileUtils.parseTimestampMillis("bad", Long.MAX_VALUE));
    }

    @Test
    void parseTimestampMillis_validReturnsPositive() {
        long result = ConversationFileUtils.parseTimestampMillis("2025-01-15T14-30-00", -1);
        assertTrue(result > 0, "Parsed millis should be positive for a 2025 date");
    }

    // ── countMessages ───────────────────────────────────────────────────

    @Test
    void countMessages_validJsonArray(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("messages.json");
        Files.writeString(file, "[{\"role\":\"user\"},{\"role\":\"assistant\"},{\"role\":\"user\"}]");
        assertEquals(3, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_emptyArray(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("empty.json");
        Files.writeString(file, "[]");
        assertEquals(0, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_singleElement(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("single.json");
        Files.writeString(file, "[{\"msg\":\"hello\"}]");
        assertEquals(1, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_nestedArrays(@TempDir Path tempDir) throws IOException {
        // Only top-level array elements should be counted
        Path file = tempDir.resolve("nested.json");
        Files.writeString(file, "[[1,2,3],[4,5]]");
        assertEquals(2, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_primitiveElements(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("primitives.json");
        Files.writeString(file, "[1, 2, 3, 4, 5]");
        assertEquals(5, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_invalidJson_returnsNegativeOne(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("invalid.json");
        Files.writeString(file, "this is not json");
        assertEquals(-1, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_jsonObject_returnsNegativeOne(@TempDir Path tempDir) throws IOException {
        // A JSON object (not array) should cause getAsJsonArray() to fail
        Path file = tempDir.resolve("object.json");
        Files.writeString(file, "{\"key\":\"value\"}");
        assertEquals(-1, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_emptyFile_returnsNegativeOne(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");
        assertEquals(-1, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_nonExistentFile_returnsNegativeOne(@TempDir Path tempDir) {
        Path file = tempDir.resolve("does-not-exist.json");
        assertEquals(-1, ConversationFileUtils.countMessages(file));
    }

    @Test
    void countMessages_largeArray(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("large.json");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        Files.writeString(file, sb.toString());
        assertEquals(100, ConversationFileUtils.countMessages(file));
    }
}
