package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
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
 * Exports {@link EntryData} list into OpenCode's native SQLite format.
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
    private static final String IMPORTED_TITLE = "AgentBridge Session";
    private static final String FIELD_INPUT = "input";

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
     * Exports a list of {@link EntryData} entries to OpenCode's native SQLite database.
     *
     * <p>Entries are iterated directly: consecutive assistant-role entries
     * (Text, Thinking, ToolCall, SubAgent) share a single message row to maintain
     * the strict user→assistant→user→assistant chain that OpenCode expects.</p>
     *
     * @param entries    the UI entries to export
     * @param dbPath     path to the OpenCode database file
     * @param projectDir the project directory (used as the session's {@code directory} field)
     * @return the new session ID (e.g. {@code ses_XXXX}), or {@code null} if export failed
     */
    @Nullable
    public static String exportSession(
        @NotNull List<EntryData> entries,
        @NotNull Path dbPath,
        @NotNull String projectDir) {
        return exportSession(entries, dbPath, projectDir, 0);
    }

    public static String exportSession(
        @NotNull List<EntryData> entries,
        @NotNull Path dbPath,
        @NotNull String projectDir,
        int maxTotalChars) {

        entries = trimEntriesToBudget(entries, maxTotalChars);

        if (entries.isEmpty()) return null;

        if (!hasExportableEntries(entries)) return null;

        ensureDatabaseDirectory(dbPath);

        String sessionId = generateId("ses");

        try (Connection conn = openSqlite(dbPath)) {
            ensureTables(conn);

            long now = System.currentTimeMillis();
            String projectId = findOrCreateProject(conn, projectDir, now);
            insertSession(conn, sessionId, projectId, projectDir, now);

            int messageCount = processEntries(conn, sessionId, entries, now, projectDir);

            LOG.info("Exported session to OpenCode: " + sessionId
                + " (project=" + projectId + ", messages=" + messageCount + ")");
            return sessionId;
        } catch (SQLException e) {
            LOG.warn("Failed to export session to OpenCode database: " + dbPath, e);
            return null;
        }
    }

    private static boolean hasExportableEntries(@NotNull List<EntryData> entries) {
        for (EntryData e : entries) {
            if (e instanceof EntryData.Prompt || e instanceof EntryData.Text
                || e instanceof EntryData.Thinking || e instanceof EntryData.ToolCall
                || e instanceof EntryData.SubAgent) {
                return true;
            }
        }
        return false;
    }

    private static void ensureDatabaseDirectory(@NotNull Path dbPath) {
        if (!Files.exists(dbPath)) {
            LOG.info("OpenCode database not found at " + dbPath + " — creating it");
            try {
                Path parent = dbPath.getParent();
                if (parent != null) Files.createDirectories(parent);
            } catch (Exception e) {
                LOG.warn("Failed to create OpenCode database directory: " + dbPath.getParent(), e);
            }
        }
    }

    private static int processEntries(
        Connection conn, String sessionId,
        @NotNull List<EntryData> entries, long startTime,
        @NotNull String projectDir) throws SQLException {

        String lastUserMessageId = null;
        long prevTime = startTime;
        int messageCount = 0;

        List<JsonObject> pendingParts = null;
        String pendingAgent = null;
        String pendingModel = null;

        for (EntryData entry : entries) {
            switch (entry) {
                case EntryData.Prompt prompt -> {
                    // Flush any pending assistant message before a new user message
                    if (pendingParts != null && !pendingParts.isEmpty()) {
                        prevTime++;
                        flushMessage(conn, sessionId, "assistant",
                            pendingAgent, pendingModel, pendingParts, prevTime,
                            projectDir, lastUserMessageId);
                        pendingParts = null;
                        pendingAgent = null;
                        pendingModel = null;
                        messageCount++;
                    }

                    // Insert user message
                    prevTime++;
                    JsonObject userPart = new JsonObject();
                    userPart.addProperty("type", "text");
                    userPart.addProperty("text", prompt.getText());

                    lastUserMessageId = flushMessage(conn, sessionId, "user",
                        "", "", List.of(userPart), prevTime, projectDir, null);
                    messageCount++;
                }
                case EntryData.Text text -> {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "text");
                    part.addProperty("text", text.getRaw());
                    if (pendingParts == null) {
                        pendingParts = new ArrayList<>();
                        pendingAgent = text.getAgent();
                        pendingModel = text.getModel();
                    }
                    pendingParts.add(part);
                }
                case EntryData.Thinking thinking -> {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "reasoning");
                    part.addProperty("text", thinking.getRaw());
                    JsonObject time = new JsonObject();
                    time.addProperty("start", prevTime);
                    time.addProperty("end", prevTime);
                    part.add("time", time);
                    if (pendingParts == null) {
                        pendingParts = new ArrayList<>();
                        pendingAgent = thinking.getAgent();
                        pendingModel = thinking.getModel();
                    }
                    pendingParts.add(part);
                }
                case EntryData.ToolCall toolCall -> {
                    JsonObject part = buildToolInvocationPart(toolCall, prevTime);
                    if (pendingParts == null) {
                        pendingParts = new ArrayList<>();
                        pendingAgent = toolCall.getAgent();
                        pendingModel = toolCall.getModel();
                    }
                    pendingParts.add(part);
                }
                case EntryData.SubAgent subAgent -> {
                    JsonObject part = buildSubAgentPart(subAgent);
                    if (pendingParts == null) {
                        pendingParts = new ArrayList<>();
                        pendingAgent = subAgent.getAgent();
                        pendingModel = subAgent.getModel();
                    }
                    pendingParts.add(part);
                }
                default -> {
                    // Skip: Status, TurnStats, ContextFiles, SessionSeparator
                }
            }
        }

        // Flush trailing assistant message
        if (pendingParts != null && !pendingParts.isEmpty()) {
            prevTime++;
            flushMessage(conn, sessionId, "assistant",
                pendingAgent, pendingModel, pendingParts, prevTime,
                projectDir, lastUserMessageId);
            messageCount++;
        }

        return messageCount;
    }

    private static JsonObject buildSubAgentPart(@NotNull EntryData.SubAgent subAgent) {
        StringBuilder sb = new StringBuilder("[Subagent");
        sb.append(" (").append(subAgent.getAgentType()).append(")");
        String desc = subAgent.getDescription();
        if (desc != null && !desc.isEmpty()) sb.append(": ").append(desc);
        sb.append("]");
        String subResult = subAgent.getResult();
        if (subResult != null && !subResult.isBlank()) {
            sb.append("\n").append(subResult);
        }
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", sb.toString());
        return part;
    }

    // ── ID generation ─────────────────────────────────────────────────────────

    private static @NotNull List<EntryData> trimEntriesToBudget(@NotNull List<EntryData> entries, int maxTotalChars) {
        if (maxTotalChars <= 0) return entries;

        int total = countTotalChars(entries);
        if (total <= maxTotalChars) return entries;

        List<EntryData> result = new ArrayList<>(entries);
        while (total > maxTotalChars) {
            int secondPromptIdx = findSecondPromptIndex(result);
            if (secondPromptIdx != -1) {
                int charsDropped = countTotalChars(result.subList(0, secondPromptIdx));
                result.subList(0, secondPromptIdx).clear();
                total -= charsDropped;
            } else {
                int freed = dropOldestNonPrompt(result);
                if (freed < 0) break; // nothing to drop
                total -= freed;
            }
        }
        return result;
    }

    private static int countEntryChars(@NotNull EntryData e) {
        return switch (e) {
            case EntryData.Prompt p -> p.getText().length();
            case EntryData.Text t -> t.getRaw().length();
            case EntryData.ToolCall tc -> {
                int n = 0;
                if (tc.getArguments() != null) n += tc.getArguments().length();
                if (tc.getResult() != null) n += tc.getResult().length();
                yield n;
            }
            default -> 0;
        };
    }

    private static int countTotalChars(@NotNull List<EntryData> entries) {
        int total = 0;
        for (EntryData e : entries) total += countEntryChars(e);
        return total;
    }

    private static int findSecondPromptIndex(@NotNull List<EntryData> entries) {
        int promptsSeen = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof EntryData.Prompt && ++promptsSeen == 2) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Drops the oldest non-Prompt entry after the initial Prompt.
     * Returns the number of characters freed, or Integer.MIN_VALUE if nothing could be dropped.
     */
    private static int dropOldestNonPrompt(@NotNull List<EntryData> result) {
        for (int i = 1; i < result.size(); i++) {
            if (!(result.get(i) instanceof EntryData.Prompt)) {
                EntryData dropped = result.remove(i);
                return countEntryChars(dropped);
            }
        }
        return -1;
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
     * Inserts a message and its pre-built parts into the database.
     *
     * @return the generated message ID (for parentID linking)
     */
    @NotNull
    private static String flushMessage(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String role,
        @Nullable String agent,
        @Nullable String model,
        @NotNull List<JsonObject> parts,
        long timeCreated,
        @NotNull String projectDir,
        @Nullable String parentMessageId) throws SQLException {

        String messageId = generateId("msg");

        JsonObject msgData = buildMessageData(role, agent, model, timeCreated, projectDir, parentMessageId);

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

        for (JsonObject part : parts) {
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
        @NotNull String role,
        @Nullable String agent,
        @Nullable String model,
        long timeCreated,
        @NotNull String projectDir,
        @Nullable String parentMessageId) {

        JsonObject data = new JsonObject();
        data.addProperty("role", role);

        String modelId = (model != null && !model.isEmpty()) ? model : "imported";
        String providerId = "imported";
        String agentName = (agent != null && !agent.isEmpty()) ? agent : "build";

        if ("user".equals(role)) {
            JsonObject time = new JsonObject();
            time.addProperty("created", timeCreated);
            data.add("time", time);

            data.addProperty("agent", agentName);

            JsonObject modelObj = new JsonObject();
            modelObj.addProperty("providerID", providerId);
            modelObj.addProperty("modelID", modelId);
            data.add("model", modelObj);
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
            tokens.addProperty(FIELD_INPUT, 0);
            tokens.addProperty("output", 0);
            tokens.addProperty("reasoning", 0);
            tokens.add("cache", cache);
            data.add("tokens", tokens);
        }

        return data;
    }

    // ── Part insertion ────────────────────────────────────────────────────────

    /**
     * Inserts a single pre-built part JSON into the {@code part} table.
     */
    private static void insertPart(
        @NotNull Connection conn,
        @NotNull String messageId,
        @NotNull String sessionId,
        @NotNull JsonObject partData,
        long timeCreated) throws SQLException {

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

    /**
     * Builds an OpenCode "tool" part from an {@link EntryData.ToolCall}.
     * OpenCode Zod schema expects: type="tool", callID, tool, state{status, input, output, title, metadata, time}.
     */
    @NotNull
    private static JsonObject buildToolInvocationPart(@NotNull EntryData.ToolCall toolCall, long timeMs) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "tool");
        result.addProperty("callID", toolCall.getEntryId() != null ? toolCall.getEntryId() : "");
        result.addProperty("tool", toolCall.getTitle() != null ? toolCall.getTitle() : "unknown");

        boolean completed = toolCall.getResult() != null;

        JsonObject stateObj = new JsonObject();
        stateObj.addProperty("status", completed ? "completed" : "running");

        // OpenCode Zod schema requires input: z.record(z.string(), z.any()) — must be a JSON object.
        String argsStr = toolCall.getArguments();
        if (argsStr != null && !argsStr.isBlank()) {
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(argsStr);
                if (parsed.isJsonObject()) {
                    stateObj.add(FIELD_INPUT, parsed);
                } else {
                    JsonObject wrapper = new JsonObject();
                    wrapper.addProperty("raw", argsStr);
                    stateObj.add(FIELD_INPUT, wrapper);
                }
            } catch (Exception e) {
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("raw", argsStr);
                stateObj.add(FIELD_INPUT, wrapper);
            }
        } else {
            stateObj.add(FIELD_INPUT, new JsonObject());
        }

        if (completed) {
            stateObj.addProperty("output", toolCall.getResult());
            stateObj.addProperty("title", "");
            stateObj.add("metadata", new JsonObject());
        }

        JsonObject time = new JsonObject();
        time.addProperty("start", timeMs);
        if (completed) {
            time.addProperty("end", timeMs);
        }
        stateObj.add("time", time);

        result.add("state", stateObj);
        return result;
    }
}
