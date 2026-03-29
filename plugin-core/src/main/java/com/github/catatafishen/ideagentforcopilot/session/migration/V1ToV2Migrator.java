package com.github.catatafishen.ideagentforcopilot.session.migration;

import com.github.catatafishen.ideagentforcopilot.session.v2.EntryDataConverter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.github.catatafishen.ideagentforcopilot.ui.ConversationSerializer;
import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
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
 * One-shot migration from v1 (conversation.json) to v2 (JSONL sessions).
 *
 * <p>Migration is skipped if {@code sessions-index.json} already exists.
 * Each logical session delimited by {@link EntryData.SessionSeparator} entries in the
 * v1 file becomes its own JSONL file.
 */
public final class V1ToV2Migrator {

    private static final Logger LOG = Logger.getInstance(V1ToV2Migrator.class);

    private static final String AGENT_WORK_DIR = ".agent-work";
    private static final String SESSIONS_DIR = "sessions";
    private static final String SESSIONS_INDEX = "sessions-index.json";
    private static final String CONVERSATIONS_DIR = "conversations";
    private static final String ARCHIVE_PREFIX = "conversation-";
    private static final String ARCHIVE_SUFFIX = ".json";
    private static final int MIN_VALID_SIZE = 10;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private V1ToV2Migrator() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Runs the migration if it hasn't been done yet.
     *
     * <p>Safe to call on every plugin startup — it is a no-op once
     * {@code sessions-index.json} exists.
     *
     * @param basePath absolute path to the project root, may be {@code null}
     */
    public static void migrateIfNeeded(@Nullable String basePath) {
        File sessionsDir = new File(agentWorkDir(basePath), SESSIONS_DIR);
        File indexFile = new File(sessionsDir, SESSIONS_INDEX);
        if (indexFile.exists()) return; // already migrated

        String v1Json = readBestV1Json(basePath);
        if (v1Json == null || v1Json.isBlank()) {
            // Nothing to migrate — just create an empty index so we don't retry
            writeEmptyIndex(sessionsDir, indexFile);
            return;
        }

        try {
            List<EntryData> allEntries = ConversationSerializer.INSTANCE.deserialize(v1Json);
            if (allEntries.isEmpty()) {
                writeEmptyIndex(sessionsDir, indexFile);
                return;
            }

            //noinspection ResultOfMethodCallIgnored  — best-effort
            sessionsDir.mkdirs();

            String directory = basePath != null ? basePath : "";
            List<JsonObject> indexRecords = new ArrayList<>();

            // Split allEntries by SessionSeparator
            List<List<EntryData>> sessions = splitBySeparator(allEntries);

            long now = System.currentTimeMillis();
            for (List<EntryData> session : sessions) {
                if (session.isEmpty()) continue;

                String sessionId = UUID.randomUUID().toString();
                List<SessionMessage> messages = EntryDataConverter.toMessages(session);

                // Write JSONL
                File jsonlFile = new File(sessionsDir, sessionId + ".jsonl");
                StringBuilder sb = new StringBuilder();
                for (SessionMessage msg : messages) {
                    sb.append(GSON.toJson(msg)).append('\n');
                }
                try {
                    Files.writeString(jsonlFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("Migration: failed to write JSONL for session " + sessionId, e);
                    continue;
                }

                // Build index record
                JsonObject rec = new JsonObject();
                rec.addProperty("id", sessionId);
                rec.addProperty("agent", "GitHub Copilot");
                rec.addProperty("directory", directory);
                rec.addProperty("createdAt", now);
                rec.addProperty("updatedAt", now);
                rec.addProperty("jsonlPath", sessionId + ".jsonl");
                indexRecords.add(rec);
            }

            // Write index
            JsonArray arr = new JsonArray();
            indexRecords.forEach(arr::add);
            Files.writeString(indexFile.toPath(), GSON.toJson(arr), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Persist the last session UUID so the store knows which file to continue
            if (!indexRecords.isEmpty()) {
                JsonObject last = indexRecords.get(indexRecords.size() - 1);
                String lastId = last.get("id").getAsString();
                File currentIdFile = new File(sessionsDir, ".current-session-id");
                Files.writeString(currentIdFile.toPath(), lastId, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            LOG.info("V1→V2 migration complete: " + indexRecords.size() + " sessions written to " + sessionsDir);

        } catch (Exception e) {
            LOG.warn("V1→V2 migration failed", e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Nullable
    private static String readBestV1Json(@Nullable String basePath) {
        // Try primary file
        File primary = new File(agentWorkDir(basePath), "conversation.json");
        if (primary.exists() && primary.length() >= MIN_VALID_SIZE) {
            try {
                return Files.readString(primary.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Migration: could not read conversation.json", e);
            }
        }
        // Fallback: most-recent archive
        File archivesDir = new File(agentWorkDir(basePath), CONVERSATIONS_DIR);
        if (!archivesDir.isDirectory()) return null;
        File[] archives = archivesDir.listFiles(
            (d, name) -> name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX));
        if (archives == null || archives.length == 0) return null;
        File latest = archives[0];
        for (File f : archives) {
            if (f.getName().compareTo(latest.getName()) > 0) latest = f;
        }
        if (latest.length() < MIN_VALID_SIZE) return null;
        try {
            return Files.readString(latest.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Migration: could not read archive", e);
            return null;
        }
    }

    /**
     * Splits entries into groups separated by {@link EntryData.SessionSeparator}.
     */
    @SuppressWarnings("java:S3776") // acceptable complexity for a linear split
    @org.jetbrains.annotations.NotNull
    private static List<List<EntryData>> splitBySeparator(@org.jetbrains.annotations.NotNull List<EntryData> entries) {
        List<List<EntryData>> sessions = new ArrayList<>();
        List<EntryData> current = new ArrayList<>();
        for (EntryData entry : entries) {
            if (entry instanceof EntryData.SessionSeparator) {
                if (!current.isEmpty()) {
                    sessions.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(entry);
            }
        }
        if (!current.isEmpty()) sessions.add(current);
        return sessions;
    }

    private static void writeEmptyIndex(@org.jetbrains.annotations.NotNull File sessionsDir,
                                        @org.jetbrains.annotations.NotNull File indexFile) {
        try {
            //noinspection ResultOfMethodCallIgnored  — best-effort
            sessionsDir.mkdirs();
            Files.writeString(indexFile.toPath(), "[]", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Could not write empty sessions-index.json", e);
        }
    }

    @org.jetbrains.annotations.NotNull
    private static File agentWorkDir(@Nullable String basePath) {
        return new File(basePath != null ? basePath : "", AGENT_WORK_DIR);
    }
}
