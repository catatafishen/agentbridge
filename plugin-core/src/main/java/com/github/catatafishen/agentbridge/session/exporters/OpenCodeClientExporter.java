package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports {@link EntryData} list into OpenCode's native SQLite format.
 *
 * <p>OpenCode stores sessions in {@code opencode.db} with drizzle-managed tables. This
 * exporter detects the installed schema generation and writes rows only in a shape that
 * generation actually reads, so {@code session/resume} can pick the conversation up:</p>
 *
 * <p><b>OpenCode 2.x ({@code session_v2} table present):</b></p>
 * <ul>
 *   <li>{@code session_v2} requires {@code project_id}, {@code slug}, {@code directory},
 *       {@code version}, {@code time_created}, {@code time_updated}</li>
 *   <li>Messages live in {@code session_message} ({@code type}/{@code seq} columns, parts
 *       embedded as a {@code content} array inside {@code data}); the legacy
 *       {@code message}/{@code part} tables are not read for new sessions</li>
 *   <li>{@code event_sequence} must carry the last used {@code seq} for the session</li>
 *   <li>Session lookup by id does not scope by project; the {@code directory} column must
 *       match the cwd the ACP client sends with the resume request</li>
 * </ul>
 *
 * <p><b>OpenCode 1.2.x (legacy {@code session} table present):</b></p>
 * <ul>
 *   <li>{@code session} requires {@code project_id} (FK→project), {@code slug}, {@code version}</li>
 *   <li>{@code message} requires {@code time_updated}</li>
 *   <li>{@code part} requires {@code session_id} and {@code time_updated}</li>
 *   <li>IDs use prefixed format: {@code ses_}, {@code msg_}, {@code prt_}</li>
 *   <li>{@code project.id} = SHA-1 hex of the worktree path</li>
 * </ul>
 *
 * <p>When neither schema is present the database has not been initialized by OpenCode yet;
 * the export is skipped rather than fabricating tables, because a v2 process migrating a
 * database later chokes on unexpected table shapes.</p>
 */
public final class OpenCodeClientExporter {

    private static final Logger LOG = Logger.getInstance(OpenCodeClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String OPENCODE_VERSION = "1.2.0";
    private static final String SELF_AGENT = AgentProfileManager.OPENCODE_PROFILE_ID;
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
        return Path.of(SystemProperties.getUserHome(), ".local", "share", "opencode", "opencode.db");
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
        entries = stripTrailingPendingPrompt(entries);

        if (entries.isEmpty()) return null;

        if (!hasExportableEntries(entries)) return null;

        ensureDatabaseDirectory(dbPath);

        String sessionId = generateId("ses");

        try (Connection conn = openSqlite(dbPath)) {
            if (tableExists(conn, "session_v2")) {
                return exportSessionV2(conn, sessionId, entries, projectDir);
            }
            if (!tableExists(conn, "session")) {
                LOG.warn("OpenCode database at " + dbPath + " has no initialized schema; skipping raw session export");
                return null;
            }

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
                        applyForeignAgentPrefix(pendingParts, pendingAgent);
                        flushMessage(conn, sessionId,
                            new MessageInsert("assistant", pendingAgent, pendingModel,
                                pendingParts, prevTime, projectDir, lastUserMessageId));
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

                    lastUserMessageId = flushMessage(conn, sessionId,
                        new MessageInsert("user", "", "", List.of(userPart),
                            prevTime, projectDir, null));
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
            applyForeignAgentPrefix(pendingParts, pendingAgent);
            flushMessage(conn, sessionId,
                new MessageInsert("assistant", pendingAgent, pendingModel,
                    pendingParts, prevTime, projectDir, lastUserMessageId));
            messageCount++;
        }

        return messageCount;
    }

    private static JsonObject buildSubAgentPart(@NotNull EntryData.SubAgent subAgent) {
        StringBuilder sb = new StringBuilder("[Subagent");
        sb.append(" (").append(subAgent.getAgentType()).append(")");
        String desc = subAgent.getDescription();
        if (!desc.isEmpty()) sb.append(": ").append(desc);
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
        return EntryBudgetTrimmer.trimEntriesToBudget(entries, maxTotalChars);
    }

