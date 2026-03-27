package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.OpenCodeClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenCodeClientImporter} and {@link OpenCodeClientExporter}.
 * Uses temporary SQLite databases to validate import, export, and round-trip
 * conversion between OpenCode's native format and the v2 {@link SessionMessage} model.
 */
class OpenCodeClientRoundTripTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String PROJECT_DIR = "/home/user/project";

    @TempDir
    Path tempDir;

    private Path dbPath;

    @BeforeEach
    void setUp() throws SQLException {
        dbPath = tempDir.resolve("opencode.db");
        createTables(dbPath);
    }

    // ── Import tests ────────────────────────────────────────────────

    @Test
    void importBasicConversation() throws SQLException {
        String sessionId = insertSession(dbPath, "s1", PROJECT_DIR, 1000, 2000);

        insertMessageWithParts(dbPath, "m1", sessionId, "user", 1000,
            textPartJson("Hello world"));
        insertMessageWithParts(dbPath, "m2", sessionId, "assistant", 1001,
            textPartJson("Hi there!"));

        List<SessionMessage> messages = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role);
        assertEquals("Hello world", extractText(messages.get(0)));
        assertEquals("assistant", messages.get(1).role);
        assertEquals("Hi there!", extractText(messages.get(1)));
    }

    @Test
    void importWithToolInvocation() throws SQLException {
        String sessionId = insertSession(dbPath, "s1", PROJECT_DIR, 1000, 2000);

        insertMessageWithParts(dbPath, "m1", sessionId, "user", 1000,
            textPartJson("Read a file"));

        JsonObject toolInvPart = new JsonObject();
        toolInvPart.addProperty("type", "tool-invocation");
        JsonObject inv = new JsonObject();
        inv.addProperty("state", "result");
        inv.addProperty("toolCallId", "tc1");
        inv.addProperty("toolName", "read_file");
        inv.addProperty("args", "{\"path\":\"/test\"}");
        inv.addProperty("result", "file data");
        toolInvPart.add("toolInvocation", inv);

        insertMessageWithParts(dbPath, "m2", sessionId, "assistant", 1001,
            textPartJson("I'll read it"), GSON.toJson(toolInvPart));

        List<SessionMessage> messages = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        boolean hasTool = false;
        for (JsonObject part : assistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                hasTool = true;
                JsonObject toolInv = part.getAsJsonObject("toolInvocation");
                assertEquals("tc1", toolInv.get("toolCallId").getAsString());
                assertEquals("read_file", toolInv.get("toolName").getAsString());
            }
        }
        assertTrue(hasTool, "Should have tool invocation part");
    }

    @Test
    void importFallsBackToMessageDataPartsWhenNoPartRows() throws SQLException {
        String sessionId = insertSession(dbPath, "s1", PROJECT_DIR, 1000, 2000);

        // Insert message with embedded parts in data JSON, but no part table rows
        JsonObject msgData = new JsonObject();
        msgData.addProperty("role", "user");
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Embedded text");
        parts.add(textPart);
        msgData.add("parts", parts);

        insertMessageRaw(dbPath, "m1", sessionId, GSON.toJson(msgData), 1000);

        List<SessionMessage> messages = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(1, messages.size());
        assertEquals("Embedded text", extractText(messages.get(0)));
    }

    @Test
    void importExtractsModelFromMetadata() throws SQLException {
        String sessionId = insertSession(dbPath, "s1", PROJECT_DIR, 1000, 2000);

        JsonObject msgData = new JsonObject();
        msgData.addProperty("role", "assistant");
        JsonObject metadata = new JsonObject();
        JsonObject assistantMeta = new JsonObject();
        assistantMeta.addProperty("modelID", "claude-sonnet-4");
        metadata.add("assistant", assistantMeta);
        msgData.add("metadata", metadata);

        insertMessageRaw(dbPath, "m1", sessionId, GSON.toJson(msgData), 1000);
        insertPart(dbPath, "p1", "m1", textPartJson("Answer"), 1000);

        List<SessionMessage> messages = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(1, messages.size());
        assertEquals("claude-sonnet-4", messages.get(0).model);
    }

    @Test
    void importPicksLatestSessionForProject() throws SQLException {
        insertSession(dbPath, "old-session", PROJECT_DIR, 100, 100);
        insertSession(dbPath, "new-session", PROJECT_DIR, 200, 200);

        insertMessageWithParts(dbPath, "m-old", "old-session", "user", 100,
            textPartJson("Old message"));
        insertMessageWithParts(dbPath, "m-new", "new-session", "user", 200,
            textPartJson("New message"));

        List<SessionMessage> messages = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(1, messages.size());
        assertEquals("New message", extractText(messages.get(0)));
    }

    @Test
    void importIgnoresSessionsFromOtherProjects() throws SQLException {
        insertSession(dbPath, "other-session", "/other/project", 1000, 2000);
        insertMessageWithParts(dbPath, "m-other", "other-session", "user", 1000,
            textPartJson("Other project"));

        List<SessionMessage> messages = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertTrue(messages.isEmpty(), "Should not import sessions from different projects");
    }

    @Test
    void importReturnsEmptyForMissingDb() {
        Path nonExistent = tempDir.resolve("nonexistent.db");
        assertTrue(OpenCodeClientImporter.importLatestSession(nonExistent, PROJECT_DIR).isEmpty());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportCreatesSessionAndMessageRows() throws SQLException {
        List<SessionMessage> messages = List.of(
            userMessage("Hello"),
            assistantMessage("World")
        );

        String sessionId = OpenCodeClientExporter.exportSession(messages, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        // Verify session row
        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("SELECT * FROM session WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(PROJECT_DIR, rs.getString("directory"));
            }
        }

        // Verify message rows
        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM message WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void exportCreatesPartRows() throws SQLException {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Answer text");

        JsonObject reasoningPart = new JsonObject();
        reasoningPart.addProperty("type", "reasoning");
        reasoningPart.addProperty("text", "Thinking...");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(reasoningPart, textPart),
            System.currentTimeMillis(), null, null);

        String sessionId = OpenCodeClientExporter.exportSession(
            List.of(userMessage("Q"), assistant), dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        // Count total part rows for the session
        int totalParts = 0;
        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT COUNT(*) FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ?""")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                totalParts = rs.getInt(1);
            }
        }
        // user has 1 text part + assistant has 2 parts (reasoning + text) = 3
        assertEquals(3, totalParts);
    }

    @Test
    void exportEmptyMessagesReturnsNull() {
        assertNull(OpenCodeClientExporter.exportSession(List.of(), dbPath, PROJECT_DIR));
    }

    @Test
    void exportToNonExistentDbCreatesIt() {
        Path freshDb = tempDir.resolve("fresh/opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(
            List.of(userMessage("hi")), freshDb, PROJECT_DIR);
        assertNotNull(sessionId, "Exporter should create the DB and succeed");
        assertTrue(Files.exists(freshDb), "Database file should have been created");
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesTextContent() throws SQLException {
        List<SessionMessage> original = List.of(
            userMessage("What is Rust?"),
            assistantMessage("A systems language.")
        );

        String sessionId = OpenCodeClientExporter.exportSession(original, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        List<SessionMessage> imported = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(2, imported.size());
        assertEquals("What is Rust?", extractText(imported.get(0)));
        assertEquals("A systems language.", extractText(imported.get(1)));
    }

    @Test
    void roundTripPreservesToolInvocations() throws SQLException {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Reading file");

        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "file data");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart, toolPart),
            System.currentTimeMillis(), null, null);

        List<SessionMessage> original = List.of(userMessage("Read /a"), assistant);

        String sessionId = OpenCodeClientExporter.exportSession(original, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        List<SessionMessage> imported = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(2, imported.size());

        SessionMessage importedAssistant = imported.get(1);
        boolean foundTool = false;
        for (JsonObject part : importedAssistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                foundTool = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("tc1", inv.get("toolCallId").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
            }
        }
        assertTrue(foundTool, "Tool invocation should survive round-trip");
    }

    @Test
    void roundTripMultipleTurns() throws SQLException {
        List<SessionMessage> original = List.of(
            userMessage("Question 1"),
            assistantMessage("Answer 1"),
            userMessage("Question 2"),
            assistantMessage("Answer 2")
        );

        OpenCodeClientExporter.exportSession(original, dbPath, PROJECT_DIR);

        List<SessionMessage> imported = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(4, imported.size());
        assertEquals("Question 1", extractText(imported.get(0)));
        assertEquals("Answer 1", extractText(imported.get(1)));
        assertEquals("Question 2", extractText(imported.get(2)));
        assertEquals("Answer 2", extractText(imported.get(3)));
    }

    // ── Helper methods ──────────────────────────────────────────────

    private static SessionMessage userMessage(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return new SessionMessage("u-" + text.hashCode(), "user", List.of(part),
            System.currentTimeMillis(), null, null);
    }

    private static SessionMessage assistantMessage(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return new SessionMessage("a-" + text.hashCode(), "assistant", List.of(part),
            System.currentTimeMillis(), "OpenCode", null);
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

    private static String textPartJson(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return GSON.toJson(part);
    }

    private static Connection connect(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /**
     * Creates tables matching the real OpenCode schema (drizzle-managed).
     * This ensures tests validate against production constraints.
     */
    private static void createTables(Path dbPath) throws SQLException {
        try (Connection conn = connect(dbPath);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS project (
                    id TEXT PRIMARY KEY,
                    worktree TEXT NOT NULL,
                    vcs TEXT,
                    name TEXT,
                    icon_url TEXT,
                    icon_color TEXT,
                    time_created INTEGER NOT NULL,
                    time_updated INTEGER NOT NULL,
                    time_initialized INTEGER,
                    sandboxes TEXT NOT NULL,
                    commands TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    parent_id TEXT,
                    slug TEXT NOT NULL,
                    directory TEXT NOT NULL,
                    title TEXT NOT NULL,
                    version TEXT NOT NULL,
                    share_url TEXT,
                    summary_additions INTEGER,
                    summary_deletions INTEGER,
                    summary_files INTEGER,
                    summary_diffs TEXT,
                    revert TEXT,
                    permission TEXT,
                    time_created INTEGER NOT NULL,
                    time_updated INTEGER NOT NULL,
                    time_compacting INTEGER,
                    time_archived INTEGER,
                    workspace_id TEXT,
                    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS message (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    time_created INTEGER NOT NULL,
                    time_updated INTEGER NOT NULL,
                    data TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS part (
                    id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    time_created INTEGER NOT NULL,
                    time_updated INTEGER NOT NULL,
                    data TEXT NOT NULL,
                    FOREIGN KEY (message_id) REFERENCES message(id) ON DELETE CASCADE
                )""");
        }
    }

    private static String insertSession(Path dbPath, String id, String directory, long created, long updated)
        throws SQLException {
        // First ensure project exists
        String projectId = "test-project-id";
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR IGNORE INTO project (id, worktree, time_created, time_updated, sandboxes) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, projectId);
            ps.setString(2, directory);
            ps.setLong(3, created);
            ps.setLong(4, updated);
            ps.setString(5, "[]");
            ps.executeUpdate();
        }

        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO session (id, project_id, slug, directory, title, version, " +
                     "time_created, time_updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, projectId);
            ps.setString(3, "test-slug");
            ps.setString(4, directory);
            ps.setString(5, "Test Session");
            ps.setString(6, "1.2.0");
            ps.setLong(7, created);
            ps.setLong(8, updated);
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertMessageRaw(Path dbPath, String id, String sessionId, String data, long timeCreated)
        throws SQLException {
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO message (id, session_id, data, time_created, time_updated) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            ps.setString(3, data);
            ps.setLong(4, timeCreated);
            ps.setLong(5, timeCreated);
            ps.executeUpdate();
        }
    }

    private static void insertMessageWithParts(Path dbPath, String msgId, String sessionId,
                                               String role, long timeCreated, String... partJsons)
        throws SQLException {
        JsonObject msgData = new JsonObject();
        msgData.addProperty("role", role);
        insertMessageRaw(dbPath, msgId, sessionId, GSON.toJson(msgData), timeCreated);

        for (int i = 0; i < partJsons.length; i++) {
            insertPart(dbPath, msgId + "-p" + i, msgId, partJsons[i], timeCreated);
        }
    }

    private static void insertPart(Path dbPath, String partId, String messageId, String data, long timeCreated)
        throws SQLException {
        // Look up session_id from message
        String sessionId;
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement("SELECT session_id FROM message WHERE id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                sessionId = rs.next() ? rs.getString("session_id") : "unknown";
            }
        }

        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO part (id, message_id, session_id, data, time_created, time_updated) " +
                     "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, partId);
            ps.setString(2, messageId);
            ps.setString(3, sessionId);
            ps.setString(4, data);
            ps.setLong(5, timeCreated);
            ps.setLong(6, timeCreated);
            ps.executeUpdate();
        }
    }
}
