package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.CodexClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.CodexClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.JsonObject;
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
 * rollout JSONL format (+ SQLite {@code threads} table) and the v2
 * {@link SessionMessage} model.
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

        List<SessionMessage> messages = CodexClientImporter.importRolloutFile(rollout);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role);
        assertEquals("Hello", extractText(messages.get(0)));
        assertEquals("assistant", messages.get(1).role);
        assertEquals("Hi there!", extractText(messages.get(1)));
    }

    @Test
    void importWithFunctionCallAndOutput() throws IOException {
        Path rollout = writeRollout(
            "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Read file\"}]}",
            "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"read_file\",\"id\":\"fc_abc\",\"arguments\":\"{\\\"path\\\":\\\"/test\\\"}\"}",
            "{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"file data\"}",
            "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"The file contains: file data\"}]}"
        );

        List<SessionMessage> messages = CodexClientImporter.importRolloutFile(rollout);

        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        boolean foundTool = false;
        boolean foundText = false;
        for (JsonObject part : assistant.parts) {
            String type = part.get("type").getAsString();
            if ("tool-invocation".equals(type)) {
                foundTool = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("call_1", inv.get("toolCallId").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("file data", inv.get("result").getAsString());
            } else if ("text".equals(type)) {
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

        List<SessionMessage> messages = CodexClientImporter.importRolloutFile(rollout);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        boolean hasReasoning = false;
        for (JsonObject part : assistant.parts) {
            if ("reasoning".equals(part.get("type").getAsString())) {
                hasReasoning = true;
                assertEquals("Let me consider...", part.get("text").getAsString());
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

        List<SessionMessage> messages = CodexClientImporter.importRolloutFile(rollout);
        assertEquals(2, messages.size());
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

        List<SessionMessage> messages = CodexClientImporter.importRolloutFile(rollout);
        // user + flushed assistant with orphan tool
        assertEquals(2, messages.size());
    }

    // ── Export tests ─────────────────────────────────────────────────

    @Test
    void exportSessionCreatesRolloutAndInsertThread() throws IOException, SQLException {
        List<SessionMessage> messages = List.of(
            userMessage("Hello"),
            assistantMessage("World")
        );

        Path sessionsDir = tempDir.resolve("sessions");
        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(messages, sessionsDir, dbPath);
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
        JsonObject toolPart = toolInvocationPart("call_1", "read_file", "{\"path\":\"/a\"}", "data");
        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(toolPart), System.currentTimeMillis(), null, null);

        Path sessionsDir = tempDir.resolve("sessions");
        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(
            List.of(userMessage("read"), assistant), sessionsDir, dbPath);
        assertNotNull(threadId);

        String content = Files.readString(sessionsDir.resolve(threadId).resolve("rollout.jsonl"));
        assertTrue(content.contains("\"type\":\"function_call\""));
        assertTrue(content.contains("\"call_id\":\"call_1\""));
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

        List<SessionMessage> messages = CodexClientImporter.importLatestThread(dbPath);
        assertEquals(2, messages.size());
        assertEquals("Test question", extractText(messages.get(0)));
        assertEquals("Test answer", extractText(messages.get(1)));
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

        List<SessionMessage> messages = CodexClientImporter.importLatestThread(dbPath);
        assertEquals(1, messages.size());
        assertEquals("New", extractText(messages.get(0)));
    }

    @Test
    void importLatestThreadIgnoresArchivedThreads() throws IOException, SQLException {
        Path rollout = tempDir.resolve("archived-rollout.jsonl");
        Files.writeString(rollout, "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Archived\"}]}");

        Path dbPath = tempDir.resolve("codex.db");
        createThreadsTable(dbPath);
        insertThread(dbPath, "arch-thread", rollout.toString(), 100, 100);
        archiveThread(dbPath, "arch-thread");

        List<SessionMessage> messages = CodexClientImporter.importLatestThread(dbPath);
        assertTrue(messages.isEmpty(), "Archived threads should not be imported");
    }

    @Test
    void importReturnsEmptyForMissingDb() {
        Path nonExistent = tempDir.resolve("nonexistent.db");
        assertTrue(CodexClientImporter.importLatestThread(nonExistent).isEmpty());
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesTextContent() throws IOException, SQLException {
        List<SessionMessage> original = List.of(
            userMessage("What is Rust?"),
            assistantMessage("A systems language.")
        );

        Path sessionsDir = tempDir.resolve("rt-sessions");
        Path dbPath = tempDir.resolve("rt-codex.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(original, sessionsDir, dbPath);
        assertNotNull(threadId);

        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        List<SessionMessage> imported = CodexClientImporter.importRolloutFile(rolloutFile);

        assertEquals(2, imported.size());
        assertEquals("What is Rust?", extractText(imported.get(0)));
        assertEquals("A systems language.", extractText(imported.get(1)));
    }

    @Test
    void roundTripPreservesToolCalls() throws IOException, SQLException {
        JsonObject textPart = textPart("Reading file");
        JsonObject toolPart = toolInvocationPart("call_1", "read_file", "{\"path\":\"/a\"}", "file data");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart, toolPart),
            System.currentTimeMillis(), null, null);

        List<SessionMessage> original = List.of(userMessage("Read /a"), assistant);

        Path sessionsDir = tempDir.resolve("rt-sessions-tools");
        Path dbPath = tempDir.resolve("rt-codex-tools.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(original, sessionsDir, dbPath);
        assertNotNull(threadId);

        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        List<SessionMessage> imported = CodexClientImporter.importRolloutFile(rolloutFile);

        assertEquals(2, imported.size());
        SessionMessage importedAssistant = imported.get(1);

        boolean foundTool = false;
        for (JsonObject part : importedAssistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                foundTool = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("file data", inv.get("result").getAsString());
            }
        }
        assertTrue(foundTool);
    }

    @Test
    void roundTripPreservesReasoning() throws IOException, SQLException {
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("type", "reasoning");
        reasoning.addProperty("text", "Let me think...");

        JsonObject text = textPart("Here is my answer");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(reasoning, text),
            System.currentTimeMillis(), null, null);

        List<SessionMessage> original = List.of(userMessage("Think"), assistant);

        Path sessionsDir = tempDir.resolve("rt-sessions-reasoning");
        Path dbPath = tempDir.resolve("rt-codex-reasoning.db");
        createThreadsTable(dbPath);

        String threadId = CodexClientExporter.exportSession(original, sessionsDir, dbPath);
        assertNotNull(threadId);

        Path rolloutFile = sessionsDir.resolve(threadId).resolve("rollout.jsonl");
        List<SessionMessage> imported = CodexClientImporter.importRolloutFile(rolloutFile);

        assertEquals(2, imported.size());
        SessionMessage importedAssistant = imported.get(1);

        boolean foundReasoning = false;
        for (JsonObject part : importedAssistant.parts) {
            if ("reasoning".equals(part.get("type").getAsString())) {
                foundReasoning = true;
                assertEquals("Let me think...", part.get("text").getAsString());
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

    private static SessionMessage userMessage(String text) {
        return new SessionMessage("u-" + text.hashCode(), "user", List.of(textPart(text)),
            System.currentTimeMillis(), null, null);
    }

    private static SessionMessage assistantMessage(String text) {
        return new SessionMessage("a-" + text.hashCode(), "assistant", List.of(textPart(text)),
            System.currentTimeMillis(), "Codex", null);
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject toolInvocationPart(String callId, String toolName, String args, String result) {
        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "result");
        invocation.addProperty("toolCallId", callId);
        invocation.addProperty("toolName", toolName);
        invocation.addProperty("args", args);
        invocation.addProperty("result", result);

        JsonObject part = new JsonObject();
        part.addProperty("type", "tool-invocation");
        part.add("toolInvocation", invocation);
        return part;
    }

    private static String extractText(SessionMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject part : msg.parts) {
            if ("text".equals(part.get("type").getAsString())) {
                sb.append(part.get("text").getAsString());
            }
        }
        return sb.toString();
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