    /**
     * Removes the trailing pending {@link EntryData.Prompt} from the entry list, if present.
     *
     * <p>When exporting conversation history to OpenCode, the most recent user prompt is always
     * the <em>current pending message</em> — it will be sent again via {@code session/prompt}.
     * Including it in the exported history causes OpenCode to see it twice (once in history,
     * once as the new {@code session/prompt} message), leading to confused responses where the
     * model tries to answer "both" the historical and the current copy.
     *
     * <p>Orphaned prompts that appear <em>earlier</em> in the list (unanswered turns from failed
     * sessions) are intentionally preserved — they provide useful recovery context.
     */
    @NotNull
    static List<EntryData> stripTrailingPendingPrompt(@NotNull List<EntryData> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            EntryData e = entries.get(i);
            if (e instanceof EntryData.Prompt) {
                List<EntryData> trimmed = new ArrayList<>(entries);
                trimmed.remove(i);
                return trimmed;
            }
            if (e instanceof EntryData.Text || e instanceof EntryData.Thinking
                || e instanceof EntryData.ToolCall || e instanceof EntryData.SubAgent) {
                // Last meaningful entry has an assistant response — nothing trailing to strip
                return entries;
            }
            // Non-content entries (Status, TurnStats, SessionSeparator, ContextFiles): keep scanning
        }
        return entries;
    }

    /**
     * Prefixes the first text part of an assistant message with a foreign-agent attribution note.
     *
     * <p>When a turn was answered by a different agent (e.g. "copilot"), OpenCode would otherwise
     * interpret that response as its own prior work. Adding a clear attribution prevents the model
     * from treating another agent's analysis as something it did itself.
     *
     * <p>No-ops if {@code agent} is null, empty, or matches {@value #SELF_AGENT} (case-insensitive).
     * The case-insensitive comparison is intentional: {@link com.github.catatafishen.agentbridge.bridge.EntryData.Text#getAgent()}
     * stores the profile's display name (e.g. {@code "OpenCode"}), while {@code SELF_AGENT} is the
     * profile ID ({@code "opencode"}).
     */
    static void applyForeignAgentPrefix(@NotNull List<JsonObject> parts, @Nullable String agent) {
        if (agent == null || agent.isEmpty() || SELF_AGENT.equalsIgnoreCase(agent)) return;
        String note = "[Note: this response was provided by agent '" + agent + "', not by you.]\n\n";
        for (JsonObject part : parts) {
            if ("text".equals(part.has("type") ? part.get("type").getAsString() : "")) {
                part.addProperty("text", note + part.get("text").getAsString());
                return;
            }
        }
        // No text part found — prepend a dedicated text note
        JsonObject notePart = new JsonObject();
        notePart.addProperty("type", "text");
        notePart.addProperty("text", note.strip());
        parts.add(0, notePart);
    }

    /**
     * Generates an OpenCode-style prefixed ID (e.g. {@code ses_abc123...}).
     * Uses a UUID as the random component, encoded as base-36 alphanumeric.
     */
    @NotNull
    private static String generateId(@NotNull String prefix) {
        return EntryBudgetTrimmer.generateId(prefix);
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
        return EntryBudgetTrimmer.sha1Hex(input);
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
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        int a = random.nextInt(adjectives.length);
        int n = random.nextInt(nouns.length);
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

    // ── OpenCode 2.x export (session_v2 schema) ──────────────────────────────

    /**
     * Returns {@code true} when the database contains the given table. Used to detect
     * which OpenCode schema generation owns the database before writing any rows.
     */
    static boolean tableExists(@NotNull Connection conn, @NotNull String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Writes the conversation against the OpenCode 2.x schema: one {@code session_v2} row,
     * {@code session_message} rows with parts embedded in a {@code content} array, and an
     * {@code event_sequence} watermark matching the last used {@code seq}. Project rows are
     * never created here; v2 resolves and persists projects itself on startup.
     */
    @Nullable
    private static String exportSessionV2(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull List<EntryData> entries,
        @NotNull String projectDir) throws SQLException {

        long now = System.currentTimeMillis();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            String projectId = resolveProjectIdV2(conn, projectDir);
            insertSessionV2(conn, sessionId, projectId, projectDir, latestSessionVersionV2(conn), now);

            int messageCount = processEntriesV2(conn, sessionId, entries, now);
            upsertEventSequence(conn, sessionId, Math.max(messageCount - 1, 0));

            conn.commit();
            LOG.info("Exported session to OpenCode 2.x storage: " + sessionId
                + " (project=" + projectId + ", messages=" + messageCount + ")");
            return sessionId;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Finds the project id v2 associates with this directory: the row whose worktree is the
     * enclosing git repository root, else the row for the directory itself, else the literal
     * {@code global} id v2 assigns to sessions with no resolvable project.
     */
    @NotNull
    static String resolveProjectIdV2(@NotNull Connection conn, @NotNull String projectDir) throws SQLException {
        Path gitRoot = findGitRoot(projectDir);
        if (gitRoot != null) {
            String id = findProjectByWorktree(conn, gitRoot.toString());
            if (id != null) return id;
        }
        String id = findProjectByWorktree(conn, Path.of(projectDir).toString());
        return id != null ? id : "global";
    }

    /**
     * Walks up from {@code projectDir} looking for a {@code .git} entry, mirroring how v2
     * resolves a directory to its enclosing repository. Returns {@code null} outside any repo.
     */
    @Nullable
    static Path findGitRoot(@NotNull String projectDir) {
        Path dir = Path.of(projectDir).toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    @Nullable
    private static String findProjectByWorktree(@NotNull Connection conn, @NotNull String worktree) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM project WHERE worktree = ?")) {
            ps.setString(1, worktree.replace('\\', '/'));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Copies the version string from the most recent v2 session so exported rows are
     * indistinguishable from native ones; v2 treats the value as informational only.
     */
    @NotNull
    private static String latestSessionVersionV2(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM session_v2 ORDER BY time_created DESC LIMIT 1")) {
            String version = rs.next() ? rs.getString(1) : null;
            return version != null ? version : "2.0.0";
        }
    }

    /**
     * Inserts the {@code session_v2} row. {@code directory} is normalized to forward slashes
     * because v2 compares it against its own normalized cwd form on every lookup, and
     * {@code path} is the session directory relative to the git root (empty at the root
     * itself), matching how v2 populates the column.
     */
    private static void insertSessionV2(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String projectId,
        @NotNull String projectDir,
        @NotNull String version,
        long now) throws SQLException {
        Path gitRoot = findGitRoot(projectDir);
        String relativePath = "";
        if (gitRoot != null) {
            String relative = gitRoot.toAbsolutePath().normalize()
                .relativize(Path.of(projectDir).toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (!relative.isEmpty()) relativePath = relative;
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO session_v2 (id, project_id, slug, directory, path, title, version, "
                + "time_created, time_updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, projectId);
            ps.setString(3, generateSlug());
            ps.setString(4, projectDir.replace('\\', '/'));
            ps.setString(5, relativePath);
            ps.setString(6, IMPORTED_TITLE);
            ps.setString(7, version);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
        }
    }

    /**
     * v2 message writer. Same user/assistant turn pairing as the legacy path, but each
     * message is a single {@code session_message} row whose {@code data} carries the role
     * payload with parts embedded in a {@code content} array. {@code seq} starts at 0 and
     * increments once per row, matching v2's own v1-to-v2 migration.
     */
    private static int processEntriesV2(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull List<EntryData> entries,
        long startTime) throws SQLException {

        long prevTime = startTime;
        int seq = 0;
        int messageCount = 0;

        List<JsonObject> pendingContent = null;
        String pendingAgent = null;
        String pendingModel = null;

        for (EntryData entry : entries) {
            switch (entry) {
                case EntryData.Prompt prompt -> {
                    if (pendingContent != null && !pendingContent.isEmpty()) {
                        prevTime++;
                        applyForeignAgentPrefix(pendingContent, pendingAgent);
                        insertSessionMessage(conn, sessionId, "assistant", seq++,
                            buildV2AssistantData(pendingAgent, pendingModel, pendingContent, prevTime), prevTime);
                        pendingContent = null;
                        pendingAgent = null;
                        pendingModel = null;
                        messageCount++;
                    }
                    prevTime++;
                    insertSessionMessage(conn, sessionId, "user", seq++,
                        buildV2UserData(prompt.getText(), prevTime), prevTime);
                    messageCount++;
                }
                case EntryData.Text text -> {
                    JsonObject content = new JsonObject();
                    content.addProperty("type", "text");
                    content.addProperty("text", text.getRaw());
                    if (pendingContent == null) {
                        pendingContent = new ArrayList<>();
                        pendingAgent = text.getAgent();
                        pendingModel = text.getModel();
                    }
                    pendingContent.add(content);
                }
                case EntryData.Thinking thinking -> {
                    if (pendingContent == null) {
                        pendingContent = new ArrayList<>();
                        pendingAgent = thinking.getAgent();
                        pendingModel = thinking.getModel();
                    }
                    pendingContent.add(buildV2ReasoningContent(thinking.getRaw(), prevTime));
                }
                case EntryData.ToolCall toolCall -> {
                    if (pendingContent == null) {
                        pendingContent = new ArrayList<>();
                        pendingAgent = toolCall.getAgent();
                        pendingModel = toolCall.getModel();
                    }
                    pendingContent.add(buildV2ToolContent(toolCall, prevTime));
                }
                case EntryData.SubAgent subAgent -> {
                    JsonObject content = buildSubAgentPart(subAgent);
                    if (pendingContent == null) {
                        pendingContent = new ArrayList<>();
                        pendingAgent = subAgent.getAgent();
                        pendingModel = subAgent.getModel();
                    }
                    pendingContent.add(content);
                }
                default -> {
                    // Skip: Status, TurnStats, ContextFiles, SessionSeparator
                }
            }
        }

        if (pendingContent != null && !pendingContent.isEmpty()) {
            prevTime++;
            applyForeignAgentPrefix(pendingContent, pendingAgent);
            insertSessionMessage(conn, sessionId, "assistant", seq++,
                buildV2AssistantData(pendingAgent, pendingModel, pendingContent, prevTime), prevTime);
            messageCount++;
        }

        return messageCount;
    }

    private static void insertSessionMessage(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String type,
        int seq,
        @NotNull JsonObject data,
        long time) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO session_message (id, session_id, type, seq, time_created, time_updated, data) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, generateId("msg"));
            ps.setString(2, sessionId);
            ps.setString(3, type);
            ps.setInt(4, seq);
            ps.setLong(5, time);
            ps.setLong(6, time);
            ps.setString(7, GSON.toJson(data));
            ps.executeUpdate();
        }
    }

    /**
     * Records the last used {@code seq} for the session. v2 reads this watermark to know
     * which events a session has already consumed, so it must not lag behind the rows we
     * just wrote.
     */
    private static void upsertEventSequence(@NotNull Connection conn, @NotNull String sessionId, int lastSeq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO event_sequence (aggregate_id, seq, owner_id) VALUES (?, ?, NULL) "
                + "ON CONFLICT(aggregate_id) DO UPDATE SET seq = excluded.seq, owner_id = NULL")) {
            ps.setString(1, sessionId);
            ps.setInt(2, lastSeq);
            ps.executeUpdate();
        }
    }

    /** v2 user payload: plain text plus empty attachment arrays, mirroring native rows. */
    @NotNull
    static JsonObject buildV2UserData(@NotNull String text, long time) {
        JsonObject data = new JsonObject();
        data.addProperty("text", text);
        data.add("files", new com.google.gson.JsonArray());
        data.add("agents", new com.google.gson.JsonArray());
        JsonObject timeObj = new JsonObject();
        timeObj.addProperty("created", time);
        data.add("time", timeObj);
        return data;
    }

    /** v2 assistant payload: model info and token counters at message level, parts inside {@code content}. */
    @NotNull
    static JsonObject buildV2AssistantData(@Nullable String agent, @Nullable String model, @NotNull List<JsonObject> content, long time) {
        String modelId = (model != null && !model.isEmpty()) ? model : "imported";
        String agentName = (agent != null && !agent.isEmpty()) ? agent : "build";

        JsonObject data = new JsonObject();
        data.addProperty("agent", agentName);
        JsonObject modelObj = new JsonObject();
        modelObj.addProperty("providerID", "imported");
        modelObj.addProperty("id", modelId);
        modelObj.addProperty("variant", "default");
        data.add("model", modelObj);
        com.google.gson.JsonArray contentArray = new com.google.gson.JsonArray();
        content.forEach(contentArray::add);
        data.add("content", contentArray);
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
        JsonObject timeObj = new JsonObject();
        timeObj.addProperty("created", time);
        timeObj.addProperty("completed", time);
        data.add("time", timeObj);
        return data;
    }

    /** v2 reasoning entry; timestamps use the {@code created}/{@code completed} pair. */
    @NotNull
    static JsonObject buildV2ReasoningContent(@NotNull String text, long time) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "reasoning");
        content.addProperty("text", text);
        JsonObject timeObj = new JsonObject();
        timeObj.addProperty("created", time);
        timeObj.addProperty("completed", time);
        content.add("time", timeObj);
        return content;
    }

    /**
     * v2 tool entry. A completed call carries its output as a text entry inside
     * {@code state.content}; an unfinished one is recorded as interrupted, the same shape
     * v2's own migration produces for calls it cannot replay.
     */
    @NotNull
    static JsonObject buildV2ToolContent(@NotNull EntryData.ToolCall toolCall, long time) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "tool");
        result.addProperty("id", toolCall.getEntryId());
        result.addProperty("name", ExportUtils.exportMcpToolName(toolCall, ExportUtils.OPENCODE_MCP_PREFIX));

        JsonObject state = new JsonObject();
        state.add(FIELD_INPUT, parseToolInput(toolCall.getArguments()));
        if (toolCall.getResult() != null) {
            state.addProperty("status", "completed");
            com.google.gson.JsonArray outputContent = new com.google.gson.JsonArray();
            JsonObject textEntry = new JsonObject();
            textEntry.addProperty("type", "text");
            textEntry.addProperty("text", toolCall.getResult());
            outputContent.add(textEntry);
            state.add("content", outputContent);
        } else {
            state.addProperty("status", "error");
            JsonObject error = new JsonObject();
            error.addProperty("type", "tool.interrupted");
            error.addProperty("message", "Tool execution was interrupted before export");
            state.add("error", error);
        }
        result.add("state", state);

        JsonObject timeObj = new JsonObject();
        timeObj.addProperty("created", time);
        if (toolCall.getResult() != null) timeObj.addProperty("completed", time);
        result.add("time", timeObj);
        return result;
    }

    /**
     * Parses tool call arguments into the JSON object v2 expects. Non-object or unparsable
     * payloads are wrapped in a {@code raw} key so the value still decodes as an object.
     */
    @NotNull
    static JsonObject parseToolInput(@Nullable String argsStr) {
        if (argsStr != null && !argsStr.isBlank()) {
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(argsStr);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (Exception ignored) {
                // fall through to the raw wrapper below
            }
        }
        JsonObject wrapper = new JsonObject();
        if (argsStr != null && !argsStr.isBlank()) wrapper.addProperty("raw", argsStr);
        return wrapper;
    }

    // ── Message insertion ─────────────────────────────────────────────────────

    /**
     * Parameters for a single {@link #flushMessage} insertion. Bundled into a
     * record to keep the method signature within the 7-parameter limit (Sonar S107).
     */
    private record MessageInsert(
        @NotNull String role,
        @Nullable String agent,
        @Nullable String model,
        @NotNull List<JsonObject> parts,
        long timeCreated,
        @NotNull String projectDir,
        @Nullable String parentMessageId
    ) {
    }

    /**
     * Inserts a message and its pre-built parts into the database.
     *
     * @return the generated message ID (for parentID linking)
     */
    @NotNull
    private static String flushMessage(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull MessageInsert msg) throws SQLException {

        String messageId = generateId("msg");

        JsonObject msgData = buildMessageData(msg.role(), msg.agent(), msg.model(),
            msg.timeCreated(), msg.projectDir(), msg.parentMessageId());

        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO message (id, session_id, time_created, time_updated, data) "
                + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, messageId);
            ps.setString(2, sessionId);
            ps.setLong(3, msg.timeCreated());
            ps.setLong(4, msg.timeCreated());
            ps.setString(5, GSON.toJson(msgData));
            ps.executeUpdate();
        }

        for (JsonObject part : msg.parts()) {
            insertPart(conn, messageId, sessionId, part, msg.timeCreated());
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
        result.addProperty("callID", toolCall.getEntryId());
        result.addProperty("tool", ExportUtils.exportMcpToolName(toolCall, ExportUtils.OPENCODE_MCP_PREFIX));

        boolean completed = toolCall.getResult() != null;

        JsonObject stateObj = new JsonObject();
        stateObj.addProperty("status", completed ? "completed" : "running");

        // OpenCode Zod schema requires input: z.record(z.string(), z.any()) — must be a JSON object.
        stateObj.add(FIELD_INPUT, parseToolInput(toolCall.getArguments()));

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
