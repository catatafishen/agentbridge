package com.github.catatafishen.agentbridge.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Pure utility methods for conversation file metadata: timestamp parsing/formatting,
 * file size formatting, and message counting. Extracted from ChatHistoryConfigurable
 * to enable unit testing without UI dependencies.
 */
public final class ConversationFileUtils {

    static final DateTimeFormatter TIMESTAMP_PARSER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
    static final DateTimeFormatter DISPLAY_FORMATTER =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;

    private ConversationFileUtils() {
    }

    /**
     * Formats a byte count as a human-readable size string (e.g., "1.5 KB", "3.2 MB").
     */
    public static String formatFileSize(long bytes) {
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return String.format("%.1f KB", bytes / (double) KB);
        return String.format("%.1f MB", bytes / (double) MB);
    }

    /**
     * Formats an epoch-millis timestamp using the standard display format.
     * Returns "—" for non-positive values.
     */
    public static String formatDateMillis(long millis) {
        if (millis <= 0) return "—";
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return dateTime.format(DISPLAY_FORMATTER);
    }

    /**
     * Formats an archived conversation filename timestamp (e.g., "2025-01-15T14-30-00")
     * into a human-readable display string (e.g., "Jan 15, 2025 2:30 PM").
     */
    public static String formatTimestamp(String timestamp) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(timestamp, TIMESTAMP_PARSER);
            return dateTime.format(DISPLAY_FORMATTER);
        } catch (DateTimeParseException e) {
            return timestamp;
        }
    }

    /**
     * Parses an archived conversation filename timestamp into epoch milliseconds.
     * Returns the fallback value on parse failure.
     */
    public static long parseTimestampMillis(String timestamp, long fallback) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(timestamp, TIMESTAMP_PARSER);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    /**
     * Counts the number of top-level messages in a conversation JSON file.
     * Returns -1 if the file cannot be read or parsed.
     */
    public static int countMessages(Path file) {
        try {
            String content = Files.readString(file);
            JsonArray array = JsonParser.parseString(content).getAsJsonArray();
            return array.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Counts lines in a JSONL file (one JSON object per line).
     * Returns -1 if the file cannot be read.
     */
    public static int countJsonlLines(Path file) {
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) count++;
            }
            return count;
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
