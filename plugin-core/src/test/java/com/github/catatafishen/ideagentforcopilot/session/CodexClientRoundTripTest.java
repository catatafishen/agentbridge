package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.CodexClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.CodexClientImporter;
import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CodexClientImporter} and {@link CodexClientExporter}.
 * Validates import, export, and round-trip conversion between Codex's native
 * rollout JSONL format (+ SQLite {@code threads} table) and the
 * {@link EntryData} model.
 */
class CodexClientRoundTripTest {

    @TempDir
    Path tempDir;

    // ── Import tests (rollout JSONL parsing) ────────────────────────

    @Test
    void importBasicConversation() throws IOException {
        Path rollout = writeRollout(
            "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}",
            "{\"type\":\"message\",\"role\":\"assistant\",\"id\":\"resp_abc\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi there!\"}]}"
        );

        List<EntryData> entries = CodexClientImporter.importRolloutFile(rollout);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertEquals("Hello", ((EntryData.Prompt) entries.get(0)).getText());
        assertTrue(entries.get(1) instanceof EntryData.Text);
        assertEquals("Hi there!", ((EntryData.Text) entries.get(1)).getRaw().toString());
    }

    @Test
    void importWithFunctionCallAndOutput() throws IOException {
        Path rollout = writeRollout(
            "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Read file\"}]}",
            "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"read_file\",\"id\":\"fc_abc\",\"arguments\":\"{\\\"path\\\":\\\"/test\\\"}\"}",
            "{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"file data\"}",
            "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"The file contains: file data\"}]}"
        );

        List<EntryData> entries = CodexClientImporter.importRolloutFile(rollout);

        assertEquals(3, entries.size());

        boolean foundTool = false;
        boolean foundText = false;
        for (EntryData entry : entries) {
            if (entry instanceof EntryData.ToolCall tc) {
                foundTool = true;
                assertEquals("read_file", tc.getTitle());
                assertEquals("file data", tc.getResult());
            } else if (entry instanceof EntryData.Text) {
                foundText = true;
            }
        }
        assertTrue(foundTool, "Should have tool invocation");
        assertTrue(foundText, "Should have text output");
    }

