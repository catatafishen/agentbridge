package com.github.catatafishen.agentbridge.session.v2;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.github.catatafishen.agentbridge.session.db.ConversationReader;
import com.github.catatafishen.agentbridge.session.db.ConversationWriter;
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project-level singleton that manages session persistence via SQLite.
 *
 * <p>All reads and writes go through the {@link ConversationReader} and
 * {@link ConversationWriter} backed by the SQLite {@link ConversationDatabase}.
 *
 * <p>The current session ID is still tracked via the {@code .current-session-id}
 * file for consistency with the rest of the plugin infrastructure.
 *
 * <p>Static legacy JSONL parsing helpers are preserved for use by
 * {@code JsonlToSqliteMigrator}, backfill services, and tests.
 */
public final class SessionStoreV2 implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionStoreV2.class);

    private static final String CURRENT_SESSION_FILE = ".current-session-id";

    // Duplicate-literal constants used by static legacy conversion methods
    private static final String KEY_DENIAL_REASON = "denialReason";
    private static final String KEY_MODEL = "model";
    private static final String KEY_FILENAME = "filename";
    private static final String KEY_STATUS = "status";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_RESULT = "result";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Tracks the most recent async save so that {@link #awaitPendingSave(long)} can
     * block until the write completes before reading.
     * Guarded by {@link #saveLock} for atomic read-modify-write in future chaining.
     */
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);

    private final Object saveLock = new Object();

    /**
     * Cached transient session ID used when the session-id file is unreadable (I/O error).
     * Ensures repeated calls during the same IDE session return a consistent ID rather
     * than generating a different UUID each time (which would fragment sessions).
     */
    private volatile String transientSessionId;

    /**
     * Display name of the agent currently writing sessions (e.g. "GitHub Copilot").
     */
    private volatile String currentAgent = "Unknown";

    /**
     * Project handle used to look up the {@link ConversationDatabase}.
     * May be {@code null} when the store is instantiated directly via
     * {@link #SessionStoreV2()} for read-only use (backfill jobs / tests).
     */
    @Nullable
    private final Project project;

    /**
     * Lazy-initialised writer for the SQLite conversation DB.
     */
    @SuppressWarnings("java:S3077")
    private volatile ConversationWriter conversationWriter;

    /**
     * Lazy-initialised reader for the SQLite conversation DB.
     */
    @SuppressWarnings("java:S3077")
    private volatile ConversationReader conversationReader;

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public SessionStoreV2(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Read-only constructor for backfill jobs that operate on a
     * {@code basePath} without a {@link Project}. SQLite operations are
     * unavailable with this constructor.
     */
    public SessionStoreV2() {
        this.project = null;
    }

    /**
     * Test constructor that injects a database directly, bypassing project lookup.
     * Enables unit testing without an IntelliJ Project.
     */
    public SessionStoreV2(@NotNull ConversationDatabase database) {
        this.project = null;
        this.conversationWriter = new ConversationWriter(database);
        this.conversationReader = new ConversationReader(database);
    }

    /**
     * Returns the project-level singleton instance.
     */
    @NotNull
    public static SessionStoreV2 getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, SessionStoreV2.class);
    }

    /**
     * Sets the display name of the agent that is currently writing sessions.
     * Called whenever the active agent profile changes.
     *
     * @param agent human-readable agent name (e.g. "GitHub Copilot", "Claude CLI")
     */
    public void setCurrentAgent(@NotNull String agent) {
        this.currentAgent = agent;
    }

    /**
     * Metadata record for an archived or active session, suitable for display in a session picker.
     *
     * @param id        session UUID
     * @param agent     display name of the agent (e.g. "GitHub Copilot")
     * @param name      human-readable session name (e.g. "Fix auth bug"), empty if not set
     * @param createdAt epoch millis when the session was created
     * @param updatedAt epoch millis when the session was last updated
     * @param turnCount number of user turns in the session (0 if unknown / old index entry)
     */
    public record SessionRecord(
        @NotNull String id,
        @NotNull String agent,
        @NotNull String name,
        long createdAt,
        long updatedAt,
        int turnCount) {
    }

    @NotNull
    public List<SessionRecord> listSessions(@Nullable String basePath) {
        ConversationReader reader = getOrCreateReader();
        if (reader != null) {
            List<ConversationReader.SessionRecord> dbSessions = reader.listSessions();
            List<SessionRecord> result = new ArrayList<>();
            for (ConversationReader.SessionRecord sr : dbSessions) {
                result.add(new SessionRecord(
                    sr.id(),
                    sr.agentName(),
                    sr.displayName(),
                    parseIsoToEpochMillis(sr.startedAt()),
                    parseIsoToEpochMillis(sr.endedAt()),
                    sr.turnCount()
                ));
            }
            return result;
        }
        // Fallback: read from JSONL sessions-index.json (used by backfill services
        // which instantiate SessionStoreV2 without a Project).
        return listSessionsFromJsonlIndex(basePath);
    }

    // ── Main operations ───────────────────────────────────────────────────────

    /**
     * Reads sessions from the legacy JSONL sessions-index.json file.
     * Used by backfill services that operate without a Project.
     */
    @NotNull
    private List<SessionRecord> listSessionsFromJsonlIndex(@Nullable String basePath) {
        if (basePath == null) return List.of();
        java.io.File indexFile = new java.io.File(basePath, ".agent-work/sessions/sessions-index.json");
        if (!indexFile.isFile()) return List.of();
        try {
            String content = Files.readString(indexFile.toPath(), StandardCharsets.UTF_8);
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(content).getAsJsonArray();
            List<SessionRecord> result = new ArrayList<>();
            for (var element : arr) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                String id = obj.has("id") ? obj.get("id").getAsString() : "";
                if (id.isEmpty()) continue;
                String agent = obj.has("agent") ? obj.get("agent").getAsString() : "";
                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                long createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0;
                long updatedAt = obj.has("updatedAt") ? obj.get("updatedAt").getAsLong() : 0;
                int turnCount = obj.has("turnCount") ? obj.get("turnCount").getAsInt() : 0;
                result.add(new SessionRecord(id, agent, name, createdAt, updatedAt, turnCount));
            }
            result.sort(Comparator.comparingLong(SessionRecord::updatedAt).reversed());
            return result;
        } catch (Exception e) {
            LOG.debug("Failed to read JSONL sessions index: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Archives the current conversation by resetting the session ID file.
     * The SQLite data remains intact.
     */
    public void archive(@Nullable String basePath) {
        resetCurrentSessionId(basePath);
    }

    /**
     * Deletes the {@code .current-session-id} file so the next {@link #getCurrentSessionId}
     * call generates a fresh UUID. Use this when the user explicitly starts a new conversation
     * (e.g. "New Conversation" button), <b>not</b> during agent switches.
     */
    public void resetCurrentSessionId(@Nullable String basePath) {
        File sessionIdFile = currentSessionIdFile(basePath);
        try {
            Files.deleteIfExists(sessionIdFile.toPath());
        } catch (IOException e) {
            LOG.warn("Could not delete current-session-id file", e);
        }
    }

    public void branchCurrentSession(@Nullable String basePath) {
        LOG.warn("branchCurrentSession: branch not yet supported with SQLite storage");
    }

    private static final int MAX_SESSION_NAME_LENGTH = 60;

    static String truncateSessionName(@NotNull String promptText) {
        String name = promptText.replaceAll("\\s+", " ").trim();
        if (name.length() <= MAX_SESSION_NAME_LENGTH) return name;
        return name.substring(0, MAX_SESSION_NAME_LENGTH - 1) + "…";
    }

    /**
     * Appends {@code entries} to the current session via SQLite.
     */
    public void appendEntries(@Nullable String basePath, @NotNull List<EntryData> entries) {
        if (entries.isEmpty()) return;
        try {
            String agent = currentAgent;
            String sessionId = getCurrentSessionId(basePath);

            ConversationWriter writer = getOrCreateWriter();
            if (writer == null) {
                LOG.warn("Failed to append entries: ConversationWriter not available");
                return;
            }
            writer.recordEntries(sessionId, agent, "", entries);
        } catch (Exception e) {
            LOG.warn("Failed to append entries to SQLite session store", e);
        }
    }

    @Nullable
    private ConversationWriter getOrCreateWriter() {
        ConversationWriter local = conversationWriter;
        if (local != null) return local;
        synchronized (this) {
            if (conversationWriter != null) return conversationWriter;
            if (project == null) return null;
            ConversationDatabase db = ConversationDatabase.getInstance(project);
            if (!db.isReady()) {
                try {
                    db.initialize();
                } catch (Exception e) {
                    LOG.debug("ConversationDatabase initialization failed: " + e.getMessage());
                    return null;
                }
            }
            conversationWriter = new ConversationWriter(db);
            return conversationWriter;
        }
    }

    @Nullable
    private ConversationReader getOrCreateReader() {
        ConversationReader local = conversationReader;
        if (local != null) return local;
        synchronized (this) {
            if (conversationReader != null) return conversationReader;
            if (project == null) return null;
            ConversationDatabase db = ConversationDatabase.getInstance(project);
            if (!db.isReady()) {
                try {
                    db.initialize();
                } catch (Exception e) {
                    LOG.debug("ConversationDatabase initialization failed: " + e.getMessage());
                    return null;
                }
            }
            conversationReader = new ConversationReader(db);
            return conversationReader;
        }
    }

    /**
     * Appends entries on a pooled thread (non-blocking).
     * Futures are chained (not replaced) so that concurrent appends are serialized
     * and {@link #awaitPendingSave(long)} waits for <em>all</em> in-flight writes.
     */
    public void appendEntriesAsync(@Nullable String basePath, @NotNull List<EntryData> entries) {
        List<EntryData> snapshot = List.copyOf(entries);
        synchronized (saveLock) {
            pendingSave = pendingSave.thenRunAsync(
                () -> appendEntries(basePath, snapshot),
                AppExecutorUtil.getAppExecutorService());
        }
    }

    /**
     * Blocks until the most recent async append/save completes, or until
     * {@code timeoutMs} elapses. Safe to call when no save is pending — returns immediately.
     *
     * @param timeoutMs maximum wait in milliseconds
     */
    public void awaitPendingSave(long timeoutMs) {
        CompletableFuture<Void> future;
        synchronized (saveLock) {
            future = pendingSave;
        }
        if (future.isDone()) return;
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Timed out waiting for pending save (" + timeoutMs + " ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted waiting for pending save", e);
        } catch (Exception e) {
            LOG.warn("Error waiting for pending save", e);
        }
    }

    /**
     * Result of a tail-limited session load via {@link #loadRecentEntries(String)}.
     *
     * @param entries       loaded entries in chronological order
     * @param hasMoreOnDisk {@code true} when older entries exist that were not loaded
     */
    public record RecentEntriesResult(
        @NotNull List<EntryData> entries,
        boolean hasMoreOnDisk) {
    }

    /**
     * Loads conversation entries for the current session from SQLite.
     * Returns {@code null} if no entries exist.
     */
    @Nullable
    public List<EntryData> loadEntries(@Nullable String basePath) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return null;
        try {
            String sessionId = getCurrentSessionId(basePath);
            List<EntryData> entries = reader.loadEntries(sessionId);
            if (!entries.isEmpty()) return entries;
        } catch (Exception e) {
            LOG.warn("Failed to load entries from SQLite", e);
        }
        return null;
    }

    /**
     * Loads entries for a specific session by ID from SQLite.
     * Returns {@code null} if no session files exist.
     */
    @Nullable
    public List<EntryData> loadEntriesBySessionId(@Nullable String basePath, @NotNull String sessionId) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return null;
        try {
            List<EntryData> entries = reader.loadEntries(sessionId);
            return entries.isEmpty() ? null : entries;
        } catch (Exception e) {
            LOG.warn("Failed to load entries for session " + sessionId, e);
            return null;
        }
    }

    /**
     * A prompt entry paired with the session it belongs to and its optional turn statistics.
     * Used by {@link #loadPromptsFromAllSessions(Project)} to build cross-session prompt lists
     * without loading full session entry graphs into memory.
     *
     * @param sessionId the UUID of the session this prompt came from
     * @param prompt    the prompt entry
     * @param stats     the TurnStats immediately following the prompt, or {@code null} if not present
     */
    public record PromptWithContext(
        @NotNull String sessionId,
        @NotNull EntryData.Prompt prompt,
        @Nullable EntryData.TurnStats stats) {
    }

    /**
     * Loads prompts and their associated turn statistics from all known sessions.
     *
     * @param project the IntelliJ project (used to resolve the database)
     * @return prompts sorted chronologically (oldest first), each with its session ID
     */
    @NotNull
    public List<PromptWithContext> loadPromptsFromAllSessions(@NotNull Project project) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return List.of();
        List<ConversationReader.PromptWithStats> dbPrompts = reader.loadAllPrompts();
        List<PromptWithContext> result = new ArrayList<>();
        for (ConversationReader.PromptWithStats p : dbPrompts) {
            result.add(new PromptWithContext(p.sessionId(), p.prompt(), p.stats()));
        }
        result.sort(Comparator.comparing(p -> p.prompt().getTimestamp()));
        return result;
    }

    /**
     * Loads entries for a specific session by ID from SQLite, using the project context.
     * Returns {@code null} if no session files exist.
     */
    @Nullable
    public List<EntryData> loadEntriesBySessionId(
        @NotNull Project project, @NotNull String sessionId) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return null;
        try {
            List<EntryData> entries = reader.loadEntries(sessionId);
            return entries.isEmpty() ? null : entries;
        } catch (Exception e) {
            LOG.warn("Failed to load entries for session " + sessionId, e);
            return null;
        }
    }

    /**
     * Loads the most recent entries from the current session from SQLite.
     *
     * @return a {@link RecentEntriesResult} with entries in chronological order, or
     * {@code null} if no session exists
     */
    @Nullable
    public RecentEntriesResult loadRecentEntries(@Nullable String basePath) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return null;
        try {
            File idFile = currentSessionIdFile(basePath);
            if (!idFile.exists()) return null;

            String sessionId = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
            if (sessionId.isEmpty()) return null;

            List<EntryData> entries = reader.loadRecentEntries(sessionId, 50);
            if (entries.isEmpty()) return null;
            return new RecentEntriesResult(entries, false);
        } catch (IOException e) {
            LOG.warn("Could not read current-session-id", e);
            return null;
        } catch (Exception e) {
            LOG.warn("Failed to load recent entries from SQLite", e);
            return null;
        }
    }

    // ── v2 read (static legacy parsing — used by migrator, backfill, tests) ───

    @Nullable
    static List<EntryData> parseJsonlAutoDetect(@NotNull String content) {
        List<EntryData> directEntries = new ArrayList<>();
        List<JsonObject> legacyMessages = new ArrayList<>();
        int skippedLines = 0;

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!parseOneJsonlLine(line, directEntries, legacyMessages)) skippedLines++;
        }

        if (skippedLines > 0) {
            int totalParsed = directEntries.size() + legacyMessages.size();
            LOG.warn("JSONL parse: loaded " + totalParsed + " entries, skipped "
                + skippedLines + " malformed lines");
        }

        if (!directEntries.isEmpty()) return directEntries;
        if (!legacyMessages.isEmpty()) return convertLegacyMessages(legacyMessages);
        return null;
    }

    /**
     * Parses a single trimmed, non-empty JSONL line into the appropriate collection.
     *
     * @return {@code true} if parsed successfully, {@code false} if the line was malformed
     */
    static boolean parseOneJsonlLine(
        @NotNull String line,
        @NotNull List<EntryData> directEntries,
        @NotNull List<JsonObject> legacyMessages) {
        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);
            if (EntryDataJsonAdapter.isEntryFormat(line)) {
                EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                if (entry != null) directEntries.add(entry);
            } else {
                if (obj != null) legacyMessages.add(obj);
            }
            return true;
        } catch (Exception e) {
            LOG.warn("Skipping malformed JSONL line: " + line + " (" + e.getMessage() + ")");
            return false;
        }
    }

    @NotNull
    static List<EntryData> convertLegacyMessages(@NotNull List<JsonObject> messages) {
        List<EntryData> result = new ArrayList<>();
        for (JsonObject msg : messages) {
            convertLegacyMessage(msg, result);
        }
        return result;
    }

    /**
     * Parsed header fields from a legacy JSONL message object.
     * Groups the four fields that are threaded through all part-processing helpers
     * to keep parameter counts below the S107 threshold of 7.
     */
    private record LegacyMsgHeader(
        @NotNull String role,
        @NotNull String agent,
        @NotNull String model,
        @NotNull String ts) {
    }

    /**
     * Extracts the {@link LegacyMsgHeader} from a raw legacy message object.
     */
    private static LegacyMsgHeader parseLegacyMessageHeader(@NotNull JsonObject msg) {
        String role = msg.has("role") ? msg.get("role").getAsString() : "";
        long createdAt = msg.has("createdAt") ? msg.get("createdAt").getAsLong() : 0;
        String agent = msg.has("agent") && !msg.get("agent").isJsonNull()
            ? msg.get("agent").getAsString() : "";
        String model = msg.has(KEY_MODEL) && !msg.get(KEY_MODEL).isJsonNull()
            ? msg.get(KEY_MODEL).getAsString() : "";
        String ts = createdAt > 0 ? Instant.ofEpochMilli(createdAt).toString() : "";
        return new LegacyMsgHeader(role, agent, model, ts);
    }

    private static void convertLegacyMessage(@NotNull JsonObject msg, @NotNull List<EntryData> result) {
        LegacyMsgHeader h = parseLegacyMessageHeader(msg);
        if (EntryDataJsonAdapter.TYPE_SEPARATOR.equals(h.role())) {
            result.add(new EntryData.SessionSeparator(h.ts(), h.agent()));
            return;
        }

        JsonArray partsArray = msg.has("parts") ? msg.getAsJsonArray("parts") : new JsonArray();
        List<JsonObject> parts = new ArrayList<>();
        for (int i = 0; i < partsArray.size(); i++) {
            parts.add(partsArray.get(i).getAsJsonObject());
        }

        java.util.Set<Integer> consumedFileIndices = new java.util.HashSet<>();
        for (int idx = 0; idx < parts.size(); idx++) {
            processLegacyPart(parts.get(idx), h, parts, idx, consumedFileIndices, result);
        }
    }

    private static void processLegacyPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h,
        @NotNull List<JsonObject> parts, int idx,
        @NotNull java.util.Set<Integer> consumedFileIndices,
        @NotNull List<EntryData> result) {
        String type = part.has("type") ? part.get("type").getAsString() : "";
        switch (type) {
            case EntryDataJsonAdapter.TYPE_TEXT -> processTextPart(part, h, parts, idx, consumedFileIndices, result);
            case "reasoning" -> processReasoningPart(part, h, result);
            case "tool-invocation" -> processToolInvocationPart(part, h, result);
            case EntryDataJsonAdapter.TYPE_SUBAGENT -> processSubAgentPart(part, h, result);
            case EntryDataJsonAdapter.TYPE_STATUS -> processStatusPart(part, result);
            case "file" -> processFilePart(part, idx, consumedFileIndices, result);
            default -> { /* Unknown part type — skip for forward-compat */ }
        }
    }

    private static void processTextPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h,
        @NotNull List<JsonObject> parts, int idx,
        @NotNull java.util.Set<Integer> consumedFileIndices,
        @NotNull List<EntryData> result) {
        String text = part.has("text") ? part.get("text").getAsString() : "";
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        if ("user".equals(h.role())) {
            List<ContextFileRef> ctxFiles = collectLegacyFileParts(parts, idx + 1, consumedFileIndices);
            result.add(new EntryData.Prompt(text, partTs, ctxFiles.isEmpty() ? null : ctxFiles, "", partEid));
        } else {
            result.add(new EntryData.Text(text, partTs, h.agent(), h.model(), partEid));
        }
    }

    private static void processReasoningPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h, @NotNull List<EntryData> result) {
        String text = part.has("text") ? part.get("text").getAsString() : "";
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.Thinking(text, partTs, h.agent(), h.model(), partEid));
    }

    private static void processToolInvocationPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h, @NotNull List<EntryData> result) {
        JsonObject inv = part.has("toolInvocation") ? part.getAsJsonObject("toolInvocation") : new JsonObject();
        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "";
        String args = inv.has("args") && !inv.get("args").isJsonNull() ? inv.get("args").getAsString() : null;
        String toolResult = inv.has(KEY_RESULT) && !inv.get(KEY_RESULT).isJsonNull()
            ? inv.get(KEY_RESULT).getAsString() : null;
        boolean autoDenied = inv.has(KEY_DENIAL_REASON);
        String denialReason = autoDenied ? inv.get(KEY_DENIAL_REASON).getAsString() : null;
        String kind = inv.has("kind") ? inv.get("kind").getAsString() : "other";
        String toolStatus = inv.has(KEY_STATUS) ? inv.get(KEY_STATUS).getAsString() : null;
        String toolDescription = inv.has(KEY_DESCRIPTION) ? inv.get(KEY_DESCRIPTION).getAsString() : null;
        String filePath = inv.has("filePath") ? inv.get("filePath").getAsString() : null;
        String pluginTool = inv.has("pluginTool") ? inv.get("pluginTool").getAsString() : null;
        if (pluginTool == null && inv.has("mcpHandled") && inv.get("mcpHandled").getAsBoolean()) {
            pluginTool = toolName; // best-effort from legacy
        }
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.ToolCall(
            toolName, args, kind, toolResult, toolStatus, toolDescription, filePath,
            autoDenied, denialReason, pluginTool, partTs, h.agent(), h.model(), partEid));
    }

    private static void processSubAgentPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h, @NotNull List<EntryData> result) {
        String agentType = part.has("agentType") ? part.get("agentType").getAsString() : "general-purpose";
        String description = part.has(KEY_DESCRIPTION) ? part.get(KEY_DESCRIPTION).getAsString() : "";
        String prompt = part.has("prompt") ? part.get("prompt").getAsString() : null;
        String subResult = part.has(KEY_RESULT) ? part.get(KEY_RESULT).getAsString() : null;
        String status = part.has(KEY_STATUS) ? part.get(KEY_STATUS).getAsString() : "completed";
        int colorIndex = part.has("colorIndex") ? part.get("colorIndex").getAsInt() : 0;
        String callId = part.has("callId") ? part.get("callId").getAsString() : null;
        boolean autoDenied = part.has("autoDenied") && part.get("autoDenied").getAsBoolean();
        String denialReason = part.has(KEY_DENIAL_REASON) ? part.get(KEY_DENIAL_REASON).getAsString() : null;
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.SubAgent(
            agentType, description,
            (prompt == null || prompt.isEmpty()) ? null : prompt,
            (subResult == null || subResult.isEmpty()) ? null : subResult,
            (status == null || status.isEmpty()) ? "completed" : status,
            colorIndex, callId, autoDenied, denialReason, partTs, h.agent(), h.model(), partEid));
    }

    private static void processStatusPart(@NotNull JsonObject part, @NotNull List<EntryData> result) {
        String icon = part.has("icon") ? part.get("icon").getAsString() : "ℹ";
        String message = part.has("message") ? part.get("message").getAsString() : "";
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.Status(icon, message, partEid));
    }

    private static void processFilePart(
        @NotNull JsonObject part, int idx,
        @NotNull java.util.Set<Integer> consumedFileIndices,
        @NotNull List<EntryData> result) {
        if (consumedFileIndices.contains(idx)) return;
        String filename = part.has(KEY_FILENAME) ? part.get(KEY_FILENAME).getAsString() : "";
        String path = part.has("path") ? part.get("path").getAsString() : "";
        result.add(new EntryData.ContextFiles(List.of(new FileRef(filename, path))));
    }

    // ── Legacy conversion helpers ─────────────────────────────────────────────

    /**
     * Reads a per-entry timestamp from a legacy V2 part, falling back to the message-level timestamp.
     */
    @NotNull
    static String readLegacyTimestamp(@NotNull JsonObject part, @NotNull String messageLevelTs) {
        if (part.has("ts")) {
            String partTs = part.get("ts").getAsString();
            if (!partTs.isEmpty()) return partTs;
        }
        return messageLevelTs;
    }

    /**
     * Read entry ID from a part's "eid" field, falling back to a new UUID if absent.
     */
    private static String readLegacyEntryId(JsonObject part) {
        return part.has("eid") ? part.get("eid").getAsString() : UUID.randomUUID().toString();
    }

    /**
     * Collect consecutive "file" parts starting at {@code startIdx} from a parts list,
     * returning them as context file triples (name, path, line). Skips non-file parts.
     * Records consumed indices in {@code consumed} so the caller can skip them.
     */
    static List<ContextFileRef> collectLegacyFileParts(
        List<JsonObject> parts, int startIdx, java.util.Set<Integer> consumed) {
        List<ContextFileRef> files = new ArrayList<>();
        for (int i = startIdx; i < parts.size(); i++) {
            JsonObject p = parts.get(i);
            String t = p.has("type") ? p.get("type").getAsString() : "";
            if (!"file".equals(t)) continue;
            String fn = p.has(KEY_FILENAME) ? p.get(KEY_FILENAME).getAsString() : "";
            String path = p.has("path") ? p.get("path").getAsString() : "";
            int line = p.has("line") ? p.get("line").getAsInt() : 0;
            files.add(new ContextFileRef(fn, path, line));
            consumed.add(i);
        }
        return files;
    }

    // ── Session ID management (file-based) ────────────────────────────────────

    /**
     * Returns the current active session UUID, creating and persisting one if needed.
     */
    @NotNull
    public String getCurrentSessionId(@Nullable String basePath) {
        File idFile = currentSessionIdFile(basePath);
        try {
            if (idFile.exists()) {
                String id = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return id;
            }
            String newId = UUID.randomUUID().toString();
            //noinspection ResultOfMethodCallIgnored  — best-effort mkdirs
            idFile.getParentFile().mkdirs();
            Files.writeString(idFile.toPath(), newId, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return newId;
        } catch (IOException e) {
            LOG.warn("Could not read/write current-session-id, using cached transient UUID", e);
            if (transientSessionId == null) {
                transientSessionId = UUID.randomUUID().toString();
            }
            return transientSessionId;
        }
    }

    @Override
    public void dispose() {
        // Await any in-flight save so it isn't lost on shutdown
        awaitPendingSave(3_000);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @NotNull
    private static File currentSessionIdFile(@Nullable String basePath) {
        return new File(sessionsDir(basePath), CURRENT_SESSION_FILE);
    }

    // Intentional: this private helper wraps the deprecated ExportUtils.sessionsDir(String)
    // to avoid duplicating the path logic. The deprecation is meant for external callers —
    // internal methods that haven't migrated to the Project-based API still use this shim.
    @SuppressWarnings("deprecation")
    @NotNull
    private static File sessionsDir(@Nullable String basePath) {
        return ExportUtils.sessionsDir(basePath);
    }

    /**
     * Parses an ISO-8601 timestamp string to epoch millis, returning 0 on failure.
     */
    private static long parseIsoToEpochMillis(@Nullable String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isEmpty()) return 0;
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
