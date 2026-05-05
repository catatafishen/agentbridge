package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.v2.EntryDataJsonAdapter;
import com.github.catatafishen.agentbridge.session.v2.SessionFileRotation;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports legacy JSONL session files into {@link ConversationDatabase}.
 *
 * <p>Reads all JSONL files from the configured sessions directory, parses each
 * line via {@link EntryDataJsonAdapter#deserialize}, and writes the resulting
 * {@link EntryData} batches to SQLite via {@link ConversationWriter}. Already-
 * migrated sessions are skipped (INSERT OR IGNORE semantics in the writer).
 *
 * <p>This class is fully testable without IntelliJ APIs — the core migration
 * logic operates on a sessions directory path and a writer instance. The static
 * convenience method {@link #migrateIfNeeded(Project)} resolves paths from the
 * platform and delegates to {@link #migrate(Path, ConversationWriter)}.
 */
public final class JsonlToSqliteMigrator {

    private static final Logger LOG = Logger.getInstance(JsonlToSqliteMigrator.class);

    private JsonlToSqliteMigrator() {
    }

    /**
     * Migrates all JSONL session files for the given project into the
     * {@link ConversationDatabase}. Returns silently if there is nothing to migrate
     * or if the database is not yet initialised.
     */
    public static void migrateIfNeeded(@NotNull Project project) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        if (!db.isReady()) {
            try {
                db.initialize();
            } catch (Exception e) {
                LOG.warn("JsonlToSqliteMigrator: DB initialization failed, skipping migration", e);
                return;
            }
        }
        Path sessionsDir = ExportUtils.sessionsDir(project).toPath();
        if (!Files.isDirectory(sessionsDir)) {
            LOG.debug("JsonlToSqliteMigrator: no sessions directory at " + sessionsDir);
            return;
        }
        ConversationWriter writer = new ConversationWriter(db);
        migrate(sessionsDir, writer);
    }

    /**
     * Core migration logic — reads JSONL files from {@code sessionsDir} and writes
     * to SQLite via {@code writer}. Testable without any IntelliJ platform API.
     *
     * @param sessionsDir directory containing {@code sessions-index.json} and JSONL files
     * @param writer      the SQLite writer to use
     * @return the number of sessions successfully migrated
     */
    public static int migrate(@NotNull Path sessionsDir, @NotNull ConversationWriter writer) {
        List<SessionInfo> sessions = discoverSessions(sessionsDir);
        if (sessions.isEmpty()) {
            LOG.debug("JsonlToSqliteMigrator: no sessions to migrate in " + sessionsDir);
            return 0;
        }

        int migrated = 0;
        for (SessionInfo session : sessions) {
            try {
                if (migrateSession(sessionsDir, session, writer)) {
                    migrated++;
                }
            } catch (Exception e) {
                LOG.warn("JsonlToSqliteMigrator: failed to migrate session " + session.id, e);
            }
        }
        LOG.info("JsonlToSqliteMigrator: migrated " + migrated + "/" + sessions.size() + " sessions");
        return migrated;
    }

    /**
     * Migrates a single session. Returns true if entries were written.
     */
    private static boolean migrateSession(
        @NotNull Path sessionsDir,
        @NotNull SessionInfo session,
        @NotNull ConversationWriter writer
    ) {
        List<Path> files = SessionFileRotation.listAllFiles(sessionsDir.toFile(), session.id);
        if (files.isEmpty()) return false;

        List<EntryData> entries = new ArrayList<>();
        for (Path file : files) {
            parseJsonlFile(file, entries);
        }
        if (entries.isEmpty()) return false;

        writer.recordEntries(session.id, session.agent, "", entries);
        return true;
    }

    /**
     * Parses a JSONL file into EntryData entries, skipping malformed lines.
     */
    static void parseJsonlFile(@NotNull Path file, @NotNull List<EntryData> entries) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                parseOneLine(line, file, entries);
            }
        } catch (IOException e) {
            LOG.warn("JsonlToSqliteMigrator: failed to read file " + file, e);
        }
    }

    private static void parseOneLine(@NotNull String line, @NotNull Path file,
                                     @NotNull List<EntryData> entries) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (EntryDataJsonAdapter.isEntryFormat(line)) {
                EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                if (entry != null) entries.add(entry);
            }
        } catch (Exception e) {
            LOG.debug("JsonlToSqliteMigrator: skipped malformed line in " + file.getFileName());
        }
    }

    /**
     * Discovers sessions by reading the sessions-index.json file.
     * Falls back to scanning for .jsonl files if the index is missing.
     */
    @NotNull
    static List<SessionInfo> discoverSessions(@NotNull Path sessionsDir) {
        Path indexFile = sessionsDir.resolve("sessions-index.json");
        if (Files.isRegularFile(indexFile)) {
            return readSessionsFromIndex(indexFile);
        }
        return scanForSessionFiles(sessionsDir);
    }

    @NotNull
    private static List<SessionInfo> readSessionsFromIndex(@NotNull Path indexFile) {
        List<SessionInfo> result = new ArrayList<>();
        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);
            JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
            for (var elem : arr) {
                JsonObject rec = elem.getAsJsonObject();
                String id = rec.has("id") ? rec.get("id").getAsString() : null;
                if (id == null || id.isEmpty()) continue;
                String agent = rec.has("agent") ? rec.get("agent").getAsString() : "Unknown";
                result.add(new SessionInfo(id, agent));
            }
        } catch (Exception e) {
            LOG.warn("JsonlToSqliteMigrator: failed to read sessions-index.json", e);
        }
        return result;
    }

    @NotNull
    private static List<SessionInfo> scanForSessionFiles(@NotNull Path sessionsDir) {
        List<SessionInfo> result = new ArrayList<>();
        try (var stream = Files.list(sessionsDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                .filter(p -> !p.getFileName().toString().contains(".part-"))
                .forEach(p -> {
                    String filename = p.getFileName().toString();
                    String id = filename.substring(0, filename.length() - ".jsonl".length());
                    result.add(new SessionInfo(id, "Unknown"));
                });
        } catch (IOException e) {
            LOG.warn("JsonlToSqliteMigrator: failed to scan sessions directory", e);
        }
        return result;
    }

    /**
     * Minimal session info needed for migration.
     */
    record SessionInfo(@NotNull String id, @NotNull String agent) {
    }
}
