package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.services.hooks.HookStageResult;
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project-level facade for conversation persistence via SQLite.
 *
 * <p>Provides the public API for reading, writing, and managing conversation sessions.
 * Replaces the legacy {@code SessionStoreV2} facade which now only retains static
 * JSONL parsing utilities for the migration layer.
 *
 * <p>Callers obtain the singleton via {@link #getInstance(Project)}. Read operations
 * delegate to {@link ConversationReader}, write operations to {@link ConversationWriter},
 * both backed by the project's {@link ConversationDatabase}.
 */
@Service(Service.Level.PROJECT)
public final class ConversationService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ConversationService.class);

    private static final String CURRENT_SESSION_FILE = ".current-session-id";
    private static final int MAX_SESSION_NAME_LENGTH = 60;

    private final Project project;

    @SuppressWarnings("java:S3077") // volatile reference to immutable writer is safe for our use
    private volatile ConversationWriter conversationWriter;
    @SuppressWarnings("java:S3077") // volatile reference to immutable reader is safe for our use
    private volatile ConversationReader conversationReader;

    /**
     * Tracks the most recent async save so that {@link #awaitPendingSave(long)} can
     * block until the write completes before reading.
     */
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);
    private final Object saveLock = new Object();

    /**
     * Cached transient session ID used when the session-id file is unreadable (I/O error).
     */
    private volatile String transientSessionId;

    /**
     * Display name of the agent currently writing sessions (e.g. "GitHub Copilot").
     */
    private volatile String currentAgent = "Unknown";

    // ── Records ──────────────────────────────────────────────────────────────

    /**
     * Metadata record for an archived or active session.
     *
     * @param id        session UUID
     * @param agent     display name of the agent (e.g. "GitHub Copilot")
     * @param name      human-readable session name, empty if not set
     * @param createdAt epoch millis when the session was created
     * @param updatedAt epoch millis when the session was last updated
     * @param turnCount number of user turns in the session
     */
    public record SessionRecord(
        @NotNull String id,
        @NotNull String agent,
        @NotNull String name,
        long createdAt,
        long updatedAt,
        int turnCount) {
    }

    /**
     * Result of a tail-limited session load.
     *
     * @param entries       loaded entries in chronological order
     * @param hasMoreOnDisk {@code true} when older entries exist that were not loaded
     */
    public record RecentEntriesResult(
        @NotNull List<EntryData> entries,
        boolean hasMoreOnDisk) {
    }

    /**
     * A prompt entry paired with the session it belongs to and its optional turn statistics.
     *
     * @param sessionId the UUID of the session this prompt came from
     * @param prompt    the prompt entry
     * @param stats     the TurnStats immediately following the prompt, or {@code null}
     */
    public record PromptWithContext(
        @NotNull String sessionId,
        @NotNull EntryData.Prompt prompt,
        @Nullable EntryData.TurnStats stats) {
    }

    // ── Construction ─────────────────────────────────────────────────────────

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public ConversationService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Test constructor that injects a database directly, bypassing project lookup.
     */
    public ConversationService(@NotNull ConversationDatabase database) {
        this.project = null;
        this.conversationWriter = new ConversationWriter(database);
        this.conversationReader = new ConversationReader(database);
    }

    @NotNull
    public static ConversationService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ConversationService.class);
    }

    // ── Agent tracking ───────────────────────────────────────────────────────

    public void setCurrentAgent(@NotNull String agent) {
        this.currentAgent = agent;
    }

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Appends entries to the current session synchronously via SQLite.
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

    /**
     * Appends entries on a pooled thread (non-blocking).
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
     * Blocks until the most recent async append completes, or until timeout elapses.
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

    // ── Read operations ──────────────────────────────────────────────────────

    /**
     * Lists all sessions ordered by most recently updated first.
     */
    @NotNull
    public List<SessionRecord> listSessions() {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return List.of();
        List<ConversationReader.SessionRecord> dbSessions = reader.listSessions();
        List<SessionRecord> result = new ArrayList<>();
        for (ConversationReader.SessionRecord sr : dbSessions) {
            result.add(new SessionRecord(
                sr.id(),
                sr.agentName(),
                sr.displayName(),
                parseIsoToEpochMillis(sr.startedAt()),
                parseIsoToEpochMillis(sr.lastActivity()),
                sr.turnCount()
            ));
        }
        return result;
    }

    /**
     * Loads all entries for the current session.
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
     * Loads entries for a specific session by ID.
     */
    @Nullable
    public List<EntryData> loadEntriesBySessionId(@NotNull String sessionId) {
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
     * Loads the most recent entries from the current session.
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

    /**
     * Loads prompts and their associated turn statistics from all sessions.
     */
    @NotNull
    public List<PromptWithContext> loadPromptsFromAllSessions() {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return List.of();
        List<ConversationReader.PromptWithStats> dbPrompts = reader.loadAllPrompts();
        List<PromptWithContext> result = new ArrayList<>();
        for (ConversationReader.PromptWithStats p : dbPrompts) {
            result.add(new PromptWithContext(p.sessionId(), p.prompt(), p.stats()));
        }
        result.sort(java.util.Comparator.comparing(p -> p.prompt().getTimestamp()));
        return result;
    }

    // ── Session ID management ────────────────────────────────────────────────

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
            //noinspection ResultOfMethodCallIgnored
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

    /**
     * Signals that the current conversation session has ended.
     *
     * <p>The SQLite data and the {@code .current-session-id} file are deliberately left intact
     * so that {@link #loadRecentEntries} can restore them immediately after
     * {@code buildAndShowChatPanel()} calls {@code archiveConversation()}. Explicit deletion of
     * the session ID file is handled only by {@link #resetCurrentSessionId}, which callers must
     * invoke separately when a genuinely fresh session is required.
     */
    public void archive(@Nullable String basePath) {
        // Intentional no-op: the session ID file must survive for restoreConversation() to read.
    }

    /**
     * Deletes the {@code .current-session-id} file so the next call generates a fresh UUID.
     */
    public void resetCurrentSessionId(@Nullable String basePath) {
        File sessionIdFile = currentSessionIdFile(basePath);
        try {
            Files.deleteIfExists(sessionIdFile.toPath());
        } catch (IOException e) {
            LOG.warn("Could not delete current-session-id file", e);
        }
    }

    public void branchCurrentSession() {
        LOG.warn("branchCurrentSession: branch not yet supported with SQLite storage");
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    static String truncateSessionName(@NotNull String promptText) {
        String name = promptText.replaceAll("\\s+", " ").trim();
        if (name.length() <= MAX_SESSION_NAME_LENGTH) return name;
        return name.substring(0, MAX_SESSION_NAME_LENGTH - 1) + "…";
    }

    /**
     * Loads all entries for a single turn by its turn ID.
     */
    @NotNull
    public List<EntryData> loadTurnEntries(@NotNull String turnId) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return List.of();
        return reader.loadTurnEntries(turnId);
    }

    /**
     * Returns adjacent turn IDs for a session relative to a reference turn.
     * Negative count = earlier turns, positive = later turns.
     */
    @NotNull
    public List<String> loadAdjacentTurnIds(@NotNull String sessionId,
                                            @NotNull String referenceTurnId, int count) {
        ConversationReader reader = getOrCreateReader();
        if (reader == null) return List.of();
        return reader.loadAdjacentTurnIds(sessionId, referenceTurnId, count);
    }

    /**
     * Enriches an existing tool-call event row with performance stats.
     * Best-effort — silently returns if the writer is unavailable.
     */
    public void enrichToolCallStats(@NotNull String toolEventId, long inputSize, long outputSize,
                                    long durationMs, boolean success, @Nullable String errorMessage,
                                    @Nullable String category) {
        ConversationWriter writer = getOrCreateWriter();
        if (writer == null) return;
        writer.enrichToolCallStats(toolEventId, inputSize, outputSize, durationMs,
            success, errorMessage, category);
    }

    /**
     * Records hook execution stages for a tool call.
     * Best-effort — silently returns if the writer is unavailable.
     */
    public void recordHookStages(@NotNull String toolEventId,
                                 @NotNull List<HookStageResult> stages) {
        ConversationWriter writer = getOrCreateWriter();
        if (writer == null) return;
        writer.recordHookStages(toolEventId, stages);
    }

    @Override
    public void dispose() {
        awaitPendingSave(3_000);
    }

    // ── Private ──────────────────────────────────────────────────────────────

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

    @NotNull
    @SuppressWarnings("deprecation")
    private static File currentSessionIdFile(@Nullable String basePath) {
        return new File(ExportUtils.sessionsDir(basePath), CURRENT_SESSION_FILE);
    }

    private static long parseIsoToEpochMillis(@Nullable String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isEmpty()) return 0;
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
