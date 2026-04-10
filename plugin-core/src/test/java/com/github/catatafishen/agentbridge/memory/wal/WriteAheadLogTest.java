package com.github.catatafishen.agentbridge.memory.wal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WriteAheadLog} — JSONL append-only log.
 */
class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws IOException {
        wal = new WriteAheadLog(tempDir.resolve("wal"));
        wal.initialize();
    }

    @Test
    void initializeCreatesDirectory() {
        assertTrue(Files.isDirectory(tempDir.resolve("wal")));
    }

    @Test
    void initializeCreatesLogFile() {
        assertTrue(Files.exists(wal.getLogFile()));
    }

    @Test
    void logAppendsJsonlEntry() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("wing", "test-project");
        payload.addProperty("room", "technical");

        wal.log("add_drawer", "drawer-123", payload);

        List<String> lines = Files.readAllLines(wal.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());

        JsonObject entry = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("add_drawer", entry.get("operation").getAsString());
        assertEquals("drawer-123", entry.get("id").getAsString());
        assertTrue(entry.has("timestamp"));
        assertTrue(entry.has("payload"));
        assertEquals("test-project", entry.getAsJsonObject("payload").get("wing").getAsString());
    }

    @Test
    void logAppendsMultipleEntries() throws IOException {
        for (int i = 0; i < 3; i++) {
            JsonObject payload = new JsonObject();
            payload.addProperty("index", i);
            wal.log("operation-" + i, "id-" + i, payload);
        }

        List<String> lines = Files.readAllLines(wal.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());

        for (int i = 0; i < 3; i++) {
            JsonObject entry = JsonParser.parseString(lines.get(i)).getAsJsonObject();
            assertEquals("operation-" + i, entry.get("operation").getAsString());
            assertEquals("id-" + i, entry.get("id").getAsString());
        }
    }

    @Test
    void logEntryHasIso8601Timestamp() throws IOException {
        wal.log("test", "id-1", new JsonObject());

        List<String> lines = Files.readAllLines(wal.getLogFile(), StandardCharsets.UTF_8);
        JsonObject entry = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        String timestamp = entry.get("timestamp").getAsString();
        // ISO 8601 format: should contain 'T' and 'Z' or timezone offset
        assertTrue(timestamp.contains("T"), "Timestamp should be ISO 8601: " + timestamp);
    }

    @Test
    void doubleInitializeIsSafe() throws IOException {
        wal.initialize();
        assertTrue(Files.exists(wal.getLogFile()));
    }

    @Test
    void getLogFileReturnsCorrectPath() {
        assertEquals(tempDir.resolve("wal").resolve("write_log.jsonl"), wal.getLogFile());
    }
}
