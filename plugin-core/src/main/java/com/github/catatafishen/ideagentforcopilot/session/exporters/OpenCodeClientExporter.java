package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.importers.JsonlUtil;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
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
     * Opens a SQLite connection, ensuring the JDBC driver is loaded first.
     * IntelliJ plugins use a custom classloader that {@link java.sql.DriverManager}
     * does not search, so we must explicitly load the driver class.
     */
    @NotNull
    public static Connection openSqlite(@NotNull Path dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on classpath", e);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }
        return conn;
    }

    /**
     * Exports a list of v2 session messages to OpenCode's native SQLite database.
     *
     * <p>Messages are linearized before export: consecutive assistant messages
     * (e.g. main response + sub-agent result + continuation) are merged into a
     * single assistant message to maintain the linear user→assistant→user→assistant
     * chain that OpenCode expects. Each assistant message gets a unique
     * {@code parentID} pointing to the preceding user message.</p>
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
        List<SessionMessage> linearized = linearizeMessages(messages);

        try (Connection conn = openSqlite(dbPath)) {
            ensureTables(conn);

            long now = System.currentTimeMillis();
            String projectId = findOrCreateProject(conn, projectDir, now);

            insertSession(conn, sessionId, projectId, projectDir, now);

            // Track the last user message ID so assistant messages can reference it
            // via the required parentID field in OpenCode's Zod schema.
            // Use real msg.createdAt when available, but enforce strict monotonicity
            // (each timestamp > previous) so OpenCode's ORDER BY time_created is
            // deterministic even when upstream converters stamped all messages at once.
            String lastUserMessageId = null;
            long prevTime = 0;
            for (SessionMessage msg : linearized) {
                if ("separator".equals(msg.role)) continue;
                long msgTime = msg.createdAt > prevTime ? msg.createdAt : prevTime + 1;
                prevTime = msgTime;
                String messageId = insertMessage(conn, sessionId, msg, msgTime, projectDir, lastUserMessageId);
                if ("user".equals(msg.role)) {
                    lastUserMessageId = messageId;
                }
            }

            LOG.info("Exported v2 session to OpenCode: " + sessionId
                + " (project=" + projectId + ", messages=" + linearized.size()
                + ", original=" + messages.size() + ")");
            return sessionId;
        } catch (SQLException e) {
            LOG.warn("Failed to export v2 session to OpenCode database: " + dbPath, e);
            return null;
        }
    }

    // ── ID generation ─────────────────────────────────────────────────────────

    /**
     * Merges consecutive assistant messages into single messages to produce a
     * linear user→assistant→user→assistant chain.
     *
     * <p>OpenCode expects each user message to have exactly one assistant response.
     * Our v2 format may have multiple consecutive assistant messages when a turn
     * involved sub-agents or multi-step responses. This method merges all parts
     * from consecutive assistant messages into the first one, preserving the
     * agent name and model from the first assistant in each group.</p>
     *
     * <p>Separator messages are preserved as-is (they are skipped during export).</p>
     */
    @NotNull
    public static List<SessionMessage> linearizeMessages(@NotNull List<SessionMessage> messages) {
        List<SessionMessage> result = new ArrayList<>();
        SessionMessage pendingAssistant = null;
        List<JsonObject> mergedParts = null;

        for (SessionMessage msg : messages) {
            if ("separator".equals(msg.role)) {
                // Flush any pending assistant before a separator
                if (pendingAssistant != null) {
                    result.add(buildMergedAssistant(pendingAssistant, mergedParts));
                    pendingAssistant = null;
                    mergedParts = null;
                }
                result.add(msg);
                continue;
            }

            if ("assistant".equals(msg.role)) {
                if (pendingAssistant == null) {
                    // First assistant in a potential sequence
                    pendingAssistant = msg;
                    mergedParts = new ArrayList<>(msg.parts);
                } else {
                    // Consecutive assistant — merge parts into the pending one
                    mergedParts.addAll(msg.parts);
                }
            } else {
                // User message — flush any pending assistant first
                if (pendingAssistant != null) {
                    result.add(buildMergedAssistant(pendingAssistant, mergedParts));
                    pendingAssistant = null;
                    mergedParts = null;
                }
                result.add(msg);
            }
        }

        // Flush trailing assistant
        if (pendingAssistant != null) {
            result.add(buildMergedAssistant(pendingAssistant, mergedParts));
        }

        return result;
    }

    /**
     * Builds a merged assistant message from the first assistant's metadata
     * and the combined parts from all consecutive assistants.
     */
    @NotNull
    private static SessionMessage buildMergedAssistant(
        @NotNull SessionMessage first, @NotNull List<JsonObject> allParts) {
        if (allParts.size() == first.parts.size()) {
            return first; // no merge needed — return original
        }
        return new SessionMessage(
            first.id, first.role, allParts, first.createdAt, first.agent, first.model);
    }

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

    /**
     * Inserts a message and its parts into the database.
     *
     * @param timeCreated the timestamp for this message (caller provides monotonically
     *                    increasing values to ensure deterministic ordering)
     * @return the generated message ID (for parentID linking)
     */
    @NotNull
    private static String insertMessage(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull SessionMessage msg,
        long timeCreated,
        @NotNull String projectDir,
        @Nullable String parentMessageId) throws SQLException {

        String messageId = generateId("msg");

        JsonObject msgData = buildMessageData(msg, timeCreated, projectDir, parentMessageId);

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

        return messageId;
    }

    /**
     * Builds the message-level {@code data} JSON matching OpenCode's Zod schema.
     *
     * <p><b>Required fields (non-optional in Zod):</b></p>
     * <ul>
     *   <li>User: {@code role, time.created, agent, model.providerID, model.modelID}</li>
     *   <li>Assistant: {@code role, time.created, parentID, modelID, providerID, mode, agent,
     *       path.cwd, path.root, cost, tokens.input, tokens.output, tokens.reasoning,
     *       tokens.cache.read, tokens.cache.write}</li>
     * </ul>
     */
    @NotNull
    private static JsonObject buildMessageData(
        @NotNull SessionMessage msg,
        long timeCreated,
        @NotNull String projectDir,
        @Nullable String parentMessageId) {

        JsonObject data = new JsonObject();
        data.addProperty("role", msg.role);

        String modelId = (msg.model != null && !msg.model.isEmpty()) ? msg.model : "imported";
        String providerId = "imported";
        String agentName = (msg.agent != null && !msg.agent.isEmpty()) ? msg.agent : "build";

        if ("user".equals(msg.role)) {
            JsonObject time = new JsonObject();
            time.addProperty("created", timeCreated);
            data.add("time", time);

            data.addProperty("agent", agentName);

            JsonObject model = new JsonObject();
            model.addProperty("providerID", providerId);
            model.addProperty("modelID", modelId);
            data.add("model", model);
        } else {
            // assistant
            JsonObject time = new JsonObject();
            time.addProperty("created", timeCreated);
            time.addProperty("completed", timeCreated);
            data.add("time", time);

            data.addProperty("parentID", parentMessageId != null ? parentMessageId : "");
            data.addProperty("modelID", modelId);
            data.addProperty("providerID", providerId);
            data.addProperty("mode", "build");
            data.addProperty("agent", agentName);

            JsonObject path = new JsonObject();
            path.addProperty("cwd", projectDir);
            path.addProperty("root", projectDir);
            data.add("path", path);

            data.addProperty("cost", 0);

            JsonObject cache = new JsonObject();
            cache.addProperty("read", 0);
            cache.addProperty("write", 0);
            JsonObject tokens = new JsonObject();
            tokens.addProperty("input", 0);
            tokens.addProperty("output", 0);
            tokens.addProperty("reasoning", 0);
            tokens.add("cache", cache);
            data.add("tokens", tokens);
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

        JsonObject partData = convertV2PartToOpenCodePart(v2Part, timeCreated);
        if (partData == null) return;

        String partId = generateId("prt");
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

    private static JsonObject convertV2PartToOpenCodePart(@NotNull JsonObject v2Part, long timeMs) {
        String type = JsonlUtil.getStr(v2Part, "type");
        if (type == null) return null;

        JsonObject result = new JsonObject();
        switch (type) {
            case "text" -> {
                result.addProperty("type", "text");
                String text = JsonlUtil.getStr(v2Part, "text");
                result.addProperty("text", text != null ? text : "");
            }
            case "reasoning" -> {
                result.addProperty("type", "reasoning");
                String text = JsonlUtil.getStr(v2Part, "text");
                result.addProperty("text", text != null ? text : "");
                JsonObject time = new JsonObject();
                time.addProperty("start", timeMs);
                time.addProperty("end", timeMs);
                result.add("time", time);
            }
            case "tool-invocation" -> {
                JsonObject invocation = v2Part.getAsJsonObject("toolInvocation");
                if (invocation == null) return null;

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

                // OpenCode Zod schema requires input: z.record(z.string(), z.any()) — must be a JSON object.
                // JsonParser.parseString("") returns JsonNull which GSON drops during serialization,
                // so we must explicitly handle empty/blank args and non-object parse results.
                if (argsStr != null && !argsStr.isBlank()) {
                    try {
                        JsonElement parsed = com.google.gson.JsonParser.parseString(argsStr);
                        if (parsed.isJsonObject()) {
                            stateObj.add("input", parsed);
                        } else {
                            stateObj.add("input", wrapRawInput(argsStr));
                        }
                    } catch (Exception e) {
                        stateObj.add("input", wrapRawInput(argsStr));
                    }
                } else {
                    stateObj.add("input", new JsonObject());
                }

                if ("result".equals(state)) {
                    // ToolStateCompleted requires: output, title, metadata, time
                    stateObj.addProperty("output", resultStr != null ? resultStr : "");
                    stateObj.addProperty("title", "");
                    stateObj.add("metadata", new JsonObject());
                } else {
                    // ToolStateRunning requires: input, time.start
                    if (resultStr != null) {
                        stateObj.addProperty("output", resultStr);
                    }
                }

                JsonObject time = new JsonObject();
                time.addProperty("start", timeMs);
                if ("result".equals(state)) {
                    time.addProperty("end", timeMs);
                }
                stateObj.add("time", time);

                result.add("state", stateObj);
            }
            case "subagent" -> {
                // OpenCode has no subagent concept — convert to a text summary so context
                // is preserved without writing an unknown discriminant value that fails Zod validation.
                String description = JsonlUtil.getStr(v2Part, "description");
                String agentType = JsonlUtil.getStr(v2Part, "agentType");
                String subResult = JsonlUtil.getStr(v2Part, "result");
                StringBuilder sb = new StringBuilder("[Subagent");
                if (agentType != null) sb.append(" (").append(agentType).append(")");
                if (description != null) sb.append(": ").append(description);
                sb.append("]");
                if (subResult != null && !subResult.isBlank()) {
                    sb.append("\n").append(subResult);
                }
                result.addProperty("type", "text");
                result.addProperty("text", sb.toString());
            }
            default -> {
                // Unknown v2 part type (e.g. "status", "file"). Writing it as-is would cause
                // Zod schema validation failure in OpenCode because its discriminated union on
                // "type" doesn't include these values. Skip the part entirely.
                return null;
            }
        }
        return result;
    }

    /**
     * Wraps a non-object args string into a JSON object with a {@code _raw} key,
     * ensuring the Zod {@code z.record(z.string(), z.any())} requirement is met.
     */
    @NotNull
    private static JsonObject wrapRawInput(@NotNull String argsStr) {
        JsonObject inputObj = new JsonObject();
        inputObj.addProperty("_raw", argsStr);
        return inputObj;
    }
}
