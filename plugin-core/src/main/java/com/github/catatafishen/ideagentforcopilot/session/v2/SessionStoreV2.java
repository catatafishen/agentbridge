package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.bridge.ConversationStore;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.ui.ConversationSerializer;
import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project-level singleton that manages v2 JSONL session persistence.
 *
 * <p>Also writes v1 JSON via {@link ConversationStore} for backward compatibility.
 *
 * <p>Directory layout:
 * <pre>
 * &lt;projectBase&gt;/.agent-work/
 *   conversation.json          ← v1 (still written for backward compat)
 *   conversations/             ← v1 archives
 *   sessions/
 *     sessions-index.json      ← JSON array of session metadata objects
 *     &lt;uuid&gt;.jsonl             ← one file per session
 * </pre>
 */
public final class SessionStoreV2 implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionStoreV2.class);

    private static final String SESSIONS_DIR = "sessions";
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

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ConversationStore v1Store = new ConversationStore();

    /**
     * Tracks the most recent async save so that {@link #awaitPendingSave(long)} can
     * block until the write completes before reading the v2 JSONL from disk.
     */
    private volatile CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);

    /**
     * Display name of the agent currently writing sessions (e.g. "GitHub Copilot").
     */
    private volatile String currentAgent = "Unknown";

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
     * @param createdAt epoch millis when the session was created
     * @param updatedAt epoch millis when the session was last updated
     * @param turnCount number of user turns in the session (0 if unknown / old index entry)
     */
    public record SessionRecord(
        @NotNull String id,
        @NotNull String agent,
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
            String id = rec.has(KEY_ID) ? rec.get(KEY_ID).getAsString() : null;
            if (id == null || id.isEmpty()) continue;
            String agent = rec.has(KEY_AGENT) ? rec.get(KEY_AGENT).getAsString() : "Unknown";
            long createdAt = rec.has(KEY_CREATED_AT) ? rec.get(KEY_CREATED_AT).getAsLong() : 0;
            long updatedAt = rec.has(KEY_UPDATED_AT) ? rec.get(KEY_UPDATED_AT).getAsLong() : 0;
            int turnCount = rec.has(KEY_TURN_COUNT) ? rec.get(KEY_TURN_COUNT).getAsInt() : 0;
            result.add(new SessionRecord(id, agent, createdAt, updatedAt, turnCount));
        }
        result.sort(Comparator.comparingLong(SessionRecord::updatedAt).reversed());
        return result;
    }

    // ── v1 delegation ─────────────────────────────────────────────────────────

    /**
     * Returns the v1 conversation file (delegates to {@link ConversationStore}).
     */
    @NotNull
    public File conversationFile(@Nullable String basePath) {
        return v1Store.conversationFile(basePath);
    }

    /**
     * Returns the v1 archives directory (delegates to {@link ConversationStore}).
     */
    @NotNull
    public File archivesDir(@Nullable String basePath) {
        return v1Store.archivesDir(basePath);
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

    /**
     * Snapshots the current session JSONL as a new immutable branch entry in the sessions index.
     * Called at agent startup when "Branch session at startup" is enabled.
     *
     * <p>The branch is a verbatim copy of the current JSONL assigned a new UUID and registered
     * in the sessions index with a {@code "(branch)"} label. It appears in the session history
     * picker so the user can reload it.</p>
     *
     * @param basePath project base path (may be null)
     */
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

        File sourceFile = new File(sessionsDir, sessionId + JSONL_EXT);
        if (!sourceFile.exists() || sourceFile.length() < 2) {
            LOG.info("branchCurrentSession: no JSONL data to snapshot (session " + sessionId + ")");
            return;
        }

        String branchId = UUID.randomUUID().toString();
        File branchFile = new File(sessionsDir, branchId + JSONL_EXT);

        try {
            Files.createDirectories(sessionsDir.toPath());
            Files.copy(sourceFile.toPath(), branchFile.toPath());

            File indexFile = new File(sessionsDir, SESSIONS_INDEX);
            List<JsonObject> records = readIndexRecords(indexFile);

            int turnCount = 0;
            for (JsonObject rec : records) {
                if (rec.has(KEY_ID) && sessionId.equals(rec.get(KEY_ID).getAsString())) {
                    if (rec.has(KEY_TURN_COUNT)) turnCount = rec.get(KEY_TURN_COUNT).getAsInt();
                    break;
                }
            }

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

            LOG.info("Branched session " + sessionId + " → " + branchId + " at " + timestamp);
        } catch (IOException e) {
            LOG.warn("Failed to branch session " + sessionId, e);
        }
    }

    /**
     * @deprecated Use {@link #saveEntries} instead. Kept for V1ToV2Migrator compatibility.
     */
    @Deprecated
    public void save(@Nullable String basePath, @NotNull String json) {
        var entries = ConversationSerializer.INSTANCE.deserialize(json);
        saveEntries(basePath, entries);
    }

    public void saveEntries(@Nullable String basePath, @NotNull List<EntryData> entries) {
        try {
            String agent = currentAgent;

            String sessionId = getCurrentSessionId(basePath);
            File dir = sessionsDir(basePath);
            //noinspection ResultOfMethodCallIgnored  — best-effort
            dir.mkdirs();

            File jsonlFile = new File(dir, sessionId + JSONL_EXT);
            StringBuilder sb = new StringBuilder();
            int turnCount = 0;
            for (EntryData entry : entries) {
                sb.append(GSON.toJson(EntryDataJsonAdapter.serialize(entry))).append('\n');
                if (entry instanceof EntryData.Prompt) turnCount++;
            }
            Files.writeString(jsonlFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            updateSessionsIndex(basePath, sessionId, dir, jsonlFile.getName(), agent, turnCount);
        } catch (Exception e) {
            LOG.warn("Failed to write v2 session JSONL", e);
        }
    }

    /**
     * Saves the conversation on a pooled thread (non-blocking), directly from EntryData.
     * The resulting future is tracked so that {@link #awaitPendingSave(long)} can wait
     * for the write to complete before reading the v2 JSONL from disk.
     */
    public void saveEntriesAsync(@Nullable String basePath, @NotNull List<EntryData> entries) {
        // Snapshot the list to avoid concurrent modification
        List<EntryData> snapshot = List.copyOf(entries);
        pendingSave = CompletableFuture.runAsync(
            () -> saveEntries(basePath, snapshot),
            AppExecutorUtil.getAppExecutorService());
    }

    /**
     * @deprecated Use {@link #saveEntriesAsync} instead. Kept for backward compatibility.
     */
    @Deprecated
    public void saveAsync(@Nullable String basePath, @NotNull String json) {
        pendingSave = CompletableFuture.runAsync(
            () -> save(basePath, json),
            AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Blocks until the most recent {@link #saveAsync} call completes, or until
     * {@code timeoutMs} elapses. Safe to call when no save is pending — returns immediately.
     *
     * <p>Call this before reading the v2 JSONL from disk to ensure the latest conversation
     * state has been flushed.
     *
     * @param timeoutMs maximum wait in milliseconds
     */
    public void awaitPendingSave(long timeoutMs) {
        CompletableFuture<Void> future = pendingSave;
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
     * @deprecated Use {@link #loadEntries} instead. Kept for V1ToV2Migrator compatibility.
     */
    @Deprecated
    @Nullable
    public String loadJson(@Nullable String basePath) {
        try {
            String v2Json = loadFromV2(basePath);
            if (v2Json != null) return v2Json;
        } catch (Exception e) {
            LOG.warn("Failed to load conversation from v2 format, falling back to v1", e);
        }
        return v1Store.loadJson(basePath);
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
     * Loads entries directly from V2 JSONL file.
     * Auto-detects format per line: {@code "type":} → new EntryData format,
     * {@code "role":} → old SessionMessage format (converted via fromMessages).
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

        File jsonlFile = new File(dir, sessionId + JSONL_EXT);
        if (!jsonlFile.exists() || jsonlFile.length() < 2) return null;

        try {
            String content = Files.readString(jsonlFile.toPath(), StandardCharsets.UTF_8);
            return parseJsonlAutoDetect(content);
        } catch (Exception e) {
            LOG.warn("Failed to parse v2 JSONL for session " + sessionId, e);
            return null;
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
            LOG.warn("Could not read/write current-session-id, using transient UUID", e);
            return UUID.randomUUID().toString();
        }
    }

    // ── v2 write ──────────────────────────────────────────────────────────────

    private void updateSessionsIndex(
        @Nullable String basePath,
        @NotNull String sessionId,
        @NotNull File sessionsDir,
        @NotNull String jsonlFileName,
        @NotNull String agentName,
        int turnCount) throws IOException {

        File indexFile = new File(sessionsDir, SESSIONS_INDEX);
        List<JsonObject> records = readIndexRecords(indexFile);

        long now = System.currentTimeMillis();
        String directory = basePath != null ? basePath : "";

        boolean found = false;
        for (JsonObject rec : records) {
            if (rec.has(KEY_ID) && sessionId.equals(rec.get(KEY_ID).getAsString())) {
                rec.addProperty(KEY_UPDATED_AT, now);
                rec.addProperty(KEY_AGENT, agentName);
                rec.addProperty(KEY_TURN_COUNT, turnCount);
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
            newRec.addProperty(KEY_TURN_COUNT, turnCount);
            records.add(newRec);
        }

        JsonArray arr = new JsonArray();
        records.forEach(arr::add);
        Files.writeString(indexFile.toPath(), GSON.toJson(arr), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Updates the sessions index for a given session ID with the specified agent name
     * and current timestamp. Creates a new index entry if one doesn't exist.
     * Called by {@link com.github.catatafishen.ideagentforcopilot.session.SessionSwitchService}
     * when importing sessions from other agents to keep the index agent label accurate.
     *
     * @param basePath  project base path (may be null)
     * @param sessionId the session UUID to update
     * @param agentName display name of the agent (e.g. "Claude Code CLI")
     */
    public void updateSessionAgent(@Nullable String basePath, @NotNull String sessionId, @NotNull String agentName) {
        try {
            File sessionsDir = sessionsDir(basePath);
            File indexFile = new File(sessionsDir, SESSIONS_INDEX);
            List<JsonObject> records = readIndexRecords(indexFile);
            long now = System.currentTimeMillis();

            boolean found = false;
            for (JsonObject rec : records) {
                if (rec.has(KEY_ID) && sessionId.equals(rec.get(KEY_ID).getAsString())) {
                    rec.addProperty(KEY_UPDATED_AT, now);
                    rec.addProperty(KEY_AGENT, agentName);
                    found = true;
                    break;
                }
            }
            if (!found) {
                JsonObject newRec = new JsonObject();
                newRec.addProperty(KEY_ID, sessionId);
                newRec.addProperty(KEY_AGENT, agentName);
                newRec.addProperty(KEY_DIRECTORY, basePath != null ? basePath : "");
                newRec.addProperty(KEY_CREATED_AT, now);
                newRec.addProperty(KEY_UPDATED_AT, now);
                newRec.addProperty(KEY_JSONL_PATH, sessionId + JSONL_EXT);
                records.add(newRec);
            }

            JsonArray arr = new JsonArray();
            records.forEach(arr::add);
            Files.writeString(indexFile.toPath(), GSON.toJson(arr), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to update session index for sessionId=" + sessionId, e);
        }
    }

    // ── v2 read ───────────────────────────────────────────────────────────────

    @Nullable
    private String loadFromV2(@Nullable String basePath) {
        List<EntryData> entries = loadEntriesFromV2(basePath);
        if (entries == null || entries.isEmpty()) return null;
        return ConversationSerializer.INSTANCE.serialize(entries);
    }

    /**
     * Parses a JSONL string with auto-detection: lines with {@code "type":} are the new
     * EntryData format (deserialized directly), lines with {@code "role":} are the old
     * SessionMessage format (batch-converted via {@link EntryDataConverter#fromMessages}).
     */
    @Nullable
    private List<EntryData> parseJsonlAutoDetect(@NotNull String content) {
        List<EntryData> directEntries = new ArrayList<>();
        List<SessionMessage> legacyMessages = new ArrayList<>();

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                if (EntryDataJsonAdapter.isEntryFormat(line)) {
                    JsonObject obj = GSON.fromJson(line, JsonObject.class);
                    EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                    if (entry != null) directEntries.add(entry);
                } else {
                    SessionMessage msg = GSON.fromJson(line, SessionMessage.class);
                    if (msg != null) legacyMessages.add(msg);
                }
            } catch (Exception e) {
                LOG.warn("Skipping malformed JSONL line: " + line, e);
            }
        }

        if (!directEntries.isEmpty()) return directEntries;
        if (!legacyMessages.isEmpty()) return EntryDataConverter.fromMessages(legacyMessages);
        return null;
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

    @NotNull
    private static File sessionsDir(@Nullable String basePath) {
        String base = basePath != null ? basePath : "";
        return new File(base + "/.agent-work/" + SESSIONS_DIR);
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
        }
        return records;
    }
}
