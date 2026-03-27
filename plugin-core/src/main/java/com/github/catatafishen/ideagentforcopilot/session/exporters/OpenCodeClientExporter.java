package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.importers.JsonlUtil;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Exports v2 {@link SessionMessage} list into OpenCode's native SQLite format.
 *
 * <p>OpenCode stores sessions in {@code opencode.db} with tables managed by drizzle
 * migrations: {@code project}, {@code session}, {@code message}, and {@code part}.
 * This exporter creates records matching the real schema so that OpenCode can
 * resume the conversation via {@code resumeSessionId} in ACP {@code session/new}.</p>
 *
 * <p><b>Key schema details (OpenCode 1.2.x):</b></p>
 * <ul>
 *   <li>{@code session} requires {@code project_id} (FK→project), {@code slug}, {@code version}</li>
 *   <li>{@code message} requires {@code time_updated}</li>
 *   <li>{@code part} requires {@code session_id} and {@code time_updated}</li>
 *   <li>IDs use prefixed format: {@code ses_}, {@code msg_}, {@code prt_}</li>
 *   <li>{@code project.id} = SHA-1 hex of the worktree path</li>
 * </ul>
 */
public final class OpenCodeClientExporter {

    private static final Logger LOG = Logger.getInstance(OpenCodeClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String OPENCODE_VERSION = "1.2.0";
    private static final String IMPORTED_TITLE = "IDE Agent Session";

    private OpenCodeClientExporter() {
    }

    @NotNull
    public static Path defaultDbPath() {
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isEmpty()) {
            return Path.of(xdgData, "opencode", "opencode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    /**
     * Exports v2 session messages into the OpenCode SQLite database.
     *
     * @param messages   the v2 session messages to export
     * @param dbPath     path to the OpenCode database file
     * @param projectDir the project directory (used as the session's {@code directory} field)
     * @return the new session ID (e.g. {@code ses_XXXX}), or {@code null} if export failed
     */
    @Nullable
    public static String exportSession(
        @NotNull List<SessionMessage> messages,
        @NotNull Path dbPath,
        @NotNull String projectDir) {

        if (messages.isEmpty()) return null;

        if (!Files.exists(dbPath)) {
            LOG.info("OpenCode database not found at " + dbPath + " — creating it");
            try {
                Path parent = dbPath.getParent();
                if (parent != null) Files.createDirectories(parent);
            } catch (Exception e) {
                LOG.warn("Failed to create OpenCode database directory: " + dbPath.getParent(), e);
                return null;
            }
        }

        String sessionId = generateId("ses");
        String url = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement pragmaStmt = conn.createStatement()) {
                pragmaStmt.execute("PRAGMA journal_mode=WAL");
            }

            ensureTables(conn);

            long now = System.currentTimeMillis();
            String projectId = findOrCreateProject(conn, projectDir, now);

            insertSession(conn, sessionId, projectId, projectDir, now);

            for (SessionMessage msg : messages) {
                if ("separator".equals(msg.role)) continue;
                insertMessage(conn, sessionId, msg, now);
            }

            LOG.info("Exported v2 session to OpenCode: " + sessionId
                + " (project=" + projectId + ", messages=" + messages.size() + ")");
            return sessionId;
        } catch (SQLException e) {
            LOG.warn("Failed to export v2 session to OpenCode database: " + dbPath, e);
            return null;
        }
    }

    // ── ID generation ─────────────────────────────────────────────────────────

    /**
     * Generates an OpenCode-style prefixed ID (e.g. {@code ses_abc123...}).
     * Uses a UUID as the random component, encoded as base-36 alphanumeric.
     */
    @NotNull
    private static String generateId(@NotNull String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "_" + uuid;
    }

    // ── Project handling ──────────────────────────────────────────────────────

    /**
     * Finds the existing OpenCode project for this worktree, or creates one.
     * OpenCode uses SHA-1 hex of the worktree path as the project ID.
     */
    @NotNull
    private static String findOrCreateProject(
        @NotNull Connection conn,
        @NotNull String worktree,
        long now) throws SQLException {

        String projectId = sha1Hex(worktree);

        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM project WHERE id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return projectId;
            }
        }

        // Create the project record
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO project (id, worktree, vcs, time_created, time_updated, sandboxes) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, projectId);
            ps.setString(2, worktree);
            ps.setString(3, "git");
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setString(6, "[]");
            ps.executeUpdate();
        }
        LOG.info("Created OpenCode project record: " + projectId + " for " + worktree);
        return projectId;
    }

    @NotNull
    private static String sha1Hex(@NotNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is always available in Java
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    // ── Table creation (for fresh databases only) ─────────────────────────────

    /**
     * Creates the required tables if the database is empty (fresh install).
     * Schema matches OpenCode 1.2.x drizzle migrations.
     */
    private static void ensureTables(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
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

    // ── Session insertion ─────────────────────────────────────────────────────

    /**
     * Generates a random two-word slug (adjective-noun) for the session.
     */
    @NotNull
    private static String generateSlug() {
        String[] adjectives = {"calm", "bold", "kind", "warm", "cool", "fair", "wise", "keen"};
        String[] nouns = {"river", "cliff", "grove", "meadow", "ridge", "trail", "haven", "crest"};
        int a = (int) (Math.random() * adjectives.length);
        int n = (int) (Math.random() * nouns.length);
        return adjectives[a] + "-" + nouns[n];
    }

    private static void insertSession(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String projectId,
        @NotNull String projectDir,
        long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO session (id, project_id, slug, directory, title, version, "
                + "time_created, time_updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, projectId);
            ps.setString(3, generateSlug());
            ps.setString(4, projectDir);
            ps.setString(5, IMPORTED_TITLE);
            ps.setString(6, OPENCODE_VERSION);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    // ── Message insertion ─────────────────────────────────────────────────────

    private static void insertMessage(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull SessionMessage msg,
        long baseTime) throws SQLException {

        String messageId = generateId("msg");
        long timeCreated = msg.createdAt > 0 ? msg.createdAt : baseTime;

        JsonObject msgData = buildMessageData(msg, timeCreated);

        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO message (id, session_id, time_created, time_updated, data) "
                + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, messageId);
            ps.setString(2, sessionId);
            ps.setLong(3, timeCreated);
            ps.setLong(4, timeCreated);
            ps.setString(5, GSON.toJson(msgData));
            ps.executeUpdate();
        }

        for (JsonObject part : msg.parts) {
            insertPart(conn, messageId, sessionId, part, timeCreated);
        }
    }

    /**
     * Builds the message-level {@code data} JSON matching OpenCode's schema.
     */
    @NotNull
    private static JsonObject buildMessageData(@NotNull SessionMessage msg, long timeCreated) {
        JsonObject data = new JsonObject();
        data.addProperty("role", msg.role);

        JsonObject time = new JsonObject();
        time.addProperty("created", timeCreated);
        if ("assistant".equals(msg.role)) {
            time.addProperty("completed", timeCreated);
        }
        data.add("time", time);

        if (msg.model != null && !msg.model.isEmpty()) {
            if ("assistant".equals(msg.role)) {
                data.addProperty("modelID", msg.model);
                data.addProperty("providerID", "imported");
            } else {
                JsonObject model = new JsonObject();
                model.addProperty("modelID", msg.model);
                model.addProperty("providerID", "imported");
                data.add("model", model);
            }
        }

        return data;
    }

    // ── Part insertion ────────────────────────────────────────────────────────

    private static void insertPart(
        @NotNull Connection conn,
        @NotNull String messageId,
        @NotNull String sessionId,
        @NotNull JsonObject v2Part,
        long timeCreated) throws SQLException {

        String partId = generateId("prt");
        JsonObject partData = convertV2PartToOpenCodePart(v2Part);

        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO part (id, message_id, session_id, time_created, time_updated, data) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, partId);
            ps.setString(2, messageId);
            ps.setString(3, sessionId);
            ps.setLong(4, timeCreated);
            ps.setLong(5, timeCreated);
            ps.setString(6, GSON.toJson(partData));
            ps.executeUpdate();
        }
    }

    /**
     * Converts a v2 part to OpenCode's native part format.
     *
     * <p>V2 tool invocations ({@code tool-invocation}) are converted to OpenCode's
     * {@code tool} type with the structure: {@code {"type":"tool","callID":"...","tool":"...",
     * "state":{"status":"completed","input":{...},"output":"..."}}}.</p>
     */
    @NotNull
    private static JsonObject convertV2PartToOpenCodePart(@NotNull JsonObject v2Part) {
        String type = JsonlUtil.getStr(v2Part, "type");
        if (type == null) return v2Part.deepCopy();

        JsonObject result = new JsonObject();
        switch (type) {
            case "text", "reasoning" -> {
                result.addProperty("type", type);
                String text = JsonlUtil.getStr(v2Part, "text");
                result.addProperty("text", text != null ? text : "");
            }
            case "tool-invocation" -> {
                JsonObject invocation = v2Part.getAsJsonObject("toolInvocation");
                if (invocation == null) return v2Part.deepCopy();

                String toolCallId = JsonlUtil.getStr(invocation, "toolCallId");
                String toolName = JsonlUtil.getStr(invocation, "toolName");
                String state = JsonlUtil.getStr(invocation, "state");
                String argsStr = JsonlUtil.getStr(invocation, "args");
                String resultStr = JsonlUtil.getStr(invocation, "result");

                result.addProperty("type", "tool");
                result.addProperty("callID", toolCallId != null ? toolCallId : "");
                result.addProperty("tool", toolName != null ? toolName : "unknown");

                JsonObject stateObj = new JsonObject();
                stateObj.addProperty("status",
                    "result".equals(state) ? "completed" : "running");

                if (argsStr != null) {
                    try {
                        stateObj.add("input", com.google.gson.JsonParser.parseString(argsStr));
                    } catch (Exception e) {
                        JsonObject inputObj = new JsonObject();
                        inputObj.addProperty("_raw", argsStr);
                        stateObj.add("input", inputObj);
                    }
                }
                if (resultStr != null) {
                    stateObj.addProperty("output", resultStr);
                }

                result.add("state", stateObj);
            }
            default -> {
                return v2Part.deepCopy();
            }
        }
        return result;
    }
}
