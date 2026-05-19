package com.github.catatafishen.agentbridge.session.v2;

import com.github.catatafishen.agentbridge.bridge.ContextFileRef;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the package-private pure static helpers in {@link SessionStoreV2}:
 * {@code readLegacyTimestamp} and {@code collectLegacyFileParts}.
 *
 * <p>These methods were previously tested only indirectly through {@code convertLegacyMessages}.
 * Direct tests make the edge-case coverage explicit.</p>
 */
class SessionStoreV2StaticMethodsTest {

    // ── readLegacyTimestamp ──────────────────────────────────────────────────

    @Test
    void readLegacyTimestamp_partHasTs_returnsPartTs() {
        JsonObject part = new JsonObject();
        part.addProperty("ts", "2024-01-15T10:00:00Z");

        assertEquals("2024-01-15T10:00:00Z",
            SessionStoreV2.readLegacyTimestamp(part, "2024-01-01T00:00:00Z"));
    }

    @Test
    void readLegacyTimestamp_partHasEmptyTs_fallsBackToMessage() {
        JsonObject part = new JsonObject();
        part.addProperty("ts", "");

        assertEquals("2024-01-01T00:00:00Z",
            SessionStoreV2.readLegacyTimestamp(part, "2024-01-01T00:00:00Z"));
    }

    @Test
    void readLegacyTimestamp_partHasNoTs_fallsBackToMessage() {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");

        assertEquals("msg-ts",
            SessionStoreV2.readLegacyTimestamp(part, "msg-ts"));
    }

    @Test
    void readLegacyTimestamp_partTsOverridesEvenWhenMessageTsEmpty() {
        JsonObject part = new JsonObject();
        part.addProperty("ts", "2024-06-15T12:00:00Z");

        assertEquals("2024-06-15T12:00:00Z",
            SessionStoreV2.readLegacyTimestamp(part, ""));
    }

    @Test
    void readLegacyTimestamp_bothEmpty_returnsEmptyString() {
        JsonObject part = new JsonObject();
        part.addProperty("ts", "");

        assertEquals("", SessionStoreV2.readLegacyTimestamp(part, ""));
    }

    @Test
    void readLegacyTimestamp_noTsFieldAndEmptyMessage_returnsEmpty() {
        JsonObject part = new JsonObject();

        assertEquals("", SessionStoreV2.readLegacyTimestamp(part, ""));
    }

    // ── collectLegacyFileParts ───────────────────────────────────────────────

    @Test
    void collectLegacyFileParts_emptyList_returnsEmpty() {
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(
            List.of(), 0, new HashSet<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void collectLegacyFileParts_startBeyondList_returnsEmpty() {
        List<JsonObject> parts = List.of(filePart("Foo.java", "/src/Foo.java", 0));
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(
            parts, 5, new HashSet<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void collectLegacyFileParts_singleFilePart_collected() {
        List<JsonObject> parts = new ArrayList<>();
        parts.add(filePart("Foo.java", "/src/Foo.java", 42));

        Set<Integer> consumed = new HashSet<>();
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(parts, 0, consumed);

        assertEquals(1, result.size());
        assertEquals("Foo.java", result.get(0).getName());
        assertEquals("/src/Foo.java", result.get(0).getPath());
        assertEquals(42, result.get(0).getLine());
        assertTrue(consumed.contains(0));
    }

    @Test
    void collectLegacyFileParts_multipleFileParts_allCollected() {
        List<JsonObject> parts = new ArrayList<>();
        parts.add(filePart("A.java", "/src/A.java", 1));
        parts.add(filePart("B.java", "/src/B.java", 2));
        parts.add(filePart("C.java", "/src/C.java", 3));

        Set<Integer> consumed = new HashSet<>();
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(parts, 0, consumed);

        assertEquals(3, result.size());
        assertEquals(Set.of(0, 1, 2), consumed);
    }

    @Test
    void collectLegacyFileParts_nonFilePartsSkipped() {
        List<JsonObject> parts = new ArrayList<>();
        parts.add(textPart("some text"));
        parts.add(filePart("A.java", "/src/A.java", 0));
        parts.add(textPart("more text"));
        parts.add(filePart("B.java", "/src/B.java", 0));

        Set<Integer> consumed = new HashSet<>();
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(parts, 0, consumed);

        assertEquals(2, result.size());
        assertEquals("A.java", result.get(0).getName());
        assertEquals("B.java", result.get(1).getName());
        assertEquals(Set.of(1, 3), consumed);
    }

    @Test
    void collectLegacyFileParts_startIdxSkipsEarlierParts() {
        List<JsonObject> parts = new ArrayList<>();
        parts.add(filePart("A.java", "/src/A.java", 0));
        parts.add(textPart("prompt text"));
        parts.add(filePart("B.java", "/src/B.java", 10));

        Set<Integer> consumed = new HashSet<>();
        // Start at index 2 — should only find B.java
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(parts, 2, consumed);

        assertEquals(1, result.size());
        assertEquals("B.java", result.get(0).getName());
        assertEquals(10, result.get(0).getLine());
        assertEquals(Set.of(2), consumed);
    }

    @Test
    void collectLegacyFileParts_missingFields_defaultToEmpty() {
        JsonObject minimal = new JsonObject();
        minimal.addProperty("type", "file");
        // no "filename", "path", or "line"

        List<JsonObject> parts = new ArrayList<>();
        parts.add(minimal);

        Set<Integer> consumed = new HashSet<>();
        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(parts, 0, consumed);

        assertEquals(1, result.size());
        assertEquals("", result.get(0).getName());
        assertEquals("", result.get(0).getPath());
        assertEquals(0, result.get(0).getLine());
    }

    @Test
    void collectLegacyFileParts_consumedSetIsAccumulated() {
        List<JsonObject> parts = new ArrayList<>();
        parts.add(filePart("A.java", "/a", 0));
        parts.add(filePart("B.java", "/b", 0));

        Set<Integer> consumed = new HashSet<>();
        consumed.add(99); // pre-existing entry

        SessionStoreV2.collectLegacyFileParts(parts, 0, consumed);

        assertEquals(Set.of(0, 1, 99), consumed,
            "Existing consumed entries should be preserved");
    }

    @Test
    void collectLegacyFileParts_filePartWithLineZero_lineIsZero() {
        List<JsonObject> parts = new ArrayList<>();
        parts.add(filePart("Foo.java", "/src/Foo.java", 0));

        List<ContextFileRef> result = SessionStoreV2.collectLegacyFileParts(
            parts, 0, new HashSet<>());

        assertEquals(0, result.get(0).getLine());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static JsonObject filePart(String filename, String path, int line) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "file");
        part.addProperty("filename", filename);
        part.addProperty("path", path);
        if (line > 0) {
            part.addProperty("line", line);
        }
        return part;
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return part;
    }
}