    @Test
    void importWithReasoning() throws IOException {
        Path rollout = writeRollout(
            "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Think\"}]}",
            "{\"type\":\"reasoning\",\"id\":\"rs_abc\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"Let me consider...\"}]}",
            "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done thinking\"}]}"
        );

        List<EntryData> entries = CodexClientImporter.importRolloutFile(rollout);
        assertEquals(3, entries.size());

        boolean hasReasoning = false;
        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Thinking t) {
                hasReasoning = true;
                assertEquals("Let me consider...", t.getRaw().toString());
            }
        }
        assertTrue(hasReasoning, "Should have reasoning part");
    }

    @Test
    void importSkipsMalformedLines() throws IOException {
        Path rollout = writeRollout(
            "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hi\"}]}",
            "not json at all",
            "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}"
        );

        List<EntryData> entries = CodexClientImporter.importRolloutFile(rollout);
        assertEquals(2, entries.size());
    }

    @Test
    void importEmptyFileReturnsEmpty() throws IOException {
        Path empty = writeRollout();
        assertEquals(0, CodexClientImporter.importRolloutFile(empty).size());
    }

    @Test
    void importUnmatchedFunctionCallOutputCreatesOrphanPart() throws IOException {
        Path rollout = writeRollout(
            "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Go\"}]}",
            "{\"type\":\"function_call_output\",\"call_id\":\"orphan_1\",\"output\":\"orphan result\"}"
        );

        List<EntryData> entries = CodexClientImporter.importRolloutFile(rollout);
        // user prompt + flushed orphan tool call
        assertEquals(2, entries.size());
    }

    // ── Export tests ─────────────────────────────────────────────────

    @Test
    void exportSessionCreatesRolloutAndInsertThread() throws IOException, SQLException {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Hello"),
            new EntryData.Text(new StringBuilder("World"))
        );

        Path sessionsDir = tempDir.resolve("sessions");
        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(entries, sessionsDir, dbPath);
        assertNotNull(threadId);

        // Verify thread row exists
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM threads WHERE id = '" + threadId + "'")) {
            assertTrue(rs.next(), "Thread row should exist");
            assertTrue(rs.getString("rollout_path").contains(threadId));
            assertEquals(0, rs.getInt("archived"));
        }

        // Verify rollout file exists and contains expected content
        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        assertTrue(Files.exists(rolloutFile));
        String content = Files.readString(rolloutFile);
        assertTrue(content.contains("Hello"));
        assertTrue(content.contains("\"type\":\"message\""));
        assertTrue(content.contains("\"type\":\"input_text\""));
    }

    @Test
    void exportEmptyMessagesReturnsNull() {
        Path sessionsDir = tempDir.resolve("sessions");
        Path dbPath = tempDir.resolve("codex.db");
        assertNull(CodexClientExporter.exportSession(List.of(), sessionsDir, dbPath));
    }

    @Test
    void exportProducesToolCallItems() throws IOException, SQLException {
        EntryData.ToolCall toolCall = new EntryData.ToolCall("read_file", "{\"path\":\"/a\"}", "other", "data");

        Path sessionsDir = tempDir.resolve("sessions");
        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(
            List.of(new EntryData.Prompt("read"), toolCall), sessionsDir, dbPath);
        assertNotNull(threadId);

        String content = Files.readString(sessionsDir.resolve(threadId).resolve("rollout.jsonl"));
        assertTrue(content.contains("\"type\":\"function_call\""));
        assertTrue(content.contains("\"call_id\":"));
        assertTrue(content.contains("\"type\":\"function_call_output\""));
    }

    // ── SQLite import tests ─────────────────────────────────────────

    @Test
    void importLatestThreadReadsFromDb() throws IOException, SQLException {
        Path rolloutFile = tempDir.resolve("thread-rollout.jsonl");
        Files.writeString(rolloutFile, """
            {"type":"message","role":"user","content":[{"type":"input_text","text":"Test question"}]}
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Test answer"}]}
            """);

        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);
        insertThread(dbPath, "thread-1", rolloutFile.toString(), 1000, 2000);

        List<EntryData> entries = CodexClientImporter.importLatestThread(dbPath);
        assertEquals(2, entries.size());
        assertEquals("Test question", extractText(entries.get(0)));
        assertEquals("Test answer", extractText(entries.get(1)));
    }

    @Test
    void importLatestThreadPicksMostRecent() throws IOException, SQLException {
        Path oldRollout = tempDir.resolve("old-rollout.jsonl");
        Files.writeString(oldRollout, "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Old\"}]}");

        Path newRollout = tempDir.resolve("new-rollout.jsonl");
        Files.writeString(newRollout, "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"New\"}]}");

        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);
        insertThread(dbPath, "old-thread", oldRollout.toString(), 100, 100);
        insertThread(dbPath, "new-thread", newRollout.toString(), 200, 200);

        List<EntryData> entries = CodexClientImporter.importLatestThread(dbPath);
        assertEquals(1, entries.size());
        assertEquals("New", extractText(entries.get(0)));
    }

    @Test
    void importLatestThreadIgnoresArchivedThreads() throws IOException, SQLException {
        Path rollout = tempDir.resolve("archived-rollout.jsonl");
        Files.writeString(rollout, "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Archived\"}]}");

        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);
        insertThread(dbPath, "arch-thread", rollout.toString(), 100, 100);
        archiveThread(dbPath, "arch-thread");

        List<EntryData> entries = CodexClientImporter.importLatestThread(dbPath);
        assertTrue(entries.isEmpty(), "Archived threads should not be imported");
    }

    @Test
    void importReturnsEmptyForMissingDb() {
        Path nonExistent = tempDir.resolve("nonexistent.db");
        assertTrue(CodexClientImporter.importLatestThread(nonExistent).isEmpty());
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesTextContent() throws IOException, SQLException {
        List<EntryData> original = List.of(
            new EntryData.Prompt("What is Rust?"),
            new EntryData.Text(new StringBuilder("A systems language."))
        );

        Path sessionsDir = tempDir.resolve("rt-sessions");
        Path dbPath = tempDir.resolve("rt-codex.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(original, sessionsDir, dbPath);
        assertNotNull(threadId);

        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        List<EntryData> imported = CodexClientImporter.importRolloutFile(rolloutFile);

        assertEquals(2, imported.size());
        assertEquals("What is Rust?", extractText(imported.get(0)));
        assertEquals("A systems language.", extractText(imported.get(1)));
    }

    @Test
    void roundTripPreservesToolCalls() throws IOException, SQLException {
        EntryData.ToolCall toolCall = new EntryData.ToolCall("read_file", "{\"path\":\"/a\"}", "other", "file data");

        List<EntryData> original = List.of(
            new EntryData.Prompt("Read /a"),
            new EntryData.Text(new StringBuilder("Reading file")),
            toolCall
        );

        Path sessionsDir = tempDir.resolve("rt-sessions-tools");
        Path dbPath = tempDir.resolve("rt-codex-tools.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(original, sessionsDir, dbPath);
        assertNotNull(threadId);

        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        List<EntryData> imported = CodexClientImporter.importRolloutFile(rolloutFile);

        assertEquals(3, imported.size());

        boolean foundTool = false;
        for (EntryData entry : imported) {
            if (entry instanceof EntryData.ToolCall tc) {
                foundTool = true;
                assertEquals("read_file", tc.getTitle());
                assertEquals("file data", tc.getResult());
            }
        }
        assertTrue(foundTool);
    }

    @Test
    void roundTripPreservesReasoning() throws IOException, SQLException {
        List<EntryData> original = List.of(
            new EntryData.Prompt("Think"),
            new EntryData.Thinking(new StringBuilder("Let me think...")),
            new EntryData.Text(new StringBuilder("Here is my answer"))
        );

        Path sessionsDir = tempDir.resolve("rt-sessions-reasoning");
        Path dbPath = tempDir.resolve("rt-codex-reasoning.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(original, sessionsDir, dbPath);
        assertNotNull(threadId);

        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        List<EntryData> imported = CodexClientImporter.importRolloutFile(rolloutFile);

        assertEquals(3, imported.size());

        boolean foundReasoning = false;
        for (EntryData entry : imported) {
            if (entry instanceof EntryData.Thinking t) {
                foundReasoning = true;
                assertEquals("Let me think...", t.getRaw().toString());
            }
        }
        assertTrue(foundReasoning, "Reasoning should survive round-trip");
    }

    // ── Helper methods ──────────────────────────────────────────────

    private Path writeRollout(String... lines) throws IOException {
        Path file = tempDir.resolve("test-rollout-" + System.nanoTime() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String extractText(EntryData entry) {
        if (entry instanceof EntryData.Prompt p) return p.getText();
        if (entry instanceof EntryData.Text t) return t.getRaw().toString();
        return "";
    }

    private static void createThreadsTable(Path dbPath) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS threads (
                    id TEXT PRIMARY KEY,
                    rollout_path TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    archived INTEGER DEFAULT 0,
                    memory_mode TEXT
                )""");
        }
    }

    private static void insertThread(Path dbPath, String id, String rolloutPath, long created, long updated)
        throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var ps = conn.prepareStatement(
                 "INSERT INTO threads (id, rollout_path, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, rolloutPath);
            ps.setLong(3, created);
            ps.setLong(4, updated);
            ps.executeUpdate();
        }
    }

    private static void archiveThread(Path dbPath, String id) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var ps = conn.prepareStatement("UPDATE threads SET archived = 1 WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
