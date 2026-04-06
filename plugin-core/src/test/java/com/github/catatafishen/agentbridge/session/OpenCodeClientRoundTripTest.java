package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.agentbridge.session.importers.OpenCodeClientImporter;
import com.github.catatafishen.agentbridge.ui.EntryData;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenCodeClientImporter} and {@link OpenCodeClientExporter}.
 * Uses temporary SQLite databases to validate import, export, and round-trip
 * conversion between OpenCode's native format and the {@link EntryData} model.
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

        List<EntryData> entries = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertEquals("Hello world", ((EntryData.Prompt) entries.get(0)).getText());
        assertTrue(entries.get(1) instanceof EntryData.Text);
        assertEquals("Hi there!", ((EntryData.Text) entries.get(1)).getRaw());
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

        List<EntryData> entries = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(3, entries.size());

        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertTrue(entries.get(1) instanceof EntryData.Text);
        assertTrue(entries.get(2) instanceof EntryData.ToolCall);

        EntryData.ToolCall toolCall = (EntryData.ToolCall) entries.get(2);
        assertEquals("read_file", toolCall.getTitle());
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

        List<EntryData> entries = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertEquals("Embedded text", ((EntryData.Prompt) entries.get(0)).getText());
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

        List<EntryData> entries = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0) instanceof EntryData.Text);
        assertEquals("claude-sonnet-4", ((EntryData.Text) entries.get(0)).getModel());
    }

    @Test
    void importPicksLatestSessionForProject() throws SQLException {
        insertSession(dbPath, "old-session", PROJECT_DIR, 100, 100);
        insertSession(dbPath, "new-session", PROJECT_DIR, 200, 200);

        insertMessageWithParts(dbPath, "m-old", "old-session", "user", 100,
            textPartJson("Old message"));
        insertMessageWithParts(dbPath, "m-new", "new-session", "user", 200,
            textPartJson("New message"));

        List<EntryData> entries = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertEquals("New message", ((EntryData.Prompt) entries.get(0)).getText());
    }

    @Test
    void importIgnoresSessionsFromOtherProjects() throws SQLException {
        insertSession(dbPath, "other-session", "/other/project", 1000, 2000);
        insertMessageWithParts(dbPath, "m-other", "other-session", "user", 1000,
            textPartJson("Other project"));

        List<EntryData> entries = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertTrue(entries.isEmpty(), "Should not import sessions from different projects");
    }

    @Test
    void importReturnsEmptyForMissingDb() {
        Path nonExistent = tempDir.resolve("nonexistent.db");
        assertTrue(OpenCodeClientImporter.importLatestSession(nonExistent, PROJECT_DIR).isEmpty());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportCreatesSessionAndMessageRows() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("Hello"),
            textEntry("World")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
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
        List<EntryData> entries = List.of(
            promptEntry("Q"),
            new EntryData.Thinking("Thinking..."),
            textEntry("Answer text")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
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
            List.of(promptEntry("hi")), freshDb, PROJECT_DIR);
        assertNotNull(sessionId, "Exporter should create the DB and succeed");
        assertTrue(Files.exists(freshDb), "Database file should have been created");
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void exportedToolPartHasStateTime() throws SQLException {
        // Regression test: toModelMessages in OpenCode accesses part.state.time.compacted
        // for every tool part. If state.time is absent the property access throws a TypeError
        // which causes the session to return end_turn with zero tokens and no response.
        List<EntryData> entries = List.of(
            promptEntry("Q"),
            toolCallEntry("read_file", "{\"path\":\"/a\"}", "file data")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT p.data FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ? AND p.data LIKE '%"type":"tool"%'""")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Should have a tool part");
                JsonObject partData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                JsonObject state = partData.getAsJsonObject("state");
                assertNotNull(state, "Tool part must have state");
                assertTrue(state.has("time"), "state must have time (required by toModelMessages)");
                JsonObject time = state.getAsJsonObject("time");
                assertTrue(time.has("start"), "state.time must have start");
                assertTrue(time.has("end"), "state.time must have end");
            }
        }
    }

    @Test
    void exportedTextPartHasNoTime() throws SQLException {
        // Real OpenCode text parts do NOT carry a top-level time field (only reasoning parts do).
        // Writing time on text parts may cause Zod strict-mode validation failures in OpenCode.
        String sessionId = OpenCodeClientExporter.exportSession(
            List.of(promptEntry("Hello")), dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT p.data FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ? AND p.data LIKE '%"type":"text"%'""")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Should have a text part");
                JsonObject partData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                assertFalse(partData.has("time"), "text part must NOT have a top-level time field");
            }
        }
    }

    @Test
    void subagentPartConvertedToText() throws SQLException {
        // Regression: writing type="subagent" to OpenCode's DB causes Zod validation failure
        // because its discriminated union on "type" doesn't include "subagent".
        // The exporter must convert subagent parts to text summaries instead.
        EntryData.SubAgent subAgent = new EntryData.SubAgent("explore", "Exploring codebase");
        subAgent.setResult("Found 3 files");
        subAgent.setStatus("done");

        List<EntryData> entries = List.of(
            promptEntry("Search"),
            subAgent
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT p.data FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ?""")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                boolean foundSubagentText = false;
                while (rs.next()) {
                    JsonObject partData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                    String type = partData.get("type").getAsString();
                    assertFalse("subagent".equals(type), "subagent type must not be written to DB");
                    if ("text".equals(type)) {
                        String text = partData.get("text").getAsString();
                        if (text.contains("Exploring codebase")) {
                            foundSubagentText = true;
                        }
                    }
                }
                assertTrue(foundSubagentText, "Should have converted subagent to text part");
            }
        }
    }

    @Test
    void unknownPartTypeSkipped() throws SQLException {
        // Unknown entry types (e.g. Status) must be skipped rather than written
        // as-is, because OpenCode's Zod discriminated union would fail validation.
        List<EntryData> entries = List.of(
            new EntryData.Status("\u23F3", "Thinking..."),
            textEntry("Hello")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT p.data FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ?""")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                boolean foundStatus = false;
                int count = 0;
                while (rs.next()) {
                    count++;
                    JsonObject partData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                    if ("status".equals(partData.get("type").getAsString())) foundStatus = true;
                }
                assertFalse(foundStatus, "status part must not be written to DB");
                assertEquals(1, count, "Only the text part should be written");
            }
        }
    }

    @Test
    void roundTripPreservesTextContent() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("What is Rust?"),
            textEntry("A systems language.")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        List<EntryData> imported = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(2, imported.size());
        assertTrue(imported.get(0) instanceof EntryData.Prompt);
        assertEquals("What is Rust?", ((EntryData.Prompt) imported.get(0)).getText());
        assertTrue(imported.get(1) instanceof EntryData.Text);
        assertEquals("A systems language.", ((EntryData.Text) imported.get(1)).getRaw());
    }

    @Test
    void roundTripPreservesToolInvocations() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("Read /a"),
            textEntry("Reading file"),
            toolCallEntry("read_file", "{\"path\":\"/a\"}", "file data")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        List<EntryData> imported = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(3, imported.size());

        assertTrue(imported.get(0) instanceof EntryData.Prompt);
        assertTrue(imported.get(1) instanceof EntryData.Text);
        assertTrue(imported.get(2) instanceof EntryData.ToolCall);

        EntryData.ToolCall toolCall = (EntryData.ToolCall) imported.get(2);
        assertEquals("read_file", toolCall.getTitle());
    }

    @Test
    void roundTripMultipleTurns() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("Question 1"),
            textEntry("Answer 1"),
            promptEntry("Question 2"),
            textEntry("Answer 2")
        );

        OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);

        List<EntryData> imported = OpenCodeClientImporter.importLatestSession(dbPath, PROJECT_DIR);
        assertEquals(4, imported.size());
        assertEquals("Question 1", ((EntryData.Prompt) imported.get(0)).getText());
        assertEquals("Answer 1", ((EntryData.Text) imported.get(1)).getRaw());
        assertEquals("Question 2", ((EntryData.Prompt) imported.get(2)).getText());
        assertEquals("Answer 2", ((EntryData.Text) imported.get(3)).getRaw());
    }

    // ── Helper methods ──────────────────────────────────────────────

    /**
     * Verifies that exported message data includes all fields required by OpenCode's Zod schema.
     * User messages need: role, time.created, agent, model.providerID, model.modelID.
     * Assistant messages need: role, time.created, time.completed, parentID, modelID, providerID,
     * mode, agent, path.cwd, path.root, cost, tokens.input, tokens.output, tokens.reasoning,
     * tokens.cache.read, tokens.cache.write.
     */
    @Test
    void exportIncludesRequiredZodFields() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("Hello"),
            textEntry("World")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement(
                 "SELECT data FROM message WHERE session_id = ? ORDER BY time_created")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                // User message
                assertTrue(rs.next(), "Should have user message row");
                JsonObject userData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                assertEquals("user", userData.get("role").getAsString());
                assertTrue(userData.has("time"), "user.time is required");
                assertTrue(userData.getAsJsonObject("time").has("created"), "user.time.created is required");
                assertTrue(userData.has("agent"), "user.agent is required by Zod");
                assertTrue(userData.has("model"), "user.model is required by Zod");
                assertTrue(userData.getAsJsonObject("model").has("providerID"),
                    "user.model.providerID is required");
                assertTrue(userData.getAsJsonObject("model").has("modelID"),
                    "user.model.modelID is required");

                // Assistant message
                assertTrue(rs.next(), "Should have assistant message row");
                JsonObject assistData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                assertEquals("assistant", assistData.get("role").getAsString());
                assertTrue(assistData.has("parentID"), "assistant.parentID is required by Zod");
                assertTrue(assistData.has("modelID"), "assistant.modelID is required by Zod");
                assertTrue(assistData.has("providerID"), "assistant.providerID is required by Zod");
                assertTrue(assistData.has("mode"), "assistant.mode is required by Zod");
                assertTrue(assistData.has("agent"), "assistant.agent is required by Zod");
                assertTrue(assistData.has("path"), "assistant.path is required by Zod");
                JsonObject path = assistData.getAsJsonObject("path");
                assertTrue(path.has("cwd"), "assistant.path.cwd is required");
                assertTrue(path.has("root"), "assistant.path.root is required");
                assertTrue(assistData.has("cost"), "assistant.cost is required by Zod");
                assertTrue(assistData.has("tokens"), "assistant.tokens is required by Zod");
                JsonObject tokens = assistData.getAsJsonObject("tokens");
                assertTrue(tokens.has("input"), "tokens.input is required");
                assertTrue(tokens.has("output"), "tokens.output is required");
                assertTrue(tokens.has("reasoning"), "tokens.reasoning is required");
                assertTrue(tokens.has("cache"), "tokens.cache is required");
            }
        }
    }

    /**
     * Verifies that exported tool parts in completed state include all Zod-required fields:
     * state.title, state.metadata, state.time.start, state.time.end, state.output, state.input.
     */
    @Test
    void exportToolPartIncludesRequiredZodFields() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("Q"),
            toolCallEntry("read_file", "{\"path\":\"test.txt\"}", "file content")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT p.data FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ? AND json_extract(p.data, '$.type') = 'tool'""")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Should have a tool part");
                JsonObject partData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                JsonObject state = partData.getAsJsonObject("state");
                assertNotNull(state, "tool part must have state");
                assertEquals("completed", state.get("status").getAsString());
                assertTrue(state.has("input"), "state.input is required by Zod");
                assertTrue(state.has("output"), "state.output is required by Zod");
                assertTrue(state.has("title"), "state.title is required by Zod (ToolStateCompleted)");
                assertTrue(state.has("metadata"), "state.metadata is required by Zod (ToolStateCompleted)");
                assertTrue(state.has("time"), "state.time is required by Zod");
                JsonObject time = state.getAsJsonObject("time");
                assertTrue(time.has("start"), "state.time.start is required");
                assertTrue(time.has("end"), "state.time.end is required");
            }
        }
    }

    /**
     * Verifies that tool parts with empty args still have state.input after serialization.
     * <p>
     * Root cause: {@code JsonParser.parseString("")} returns {@code JsonNull},
     * and GSON's default serializer drops null members — leaving {@code state.input} absent.
     */
    @Test
    void exportToolPartWithEmptyArgsHasStateInput() throws SQLException {
        List<EntryData> entries = List.of(
            promptEntry("Q"),
            toolCallEntry("read_file", "", "result")
        );

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        try (Connection conn = connect(dbPath);
             var ps = conn.prepareStatement("""
                 SELECT p.data FROM part p
                 JOIN message m ON p.message_id = m.id
                 WHERE m.session_id = ? AND json_extract(p.data, '$.type') = 'tool'""")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Should have a tool part");
                JsonObject partData = GSON.fromJson(rs.getString("data"), JsonObject.class);
                JsonObject state = partData.getAsJsonObject("state");
                assertTrue(state.has("input"),
                    "state.input must be present even with empty args — GSON drops JsonNull members");
                assertTrue(state.get("input").isJsonObject(),
                    "state.input must be a JSON object (Zod z.record requirement)");
            }
        }
    }

    // ── EntryData factory helpers ────────────────────────────────────

    private static EntryData.Prompt promptEntry(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData.Text textEntry(String text) {
        return new EntryData.Text(text);
    }

    private static EntryData.ToolCall toolCallEntry(String toolName, String args, String result) {
        EntryData.ToolCall tc = new EntryData.ToolCall(toolName, args);
        tc.setResult(result);
        tc.setStatus("completed");
        return tc;
    }

    // ── DB helpers ──────────────────────────────────────────────────

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
