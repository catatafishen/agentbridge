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

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ConversationStore v1Store = new ConversationStore();

    /**
     * Tracks the most recent async save so that {@link #awaitPendingSave(long)} can
     * block until the write completes before reading the v2 JSONL from disk.
     */
    private volatile CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);

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
            String id = rec.has(KEY_ID) ? rec.get(KEY_ID).getAsString() : null;
            if (id == null || id.isEmpty()) continue;
            String agent = rec.has(KEY_AGENT) ? rec.get(KEY_AGENT).getAsString() : "Unknown";
            String name = rec.has(KEY_NAME) ? rec.get(KEY_NAME).getAsString() : "";
            long createdAt = rec.has(KEY_CREATED_AT) ? rec.get(KEY_CREATED_AT).getAsLong() : 0;
            long updatedAt = rec.has(KEY_UPDATED_AT) ? rec.get(KEY_UPDATED_AT).getAsLong() : 0;
            int turnCount = rec.has(KEY_TURN_COUNT) ? rec.get(KEY_TURN_COUNT).getAsInt() : 0;
            result.add(new SessionRecord(id, agent, name, createdAt, updatedAt, turnCount));
        }
        result.sort(Comparator.comparingLong(SessionRecord::updatedAt).reversed());
        return result;
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

    private static final int MAX_SESSION_NAME_LENGTH = 60;

    static String truncateSessionName(@NotNull String promptText) {
        String name = promptText.replaceAll("\\s+", " ").trim();
        if (name.length() <= MAX_SESSION_NAME_LENGTH) return name;
        return name.substring(0, MAX_SESSION_NAME_LENGTH - 1) + "…";
    }

    /**
     * Appends {@code entries} to the current session JSONL without overwriting existing content.
     * Creates the file if it does not yet exist. The sessions index is updated incrementally:
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
        } catch (Exception e) {
            LOG.warn("Failed to append entries to v2 session JSONL", e);
        }
    }

    /**
     * Appends entries on a pooled thread (non-blocking).
     * The resulting future is tracked so that {@link #awaitPendingSave(long)} can wait
     * for the write to complete before reading the v2 JSONL from disk.
     */
    public void appendEntriesAsync(@Nullable String basePath, @NotNull List<EntryData> entries) {
        List<EntryData> snapshot = List.copyOf(entries);
        pendingSave = CompletableFuture.runAsync(
            () -> appendEntries(basePath, snapshot),
            AppExecutorUtil.getAppExecutorService());
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
     * Returns {@code null} if the session file does not exist or cannot be read.
     */
    @Nullable
    public List<EntryData> loadEntriesBySessionId(@Nullable String basePath, @NotNull String sessionId) {
        File dir = sessionsDir(basePath);
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
     * Loads entries directly from V2 JSONL file.
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
            LOG.warn("Could not read/write current-session-id, using cached transient UUID", e);
            if (transientSessionId == null) {
                transientSessionId = UUID.randomUUID().toString();
            }
            return transientSessionId;
        }
    }

    // ── v2 write ──────────────────────────────────────────────────────────────

    /**
     * Updates the sessions index when appending entries. Increments {@code turnCount} by
     * {@code additionalTurns} and sets the session name from the first prompt if not yet stored.
     * Creates a new index entry if one doesn't exist for this session.
     */
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
                rec.addProperty(KEY_UPDATED_AT, now);
                rec.addProperty(KEY_AGENT, agentName);
                if (additionalTurns > 0) {
                    int current = rec.has(KEY_TURN_COUNT) ? rec.get(KEY_TURN_COUNT).getAsInt() : 0;
                    rec.addProperty(KEY_TURN_COUNT, current + additionalTurns);
                }
                // Only set session name from the first prompt; never overwrite an existing name.
                if (!firstPromptText.isEmpty() && !rec.has(KEY_NAME)) {
                    rec.addProperty(KEY_NAME, firstPromptText);
                }
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

    // ── v2 read ───────────────────────────────────────────────────────────────

    /**
     * Parses a JSONL string with auto-detection: lines with {@code "type":} are the new
     * EntryData format (deserialized directly), lines with {@code "role":} are the old
     * legacy message format (batch-converted via {@link #convertLegacyMessages}).
     */
    @Nullable
    static List<EntryData> parseJsonlAutoDetect(@NotNull String content) {
        List<EntryData> directEntries = new ArrayList<>();
        List<JsonObject> legacyMessages = new ArrayList<>();
        int skippedLines = 0;

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonObject obj = GSON.fromJson(line, JsonObject.class);
                if (EntryDataJsonAdapter.isEntryFormat(line)) {
                    EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                    if (entry != null) directEntries.add(entry);
                } else {
                    if (obj != null) legacyMessages.add(obj);
                }
            } catch (Exception e) {
                skippedLines++;
                LOG.warn("Skipping malformed JSONL line: " + line, e);
            }
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
     * Converts a list of legacy JSONL message objects (with {@code role}, {@code parts},
     * {@code createdAt}, {@code agent}, {@code model} fields) into the current
     * {@link EntryData} model.
     */
    @NotNull
    static List<EntryData> convertLegacyMessages(@NotNull List<JsonObject> messages) {
        List<EntryData> result = new ArrayList<>();

        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            long createdAt = msg.has("createdAt") ? msg.get("createdAt").getAsLong() : 0;
            String agent = msg.has("agent") && !msg.get("agent").isJsonNull() ? msg.get("agent").getAsString() : null;
            String model = msg.has("model") && !msg.get("model").isJsonNull() ? msg.get("model").getAsString() : null;

            String ts = createdAt > 0
                ? java.time.Instant.ofEpochMilli(createdAt).toString()
                : "";

            if (EntryDataJsonAdapter.TYPE_SEPARATOR.equals(role)) {
                result.add(new EntryData.SessionSeparator(
                    ts,
                    agent != null ? agent : ""));
                continue;
            }

            JsonArray partsArray = msg.has("parts") ? msg.getAsJsonArray("parts") : new JsonArray();
            List<JsonObject> parts = new ArrayList<>();
            for (int i = 0; i < partsArray.size(); i++) {
                parts.add(partsArray.get(i).getAsJsonObject());
            }

            java.util.Set<Integer> consumedFileIndices = new java.util.HashSet<>();

            for (int idx = 0; idx < parts.size(); idx++) {
                JsonObject part = parts.get(idx);
                String type = part.has("type") ? part.get("type").getAsString() : "";

                switch (type) {
                    case EntryDataJsonAdapter.TYPE_TEXT -> {
                        String text = part.has("text") ? part.get("text").getAsString() : "";
                        String partTs = readLegacyTimestamp(part, ts);
                        String partEid = readLegacyEntryId(part);
                        if ("user".equals(role)) {
                            List<ContextFileRef> ctxFiles = collectLegacyFileParts(parts, idx + 1, consumedFileIndices);
                            result.add(new EntryData.Prompt(text, partTs,
                                ctxFiles.isEmpty() ? null : ctxFiles, "",
                                partEid));
                        } else {
                            result.add(new EntryData.Text(
                                text,
                                partTs,
                                agent != null ? agent : "",
                                model != null ? model : "",
                                partEid));
                        }
                    }
                    case "reasoning" -> {
                        String text = part.has("text") ? part.get("text").getAsString() : "";
                        String partTs = readLegacyTimestamp(part, ts);
                        String partEid = readLegacyEntryId(part);
                        result.add(new EntryData.Thinking(
                            text,
                            partTs,
                            agent != null ? agent : "",
                            model != null ? model : "",
                            partEid));
                    }
                    case "tool-invocation" -> {
                        JsonObject inv = part.has("toolInvocation") ? part.getAsJsonObject("toolInvocation") : new JsonObject();
                        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "";
                        String args = inv.has("args") && !inv.get("args").isJsonNull() ? inv.get("args").getAsString() : null;
                        String toolResult = inv.has("result") && !inv.get("result").isJsonNull() ? inv.get("result").getAsString() : null;
                        boolean autoDenied = inv.has("denialReason");
                        String denialReason = autoDenied ? inv.get("denialReason").getAsString() : null;
                        String kind = inv.has("kind") ? inv.get("kind").getAsString() : "other";
                        String toolStatus = inv.has("status") ? inv.get("status").getAsString() : null;
                        String toolDescription = inv.has("description") ? inv.get("description").getAsString() : null;
                        String filePath = inv.has("filePath") ? inv.get("filePath").getAsString() : null;
                        String pluginTool = inv.has("pluginTool") ? inv.get("pluginTool").getAsString() : null;
                        if (pluginTool == null && inv.has("mcpHandled") && inv.get("mcpHandled").getAsBoolean()) {
                            pluginTool = toolName; // best-effort from legacy
                        }
                        String partTs = readLegacyTimestamp(part, ts);
                        String partEid = readLegacyEntryId(part);
                        result.add(new EntryData.ToolCall(
                            toolName, args, kind, toolResult, toolStatus, toolDescription, filePath,
                            autoDenied, denialReason, pluginTool,
                            partTs, agent != null ? agent : "",
                            model != null ? model : "", partEid));
                    }
                    case EntryDataJsonAdapter.TYPE_SUBAGENT -> {
                        String agentType = part.has("agentType") ? part.get("agentType").getAsString() : "general-purpose";
                        String description = part.has("description") ? part.get("description").getAsString() : "";
                        String prompt = part.has("prompt") ? part.get("prompt").getAsString() : null;
                        String subResult = part.has("result") ? part.get("result").getAsString() : null;
                        String status = part.has("status") ? part.get("status").getAsString() : "completed";
                        int colorIndex = part.has("colorIndex") ? part.get("colorIndex").getAsInt() : 0;
                        String callId = part.has("callId") ? part.get("callId").getAsString() : null;
                        boolean autoDenied = part.has("autoDenied") && part.get("autoDenied").getAsBoolean();
                        String denialReason = part.has("denialReason") ? part.get("denialReason").getAsString() : null;
                        String partTs = readLegacyTimestamp(part, ts);
                        String partEid = readLegacyEntryId(part);
                        result.add(new EntryData.SubAgent(
                            agentType, description,
                            (prompt == null || prompt.isEmpty()) ? null : prompt,
                            (subResult == null || subResult.isEmpty()) ? null : subResult,
                            (status == null || status.isEmpty()) ? "completed" : status,
                            colorIndex, callId, autoDenied, denialReason,
                            partTs, agent != null ? agent : "",
                            model != null ? model : "", partEid));
                    }
                    case EntryDataJsonAdapter.TYPE_STATUS -> {
                        String icon = part.has("icon") ? part.get("icon").getAsString() : "ℹ";
                        String message = part.has("message") ? part.get("message").getAsString() : "";
                        String partEid = readLegacyEntryId(part);
                        result.add(new EntryData.Status(icon, message, partEid));
                    }
                    case "file" -> {
                        if (consumedFileIndices.contains(idx)) break;
                        String filename = part.has("filename") ? part.get("filename").getAsString() : "";
                        String path = part.has("path") ? part.get("path").getAsString() : "";
                        result.add(new EntryData.ContextFiles(List.of(new FileRef(filename, path))));
                    }
                    default -> {
                        // Unknown part type — skip for forward-compat
                    }
                }
            }

            // No trailing empty Text needed — appendAgentTurn().flushSegment() at loop end
            // handles tool-only assistant turns correctly.
        }

        return result;
    }

    // ── Legacy conversion helpers ─────────────────────────────────────────────

    /**
     * Reads a per-entry timestamp from a legacy V2 part, falling back to the message-level timestamp.
     */
    @NotNull
    private static String readLegacyTimestamp(@NotNull JsonObject part, @NotNull String messageLevelTs) {
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
    private static List<ContextFileRef> collectLegacyFileParts(
        List<JsonObject> parts, int startIdx, java.util.Set<Integer> consumed) {
        List<ContextFileRef> files = new ArrayList<>();
        for (int i = startIdx; i < parts.size(); i++) {
            JsonObject p = parts.get(i);
            String t = p.has("type") ? p.get("type").getAsString() : "";
            if (!"file".equals(t)) continue;
            String fn = p.has("filename") ? p.get("filename").getAsString() : "";
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
