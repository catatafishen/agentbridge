package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.bridge.ConversationStore;
import com.github.catatafishen.ideagentforcopilot.ui.ConversationSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drop-in replacement for {@link ConversationStore} that additionally writes v2 JSONL sessions.
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
public final class SessionStoreV2 {

    private static final Logger LOG = Logger.getInstance(SessionStoreV2.class);

    private static final String SESSIONS_DIR = "sessions";
    private static final String SESSIONS_INDEX = "sessions-index.json";
    private static final String CURRENT_SESSION_FILE = ".current-session-id";

    private static final String KEY_ID = "id";
    private static final String KEY_AGENT = "agent";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_JSONL_PATH = "jsonlPath";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ConversationStore v1Store = new ConversationStore();

    /**
     * Display name of the agent currently writing sessions (e.g. "GitHub Copilot").
     */
    private volatile String currentAgent = "Unknown";

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
     */
    public record SessionRecord(
        @NotNull String id,
        @NotNull String agent,
        long createdAt,
        long updatedAt) {
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
            result.add(new SessionRecord(id, agent, createdAt, updatedAt));
        }
        result.sort(java.util.Comparator.comparingLong(SessionRecord::updatedAt).reversed());
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
     * {@link ConversationStore#archive(String)} for v1, then starts a fresh session UUID.
     */
    public void archive(@Nullable String basePath) {
        finaliseCurrentSession(basePath);
        v1Store.archive(basePath);
        // Reset current session ID so the next write gets a fresh UUID
        File sessionIdFile = currentSessionIdFile(basePath);
        try {
            Files.deleteIfExists(sessionIdFile.toPath());
        } catch (IOException e) {
            LOG.warn("Could not delete current-session-id file", e);
        }
    }

    /**
     * Saves the conversation synchronously.
     * Writes v1 JSON via {@link ConversationStore} and also rewrites the v2 JSONL.
     */
    public void save(@Nullable String basePath, @NotNull String json) {
        v1Store.save(basePath, json);
        saveV2(basePath, json);
    }

    /**
     * Saves the conversation on a pooled thread (non-blocking).
     */
    public void saveAsync(@Nullable String basePath, @NotNull String json) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> save(basePath, json));
    }

    /**
     * Loads the conversation JSON.
     * Tries to reconstruct v1-compatible JSON from the v2 JSONL first; falls back to
     * {@link ConversationStore#loadJson(String)} if v2 is absent or unreadable.
     */
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

    private void saveV2(@Nullable String basePath, @NotNull String v1Json) {
        try {
            var entries = ConversationSerializer.INSTANCE.deserialize(v1Json);
            List<SessionMessage> messages = EntryDataConverter.toMessages(entries);

            String sessionId = getCurrentSessionId(basePath);
            File sessionsDir = sessionsDir(basePath);
            //noinspection ResultOfMethodCallIgnored  — best-effort
            sessionsDir.mkdirs();

            // Write JSONL (rewrite whole file)
            File jsonlFile = new File(sessionsDir, sessionId + ".jsonl");
            StringBuilder sb = new StringBuilder();
            for (SessionMessage msg : messages) {
                sb.append(GSON.toJson(msg)).append('\n');
            }
            Files.writeString(jsonlFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Update sessions index
            updateSessionsIndex(basePath, sessionId, sessionsDir, jsonlFile.getName());

        } catch (Exception e) {
            LOG.warn("Failed to write v2 session JSONL", e);
        }
    }

    private void updateSessionsIndex(
        @Nullable String basePath,
        @NotNull String sessionId,
        @NotNull File sessionsDir,
        @NotNull String jsonlFileName) throws IOException {

        File indexFile = new File(sessionsDir, SESSIONS_INDEX);
        List<JsonObject> records = readIndexRecords(indexFile);

        long now = System.currentTimeMillis();
        String directory = basePath != null ? basePath : "";

        boolean found = false;
        for (JsonObject rec : records) {
            if (rec.has(KEY_ID) && sessionId.equals(rec.get(KEY_ID).getAsString())) {
                rec.addProperty(KEY_UPDATED_AT, now);
                rec.addProperty(KEY_AGENT, currentAgent);
                found = true;
                break;
            }
        }
        if (!found) {
            JsonObject newRec = new JsonObject();
            newRec.addProperty(KEY_ID, sessionId);
            newRec.addProperty(KEY_AGENT, currentAgent);
            newRec.addProperty("directory", directory);
            newRec.addProperty(KEY_CREATED_AT, now);
            newRec.addProperty(KEY_UPDATED_AT, now);
            newRec.addProperty(KEY_JSONL_PATH, jsonlFileName);
            records.add(newRec);
        }

        JsonArray arr = new JsonArray();
        records.forEach(arr::add);
        Files.writeString(indexFile.toPath(), GSON.toJson(arr), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ── v2 read ───────────────────────────────────────────────────────────────

    @Nullable
    private String loadFromV2(@Nullable String basePath) {
        File sessionsDir = sessionsDir(basePath);
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

        File jsonlFile = new File(sessionsDir, sessionId + ".jsonl");
        if (!jsonlFile.exists() || jsonlFile.length() < 2) return null;

        try {
            String content = Files.readString(jsonlFile.toPath(), StandardCharsets.UTF_8);
            List<SessionMessage> messages = parseJsonl(content);
            if (messages.isEmpty()) return null;
            var entries = EntryDataConverter.fromMessages(messages);
            return ConversationSerializer.INSTANCE.serialize(entries);
        } catch (Exception e) {
            LOG.warn("Failed to parse v2 JSONL for session " + sessionId, e);
            return null;
        }
    }

    @NotNull
    private static List<SessionMessage> parseJsonl(@NotNull String content) {
        List<SessionMessage> messages = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                SessionMessage msg = GSON.fromJson(line, SessionMessage.class);
                if (msg != null) messages.add(msg);
            } catch (Exception e) {
                LOG.warn("Skipping malformed JSONL line: " + line, e);
            }
        }
        return messages;
    }

    // ── session finalisation ──────────────────────────────────────────────────

    private void finaliseCurrentSession(@Nullable String basePath) { // basePath reserved for future session finalization logic
        // Nothing special needed — the JSONL is already up-to-date.
        // This is a hook for future use (e.g. writing a "closed" marker).
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
