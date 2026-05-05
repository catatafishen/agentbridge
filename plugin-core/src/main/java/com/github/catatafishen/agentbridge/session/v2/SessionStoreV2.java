package com.github.catatafishen.agentbridge.session.v2;

import com.github.catatafishen.agentbridge.bridge.ConversationStore;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.ConversationSerializer;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project-level singleton that manages v2 JSONL session persistence.
 *
 * <p>Reads v1 {@code conversation.json} as a fallback via {@link ConversationStore},
 * but all new writes go to v2 JSONL only.
 *
 * <p>Directory layout:
 * <pre>
 * &lt;projectBase&gt;/.agent-work/
 *   conversation.json          ← v1 (read-only fallback, no longer written)
 *   conversations/             ← v1 archives
 *   sessions/
 *     sessions-index.json      ← JSON array of session metadata objects
 *     &lt;uuid&gt;.jsonl             ← one file per session (v2, canonical format)
 * </pre>
 */
public final class SessionStoreV2 implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionStoreV2.class);

    private static final String SESSIONS_INDEX = "sessions-index.json";
    private static final String CURRENT_SESSION_FILE = ".current-session-id";
    private static final String JSONL_EXT = ".jsonl";

    private static final String KEY_ID = "id";
    private static final String KEY_AGENT = "agent";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_JSONL_PATH = "jsonlPath";
    private static final String KEY_TURN_COUNT = "turnCount";
    private static final String KEY_DIRECTORY = "directory";
    private static final String KEY_NAME = "name";

    // Duplicate-literal constants (S1192)
    private static final String KEY_DENIAL_REASON = "denialReason";
    private static final String KEY_MODEL = "model";
    private static final String KEY_FILENAME = "filename";
    private static final String KEY_STATUS = "status";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_RESULT = "result";
    private static final String LOG_JSONL_PARSE_FAIL = "Failed to parse v2 JSONL for session ";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ConversationStore v1Store = new ConversationStore();

    /**
     * Tracks the most recent async save so that {@link #awaitPendingSave(long)} can
     * block until the write completes before reading the v2 JSONL from disk.
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
     * Project handle used to look up the {@link com.github.catatafishen.agentbridge.session.db.ConversationDatabase}
     * for dual-writes during the JSONL→SQLite migration. Auto-injected by the
     * IntelliJ service container. May be {@code null} when the store is
     * instantiated directly via {@link #SessionStoreV2()} for read-only use
     * (backfill jobs / tests) — in that case the SQLite dual-write is skipped.
     */
    @Nullable
    private final Project project;

    /**
     * Lazy-initialised writer for the new SQLite conversation DB. Phase 1 of the
     * migration runs this in parallel with JSONL writes; reads still come from
     * JSONL until Phase 3.
     */
    @SuppressWarnings("java:S3077") // intentional: writer is constructed atomically, only the reference is volatile
    private volatile com.github.catatafishen.agentbridge.session.db.ConversationWriter conversationWriter;

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public SessionStoreV2(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Read-only constructor for backfill jobs and tests that operate on a
     * {@code basePath} without a {@link Project}. SQLite dual-write is a no-op
     * with this constructor — JSONL is the only write path.
     */
    public SessionStoreV2() {
        this.project = null;
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
        File indexFile = new File(sessionsDir(basePath), SESSIONS_INDEX);
        List<JsonObject> records = readIndexRecords(indexFile);
        List<SessionRecord> result = new ArrayList<>();
        for (JsonObject rec : records) {
            SessionRecord sr = parseSessionRecord(rec);
            if (sr != null) result.add(sr);
        }
        result.sort(Comparator.comparingLong(SessionRecord::updatedAt).reversed());
        return result;
    }

    /**
     * Parses one index record into a {@link SessionRecord}; returns {@code null} if the id is missing.
     */
    @Nullable
    private static SessionRecord parseSessionRecord(@NotNull JsonObject rec) {
        String id = rec.has(KEY_ID) ? rec.get(KEY_ID).getAsString() : null;
        if (id == null || id.isEmpty()) return null;
        String agent = rec.has(KEY_AGENT) ? rec.get(KEY_AGENT).getAsString() : "Unknown";
        String name = rec.has(KEY_NAME) ? rec.get(KEY_NAME).getAsString() : "";
        long createdAt = rec.has(KEY_CREATED_AT) ? rec.get(KEY_CREATED_AT).getAsLong() : 0;
        long updatedAt = rec.has(KEY_UPDATED_AT) ? rec.get(KEY_UPDATED_AT).getAsLong() : 0;
        int turnCount = rec.has(KEY_TURN_COUNT) ? rec.get(KEY_TURN_COUNT).getAsInt() : 0;
        return new SessionRecord(id, agent, name, createdAt, updatedAt, turnCount);
    }
    // ── Main operations ───────────────────────────────────────────────────────

    /**
     * Archives the current conversation: finalises the v2 session, then delegates to
     * {@link ConversationStore#archive(String)} for v1. Does <b>not</b> delete the
     * current session ID — call {@link #resetCurrentSessionId(String)} separately
     * when a completely fresh session is desired (e.g. "New Conversation").
     *
     * <p>Keeping {@code .current-session-id} intact is important during agent switches:
     * {@code buildAndShowChatPanel()} calls this on first connection, but the session
     * switch export ({@code SessionSwitchService.doExport}) may still need the same
     * session ID for subsequent export steps.
     */
    public void archive(@Nullable String basePath) {
        finaliseCurrentSession();
        v1Store.archive(basePath);
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
        File sessionsDir = sessionsDir(basePath);
        File idFile = currentSessionIdFile(basePath);
        if (!idFile.exists()) {
            LOG.info("branchCurrentSession: no current session to branch");
            return;
        }

        String sessionId;
        try {
            sessionId = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            LOG.warn("branchCurrentSession: could not read current-session-id", e);
            return;
        }
        if (sessionId.isEmpty()) return;

        List<Path> allFiles = SessionFileRotation.listAllFiles(sessionsDir, sessionId);
        if (allFiles.isEmpty()) {
            LOG.info("branchCurrentSession: no JSONL data to snapshot (session " + sessionId + ")");
            return;
        }

        String branchId = UUID.randomUUID().toString();
        try {
            performBranchCopy(sessionsDir, sessionId, branchId, basePath);
        } catch (IOException e) {
            LOG.warn("Failed to branch session " + sessionId, e);
        }
    }

    /**
     * Carries out the physical file copies and index update for a branch operation.
     * Separated from {@link #branchCurrentSession} to reduce cognitive complexity.
     */
    private void performBranchCopy(
        @NotNull File sessionsDir,
        @NotNull String sessionId,
        @NotNull String branchId,
        @Nullable String basePath) throws IOException {

        Files.createDirectories(sessionsDir.toPath());

        // Copy all part files with the new branch ID prefix
        List<File> partFiles = SessionFileRotation.listPartFiles(sessionsDir, sessionId);
        for (int i = 0; i < partFiles.size(); i++) {
            String partName = String.format("%s.part-%03d.jsonl", branchId, i + 1);
            Files.copy(partFiles.get(i).toPath(), sessionsDir.toPath().resolve(partName));
        }

        // Copy the active (tail) file
        File sourceFile = new File(sessionsDir, sessionId + JSONL_EXT);
        File branchFile = new File(sessionsDir, branchId + JSONL_EXT);
        if (sourceFile.exists()) {
            Files.copy(sourceFile.toPath(), branchFile.toPath());
        }

        File indexFile = new File(sessionsDir, SESSIONS_INDEX);
        List<JsonObject> records = readIndexRecords(indexFile);
        int turnCount = findTurnCountInIndex(records, sessionId);

        long now = System.currentTimeMillis();
        String timestamp = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        JsonObject branchRec = new JsonObject();
        branchRec.addProperty(KEY_ID, branchId);
        branchRec.addProperty(KEY_AGENT, currentAgent + " (branch " + timestamp + ")");
        branchRec.addProperty(KEY_DIRECTORY, basePath != null ? basePath : "");
        branchRec.addProperty(KEY_CREATED_AT, now);
        branchRec.addProperty(KEY_UPDATED_AT, now);
        branchRec.addProperty(KEY_JSONL_PATH, branchFile.getName());
        branchRec.addProperty(KEY_TURN_COUNT, turnCount);
        branchRec.addProperty("branchedFrom", sessionId);
        records.add(branchRec);

        JsonArray arr = new JsonArray();
        records.forEach(arr::add);
        Files.writeString(indexFile.toPath(), GSON.toJson(arr), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        int totalFiles = partFiles.size() + (sourceFile.exists() ? 1 : 0);
        LOG.info("Branched session " + sessionId + " → " + branchId
            + " at " + timestamp + " (" + totalFiles + " file(s))");
    }

    /**
     * Looks up the stored {@code turnCount} for a session in an already-loaded index records list.
     */
    private static int findTurnCountInIndex(@NotNull List<JsonObject> records, @NotNull String sessionId) {
        for (JsonObject rec : records) {
            if (rec.has(KEY_ID) && sessionId.equals(rec.get(KEY_ID).getAsString())) {
                return rec.has(KEY_TURN_COUNT) ? rec.get(KEY_TURN_COUNT).getAsInt() : 0;
            }
        }
        return 0;
    }

    private static final int MAX_SESSION_NAME_LENGTH = 60;

    static String truncateSessionName(@NotNull String promptText) {
        String name = promptText.replaceAll("\\s+", " ").trim();
        if (name.length() <= MAX_SESSION_NAME_LENGTH) return name;
        return name.substring(0, MAX_SESSION_NAME_LENGTH - 1) + "…";
    }

    /**
     * Appends {@code entries} to the current session JSONL without overwriting existing content.
     * Creates the file if it does not yet exist. Rotates the file first if it exceeds the size
     * limit or crosses a date boundary. The sessions index is updated incrementally:
     * {@code turnCount} is incremented by the number of {@link EntryData.Prompt} entries in
     * the batch, and the session name is set from the first prompt only if not already stored.
     */
    public void appendEntries(@Nullable String basePath, @NotNull List<EntryData> entries) {
        if (entries.isEmpty()) return;
        try {
            String agent = currentAgent;

            String sessionId = getCurrentSessionId(basePath);
            File dir = sessionsDir(basePath);
            //noinspection ResultOfMethodCallIgnored  — best-effort
            dir.mkdirs();

            File jsonlFile = new File(dir, sessionId + JSONL_EXT);

            SessionFileRotation.rotateIfNeeded(jsonlFile, dir, sessionId, Clock.systemDefaultZone());

            StringBuilder sb = new StringBuilder();
            int additionalTurns = 0;
            String firstPromptText = "";
            for (EntryData entry : entries) {
                sb.append(GSON.toJson(EntryDataJsonAdapter.serialize(entry))).append('\n');
                if (entry instanceof EntryData.Prompt p) {
                    additionalTurns++;
                    if (firstPromptText.isEmpty() && !p.getText().isBlank()) {
                        firstPromptText = truncateSessionName(p.getText());
                    }
                }
            }
            Files.writeString(jsonlFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            appendSessionsIndex(basePath, sessionId, dir, jsonlFile.getName(), agent,
                firstPromptText, additionalTurns);

            // Dual-write to the new SQLite conversation DB (Phase 1 of migration).
            // Best-effort: failures are swallowed inside the writer.
            dualWriteToSqlite(sessionId, agent, entries);
        } catch (Exception e) {
            LOG.warn("Failed to append entries to v2 session JSONL", e);
        }
    }

    /**
     * Writes the same batch into the SQLite conversation DB. No-op when the
     * database service is not yet initialised (e.g. during early startup or
     * when running without a real project context).
     */
    private void dualWriteToSqlite(@NotNull String sessionId, @NotNull String agent,
                                   @NotNull List<EntryData> entries) {
        try {
            com.github.catatafishen.agentbridge.session.db.ConversationWriter writer =
                getOrCreateWriter();
            if (writer == null) return;
            writer.recordEntries(sessionId, agent, "", entries);
        } catch (Exception e) {
            // Swallow — JSONL is still authoritative during Phase 1.
            LOG.debug("ConversationWriter dual-write skipped: " + e.getMessage());
        }
    }

    @Nullable
    private com.github.catatafishen.agentbridge.session.db.ConversationWriter getOrCreateWriter() {
        com.github.catatafishen.agentbridge.session.db.ConversationWriter local = conversationWriter;
        if (local != null) return local;
        synchronized (this) {
            if (conversationWriter != null) return conversationWriter;
            if (project == null || project.isDisposed()) return null;
            com.github.catatafishen.agentbridge.session.db.ConversationDatabase db =
                com.github.catatafishen.agentbridge.session.db.ConversationDatabase.getInstance(project);
            if (!db.isReady()) {
                try {
                    db.initialize();
                } catch (Exception e) {
                    LOG.debug("ConversationDatabase initialization failed: " + e.getMessage());
                    return null;
                }
            }
            conversationWriter =
                new com.github.catatafishen.agentbridge.session.db.ConversationWriter(db);
            return conversationWriter;
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
     * <p>Call this before reading the v2 JSONL from disk to ensure the latest conversation
     * state has been flushed.
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
     * Maximum bytes to read from disk when loading recent entries for UI restore or export.
     * Files are read newest-first and loading stops once this budget is exceeded, keeping
     * memory use bounded even for very large sessions.
     */
    static final long RECENT_ENTRIES_MAX_BYTES = 20L * 1024 * 1024; // 20 MB

    /**
     * Result of a tail-limited session load via {@link #loadRecentEntries(String)}.
     *
     * @param entries       loaded entries in chronological order
     * @param hasMoreOnDisk {@code true} when older entries exist in part files that were
     *                      not loaded because the byte budget was reached
     */
    public record RecentEntriesResult(
        @NotNull List<EntryData> entries,
        boolean hasMoreOnDisk) {
    }

    /**
     * Loads conversation directly as EntryData entries from V2 JSONL,
     * bypassing the V1 JSON intermediary. This is the preferred load path.
     * Falls back to V1 if V2 is absent.
     */
    @Nullable
    public List<EntryData> loadEntries(@Nullable String basePath) {
        try {
            List<EntryData> v2Entries = loadEntriesFromV2(basePath);
            if (v2Entries != null) return v2Entries;
        } catch (Exception e) {
            LOG.warn("Failed to load entries from v2 format, falling back to v1", e);
        }
        String v1Json = v1Store.loadJson(basePath);
        if (v1Json == null) return null;
        return ConversationSerializer.INSTANCE.deserialize(v1Json);
    }

    /**
     * Loads entries for a specific session by ID from V2 JSONL.
     * Reads all part files and the current active file, merging them in order.
     * Returns {@code null} if no session files exist.
     */
    @Nullable
    public List<EntryData> loadEntriesBySessionId(@Nullable String basePath, @NotNull String sessionId) {
        File dir = sessionsDir(basePath);
        List<Path> files = SessionFileRotation.listAllFiles(dir, sessionId);
        if (files.isEmpty()) return null;
        try {
            return loadEntriesFromFiles(files);
        } catch (Exception e) {
            LOG.warn(LOG_JSONL_PARSE_FAIL + sessionId, e);
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
     * Efficiently loads prompts and their associated turn statistics from <em>all</em> known
     * sessions by scanning JSONL files for {@code "type":"prompt"} and {@code "type":"turnStats"}
     * lines only.
     *
     * <p>Unlike {@link #loadEntries(String)} (which parses every entry), this method reads each
     * JSONL file line-by-line and only parses the two lightweight types needed for the prompts
     * list, making it practical even for sessions with hundreds of megabytes of tool-call data.
     *
     * @param project the IntelliJ project (used to resolve the configured sessions directory)
     * @return prompts sorted chronologically (oldest first), each with its session ID
     */
    @NotNull
    public List<PromptWithContext> loadPromptsFromAllSessions(@NotNull Project project) {
        File dir = ExportUtils.sessionsDir(project);
        List<SessionRecord> sessions = listSessions(project.getBasePath());
        List<PromptWithContext> result = new ArrayList<>();
        for (SessionRecord session : sessions) {
            List<Path> files = SessionFileRotation.listAllFiles(dir, session.id());
            result.addAll(scanPromptsFromFiles(session.id(), files));
        }
        result.sort(Comparator.comparing(p -> p.prompt().getTimestamp()));
        return result;
    }

    /**
     * Scans JSONL files for prompt + turnStats entries only (skips everything else).
     */
    @NotNull
    private List<PromptWithContext> scanPromptsFromFiles(
        @NotNull String sessionId, @NotNull List<Path> files) {
        List<PromptWithContext> result = new ArrayList<>();
        EntryData.Prompt pending = null;
        for (Path file : files) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.contains("\"type\"")) continue;
                    if (line.contains("\"type\":\"" + EntryDataJsonAdapter.TYPE_PROMPT + "\"")) {
                        pending = tryParsePrompt(line, sessionId, result, pending);
                    } else if (pending != null
                        && line.contains("\"type\":\"" + EntryDataJsonAdapter.TYPE_TURN_STATS + "\"")) {
                        pending = tryParseTurnStats(line, sessionId, result, pending);
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to scan session file for prompts: " + file, e);
            }
        }
        if (pending != null) {
            result.add(new PromptWithContext(sessionId, pending, null));
        }
        return result;
    }

    @Nullable
    private EntryData.Prompt tryParsePrompt(
        @NotNull String line, @NotNull String sessionId,
        @NotNull List<PromptWithContext> result, @Nullable EntryData.Prompt pending) {
        if (pending != null) {
            result.add(new PromptWithContext(sessionId, pending, null));
        }
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            EntryData entry = EntryDataJsonAdapter.deserialize(obj);
            return entry instanceof EntryData.Prompt p ? p : pending;
        } catch (Exception e) {
            LOG.warn("Failed to parse prompt line in session " + sessionId, e);
            return pending;
        }
    }

    @Nullable
    private EntryData.Prompt tryParseTurnStats(
        @NotNull String line, @NotNull String sessionId,
        @NotNull List<PromptWithContext> result, @NotNull EntryData.Prompt pending) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            EntryData entry = EntryDataJsonAdapter.deserialize(obj);
            if (entry instanceof EntryData.TurnStats stats) {
                result.add(new PromptWithContext(sessionId, pending, stats));
                return null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse turnStats line in session " + sessionId, e);
        }
        return pending;
    }

    /**
     * Loads entries for a specific session by ID from V2 JSONL, using the configured sessions
     * directory resolved from {@link ExportUtils#sessionsDir(Project)}.
     * Returns {@code null} if no session files exist.
     */
    @Nullable
    public List<EntryData> loadEntriesBySessionId(
        @NotNull Project project, @NotNull String sessionId) {
        File dir = ExportUtils.sessionsDir(project);
        List<Path> files = SessionFileRotation.listAllFiles(dir, sessionId);
        if (files.isEmpty()) return null;
        try {
            return loadEntriesFromFiles(files);
        } catch (Exception e) {
            LOG.warn(LOG_JSONL_PARSE_FAIL + sessionId, e);
            return null;
        }
    }

    /**
     * Loads entries directly from V2 JSONL file(s) for the current session.
     * Reads all part files and the current active file, merging them in order.
     * Auto-detects format per line: {@code "type":} → new EntryData format,
     * {@code "role":} → old legacy message format (converted via {@link #convertLegacyMessages}).
     */
    @Nullable
    private List<EntryData> loadEntriesFromV2(@Nullable String basePath) {
        File dir = sessionsDir(basePath);
        File idFile = currentSessionIdFile(basePath);
        if (!idFile.exists()) return null;

        String sessionId;
        try {
            sessionId = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            LOG.warn("Could not read current-session-id", e);
            return null;
        }
        if (sessionId.isEmpty()) return null;

        List<Path> files = SessionFileRotation.listAllFiles(dir, sessionId);
        if (files.isEmpty()) return null;

        try {
            return loadEntriesFromFiles(files);
        } catch (Exception e) {
            LOG.warn(LOG_JSONL_PARSE_FAIL + sessionId, e);
            return null;
        }
    }

    /**
     * Loads the most recent entries from the current session, bounded by
     * {@link #RECENT_ENTRIES_MAX_BYTES}. Reads part files in reverse order (active file
     * first, then part files from highest to lowest) and stops once the cumulative
     * on-disk byte size of loaded files reaches the budget.
     *
     * <p>Use this for UI restore and for export, where only recent context is needed.
     * Use {@link #loadEntries(String)} only when all historical data is required
     * (e.g., usage statistics).
     *
     * @return a {@link RecentEntriesResult} with entries in chronological order, or
     * {@code null} if no v2 session files exist
     */
    @Nullable
    public RecentEntriesResult loadRecentEntries(@Nullable String basePath) {
        File dir = sessionsDir(basePath);
        File idFile = currentSessionIdFile(basePath);
        if (!idFile.exists()) return null;

        String sessionId;
        try {
            sessionId = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            LOG.warn("Could not read current-session-id", e);
            return null;
        }
        if (sessionId.isEmpty()) return null;

        List<Path> allFiles = SessionFileRotation.listAllFiles(dir, sessionId);
        if (allFiles.isEmpty()) return null;

        // Collect files newest-first until byte budget is reached.
        List<Path> filesToLoad = new ArrayList<>();
        long totalBytesRead = 0;
        boolean hasMoreOnDisk = false;
        for (int i = allFiles.size() - 1; i >= 0; i--) {
            if (totalBytesRead >= RECENT_ENTRIES_MAX_BYTES) {
                hasMoreOnDisk = true;
                break;
            }
            Path file = allFiles.get(i);
            filesToLoad.add(file);
            totalBytesRead += file.toFile().length();
        }

        // Reverse to chronological order and parse.
        Collections.reverse(filesToLoad);
        List<EntryData> allDirectEntries = new ArrayList<>();
        List<JsonObject> allLegacyMessages = new ArrayList<>();
        int totalSkipped = 0;
        for (Path file : filesToLoad) {
            totalSkipped += parseFileIntoCollections(file, allDirectEntries, allLegacyMessages);
        }
        if (totalSkipped > 0) {
            logSkippedLines(allDirectEntries.size() + allLegacyMessages.size(),
                filesToLoad.size(), totalSkipped, "recent");
        }

        List<EntryData> entries;
        if (!allDirectEntries.isEmpty()) entries = allDirectEntries;
        else if (!allLegacyMessages.isEmpty()) entries = convertLegacyMessages(allLegacyMessages);
        else return null;

        return new RecentEntriesResult(entries, hasMoreOnDisk);
    }

    @Nullable
    private List<EntryData> loadEntriesFromFiles(@NotNull List<Path> files) {
        List<EntryData> allDirectEntries = new ArrayList<>();
        List<JsonObject> allLegacyMessages = new ArrayList<>();
        int totalSkipped = 0;

        for (Path file : files) {
            totalSkipped += parseFileIntoCollections(file, allDirectEntries, allLegacyMessages);
        }

        if (totalSkipped > 0) {
            logSkippedLines(allDirectEntries.size() + allLegacyMessages.size(),
                files.size(), totalSkipped, null);
        }

        if (!allDirectEntries.isEmpty()) return allDirectEntries;
        if (!allLegacyMessages.isEmpty()) return convertLegacyMessages(allLegacyMessages);
        return null;
    }

    /**
     * Parses a single JSONL file into the given collections.
     * Extracted to support both full-load and tail-load paths.
     *
     * @return number of malformed lines skipped
     */
    private int parseFileIntoCollections(
        @NotNull Path file,
        @NotNull List<EntryData> directEntries,
        @NotNull List<JsonObject> legacyMessages) {

        int skipped = 0;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!parseOneJsonlLine(line, directEntries, legacyMessages)) skipped++;
            }
        } catch (IOException e) {
            LOG.warn("Failed to read session file: " + file, e);
        }
        return skipped;
    }

    /**
     * Emits a WARN log when malformed JSONL lines were skipped during a load.
     *
     * @param totalParsed total entries successfully parsed
     * @param fileCount   number of files read
     * @param skipped     number of lines skipped
     * @param qualifier   optional label inserted before "JSONL parse" (e.g. "recent"); may be null
     */
    private static void logSkippedLines(int totalParsed, int fileCount, int skipped, @Nullable String qualifier) {
        String label = qualifier != null ? "JSONL parse (" + qualifier + ")" : "JSONL parse";
        LOG.warn(label + ": loaded " + totalParsed + " entries across "
            + fileCount + " file(s), skipped " + skipped + " malformed lines");
    }

    /**
     * Parses a single trimmed, non-empty JSONL line into the appropriate collection.
     * Extracted from {@link #loadEntriesFromFiles} to fix S1141 (nested try).
     *
     * @return {@code true} if parsed successfully, {@code false} if the line was malformed
     */
    private static boolean parseOneJsonlLine(
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

    // ── v2 write ──────────────────────────────────────────────────────────────

    private void appendSessionsIndex(
        @Nullable String basePath,
        @NotNull String sessionId,
        @NotNull File sessionsDir,
        @NotNull String jsonlFileName,
        @NotNull String agentName,
        @NotNull String firstPromptText,
        int additionalTurns) throws IOException {

        File indexFile = new File(sessionsDir, SESSIONS_INDEX);
        List<JsonObject> records = readIndexRecords(indexFile);

        long now = System.currentTimeMillis();
        String directory = basePath != null ? basePath : "";

        boolean found = false;
        for (JsonObject rec : records) {
            if (rec.has(KEY_ID) && sessionId.equals(rec.get(KEY_ID).getAsString())) {
                updateExistingIndexEntry(rec, now, agentName, additionalTurns, firstPromptText);
                found = true;
                break;
            }
        }
        if (!found) {
            JsonObject newRec = new JsonObject();
            newRec.addProperty(KEY_ID, sessionId);
            newRec.addProperty(KEY_AGENT, agentName);
            newRec.addProperty(KEY_DIRECTORY, directory);
            newRec.addProperty(KEY_CREATED_AT, now);
            newRec.addProperty(KEY_UPDATED_AT, now);
            newRec.addProperty(KEY_JSONL_PATH, jsonlFileName);
            newRec.addProperty(KEY_TURN_COUNT, additionalTurns);
            if (!firstPromptText.isEmpty()) newRec.addProperty(KEY_NAME, firstPromptText);
            records.add(newRec);
        }

        JsonArray arr = new JsonArray();
        records.forEach(arr::add);
        Files.writeString(indexFile.toPath(), GSON.toJson(arr), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Applies an incremental update to an existing index record. Extracted from
     * {@link #appendSessionsIndex} to reduce cognitive complexity (S3776).
     */
    private static void updateExistingIndexEntry(
        @NotNull JsonObject rec,
        long now,
        @NotNull String agentName,
        int additionalTurns,
        @NotNull String firstPromptText) {
        rec.addProperty(KEY_UPDATED_AT, now);
        // Never overwrite agent — keep the original agent from session creation.
        if (!rec.has(KEY_AGENT)) {
            rec.addProperty(KEY_AGENT, agentName);
        }
        if (additionalTurns > 0) {
            int current = rec.has(KEY_TURN_COUNT) ? rec.get(KEY_TURN_COUNT).getAsInt() : 0;
            rec.addProperty(KEY_TURN_COUNT, current + additionalTurns);
        }
        // Only set session name from the first prompt; never overwrite an existing name.
        if (!firstPromptText.isEmpty() && !rec.has(KEY_NAME)) {
            rec.addProperty(KEY_NAME, firstPromptText);
        }
    }

    // ── v2 read ───────────────────────────────────────────────────────────────

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
        long createdAt = msg.has(KEY_CREATED_AT) ? msg.get(KEY_CREATED_AT).getAsLong() : 0;
        String agent = msg.has(KEY_AGENT) && !msg.get(KEY_AGENT).isJsonNull()
            ? msg.get(KEY_AGENT).getAsString() : "";
        String model = msg.has(KEY_MODEL) && !msg.get(KEY_MODEL).isJsonNull()
            ? msg.get(KEY_MODEL).getAsString() : "";
        String ts = createdAt > 0 ? java.time.Instant.ofEpochMilli(createdAt).toString() : "";
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

    // ── session finalisation ──────────────────────────────────────────────────

    private void finaliseCurrentSession() {
        // Nothing special needed — the JSONL is already up-to-date.
        // This is a hook for future use (e.g. writing a "closed" marker).
    }

    @Override
    public void dispose() {
        // Await any in-flight save so it isn't lost on shutdown
        awaitPendingSave(3_000);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    // Intentional: this private helper wraps the deprecated ExportUtils.sessionsDir(String)
    // to avoid duplicating the path logic. The deprecation is meant for external callers —
    // internal methods that haven't migrated to the Project-based API still use this shim.
    @SuppressWarnings("deprecation")
    @NotNull
    private static File sessionsDir(@Nullable String basePath) {
        return ExportUtils.sessionsDir(basePath);
    }

    @NotNull
    private static File currentSessionIdFile(@Nullable String basePath) {
        return new File(sessionsDir(basePath), CURRENT_SESSION_FILE);
    }

    @NotNull
    private static List<JsonObject> readIndexRecords(@NotNull File indexFile) {
        List<JsonObject> records = new ArrayList<>();
        if (!indexFile.exists()) return records;
        try {
            String content = Files.readString(indexFile.toPath(), StandardCharsets.UTF_8);
            JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
            for (var el : arr) {
                if (el.isJsonObject()) records.add(el.getAsJsonObject());
            }
        } catch (Exception e) {
            LOG.warn("Could not read sessions-index.json, starting fresh", e);
            try {
                File backup = new File(indexFile.getParentFile(),
                    indexFile.getName() + ".corrupt-" + System.currentTimeMillis());
                Files.copy(indexFile.toPath(), backup.toPath());
                LOG.info("Backed up corrupt index to: " + backup.getAbsolutePath());
            } catch (Exception backupErr) {
                LOG.debug("Could not back up corrupt index file", backupErr);
            }
        }
        return records;
    }
}
