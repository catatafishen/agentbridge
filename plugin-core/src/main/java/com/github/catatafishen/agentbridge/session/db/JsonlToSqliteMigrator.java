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
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    public static final String BACKUP_DIRNAME = "sessions-backup-jsonl";
    private static final String JSONL_EXT = ".jsonl";
    private static final String BACKUP_README = """
        This directory contains backups of your session history in the legacy JSONL format.
        They were automatically moved here after a successful migration to the SQLite database.

        These files are no longer needed and can be safely deleted.
        """;

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

    public static int migrate(@NotNull Path sessionsDir, @NotNull ConversationWriter writer) {
        List<SessionInfo> sessions = discoverSessions(sessionsDir);
        if (sessions.isEmpty()) {
            LOG.debug("JsonlToSqliteMigrator: no sessions to migrate in " + sessionsDir);
            return 0;
        }

        Path backupDir = sessionsDir.resolveSibling(BACKUP_DIRNAME);
        boolean readmeWritten = false;

        int migrated = 0;
        for (SessionInfo session : sessions) {
            try {
                if (migrateSession(sessionsDir, session, writer)) {
                    migrated++;
                    if (!readmeWritten) {
                        writeBackupReadme(backupDir);
                        readmeWritten = true;
                    }
                    moveSessionFilesToBackup(sessionsDir, session, backupDir);
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

    private static void writeBackupReadme(@NotNull Path backupDir) {
        try {
            Files.createDirectories(backupDir);
            Files.writeString(backupDir.resolve("README.txt"), BACKUP_README, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("JsonlToSqliteMigrator: could not write backup README", e);
        }
    }

    private static void moveSessionFilesToBackup(
        @NotNull Path sessionsDir,
        @NotNull SessionInfo session,
        @NotNull Path backupDir
    ) {
        List<Path> files = SessionFileRotation.listAllFiles(sessionsDir.toFile(), session.id);
        for (Path file : files) {
            try {
                Path dest = backupDir.resolve(sessionsDir.relativize(file));
                Files.createDirectories(dest.getParent());
                Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("JsonlToSqliteMigrator: could not move " + file + " to backup", e);
            }
        }
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

    // ── Legacy role/parts format ──────────────────────────────────────────────

    private static void parseOneLine(@NotNull String line, @NotNull Path file,
                                     @NotNull List<EntryData> entries) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (EntryDataJsonAdapter.isEntryFormat(obj)) {
                EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                if (entry != null) entries.add(entry);
            } else if (obj.has(LEGACY_KEY_ROLE) && obj.has(LEGACY_KEY_PARTS)) {
                parseLegacyMessage(obj, entries);
            }
        } catch (Exception e) {
            LOG.debug("JsonlToSqliteMigrator: skipped malformed line in " + file.getFileName());
        }
    }

    private static final String LEGACY_KEY_ROLE = "role";
    private static final String LEGACY_KEY_AGENT = "agent";
    private static final String LEGACY_KEY_PARTS = "parts";
    private static final String LEGACY_KEY_CREATED_AT = "createdAt";
    private static final String LEGACY_KEY_TOOL_INVOCATION = "toolInvocation";
    private static final String LEGACY_KEY_RESULT = "result";
    private static final String LEGACY_KEY_STATE = "state";
    private static final String LEGACY_KEY_TOOL_NAME = "toolName";

    /**
     * Parses a legacy role/parts format message into one or more {@link EntryData} entries.
     *
     * <p>The legacy format predates the type-discriminated entry format. Each message line
     * has the structure:
     * <pre>{@code {"id":"...","role":"user"|"assistant","parts":[{"type":"text","text":"..."},...],"createdAt":millis}}</pre>
     *
     * <p>Each part is mapped to the nearest {@link EntryData} equivalent:
     * <ul>
     *   <li>{@code user/text} → {@link EntryData.Prompt}
     *   <li>{@code assistant/text} → {@link EntryData.Text}
     *   <li>{@code assistant/reasoning} → {@link EntryData.Thinking}
     *   <li>{@code assistant/tool-invocation} → {@link EntryData.ToolCall}
     *   <li>{@code assistant/subagent} → {@link EntryData.SubAgent}
     * </ul>
     *
     * <p>Parts with an unrecognised type are silently skipped.
     */
    static void parseLegacyMessage(@NotNull JsonObject obj, @NotNull List<EntryData> entries) {
        String role = legacyStr(obj, LEGACY_KEY_ROLE);
        String msgId = obj.has("id") ? obj.get("id").getAsString() : UUID.randomUUID().toString();
        String agent = legacyStr(obj, LEGACY_KEY_AGENT);
        long createdAt = obj.has(LEGACY_KEY_CREATED_AT) ? obj.get(LEGACY_KEY_CREATED_AT).getAsLong() : 0L;
        String timestamp = createdAt > 0 ? Instant.ofEpochMilli(createdAt).toString() : "";

        if (!obj.has(LEGACY_KEY_PARTS) || !obj.get(LEGACY_KEY_PARTS).isJsonArray()) return;
        JsonArray parts = obj.getAsJsonArray(LEGACY_KEY_PARTS);

        for (int i = 0; i < parts.size(); i++) {
            if (!parts.get(i).isJsonObject()) continue;
            JsonObject part = parts.get(i).getAsJsonObject();
            // Multi-part messages get per-part entryIds derived from the message id.
            String entryId = parts.size() == 1 ? msgId : msgId + "-p" + i;
            EntryData entry = convertLegacyPart(role, part, entryId, timestamp, agent);
            if (entry != null) entries.add(entry);
        }
    }

    @Nullable
    private static EntryData convertLegacyPart(
        @NotNull String role,
        @NotNull JsonObject part,
        @NotNull String entryId,
        @NotNull String timestamp,
        @NotNull String agent
    ) {
        String partType = legacyStr(part, "type");
        String text = legacyStr(part, "text");
        return switch (partType) {
            case "text" -> "user".equals(role)
                ? new EntryData.Prompt(text, timestamp, null, "", entryId)
                : new EntryData.Text(text, timestamp, agent, "", entryId);
            case "reasoning" -> new EntryData.Thinking(text, timestamp, agent, "", entryId);
            case "tool-invocation" -> convertLegacyToolInvocation(part, timestamp, agent, entryId);
            case "subagent" -> new EntryData.SubAgent(
                legacyStr(part, "agentType"),
                legacyStr(part, "description"),
                legacyStrOrNull(part, "prompt"),
                legacyStrOrNull(part, LEGACY_KEY_RESULT),
                legacyStrOrNull(part, "status"),
                part.has("colorIndex") ? part.get("colorIndex").getAsInt() : 0,
                null, false, null,
                timestamp, agent, "", entryId);
            default -> null;
        };
    }

    @Nullable
    private static EntryData.ToolCall convertLegacyToolInvocation(
        @NotNull JsonObject part, @NotNull String timestamp,
        @NotNull String agent, @NotNull String entryId
    ) {
        if (!part.has(LEGACY_KEY_TOOL_INVOCATION) || !part.get(LEGACY_KEY_TOOL_INVOCATION).isJsonObject()) {
            return null;
        }
        JsonObject ti = part.getAsJsonObject(LEGACY_KEY_TOOL_INVOCATION);
        return new EntryData.ToolCall(
            legacyStr(ti, LEGACY_KEY_TOOL_NAME),
            legacyStrOrNull(ti, "args"),
            "", // kind unknown in legacy format
            legacyStrOrNull(ti, LEGACY_KEY_RESULT),
            legacyStrOrNull(ti, LEGACY_KEY_STATE),
            "", null, false, null, null,
            timestamp, agent, "", entryId);
    }

    /**
     * Returns the string value of a field in a legacy JSON object, or {@code ""} if absent.
     */
    private static String legacyStr(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    /**
     * Returns the string value of a field in a legacy JSON object, or {@code null} if absent.
     */
    @Nullable
    private static String legacyStrOrNull(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) ? obj.get(key).getAsString() : null;
    }

    /**
     * Discovers sessions by reading the sessions-index.json file and supplementing with a
     * directory scan for any JSONL files not listed in the index.
     *
     * <p>The index provides the agent display name but may be incomplete (e.g. if the plugin
     * was upgraded mid-session). The directory scan ensures every JSONL file is migrated even
     * when the index is missing or out-of-date.
     */
    @NotNull
    static List<SessionInfo> discoverSessions(@NotNull Path sessionsDir) {
        Path indexFile = sessionsDir.resolve("sessions-index.json");
        List<SessionInfo> fromIndex = Files.isRegularFile(indexFile)
            ? readSessionsFromIndex(indexFile)
            : List.of();

        List<SessionInfo> fromScan = scanForSessionFiles(sessionsDir);

        if (fromIndex.isEmpty()) return fromScan;
        if (fromScan.isEmpty()) return fromIndex;

        // Merge: index entries take precedence (they carry the agent name).
        // Supplement with any JSONL files not listed in the index.
        Set<String> indexIds = new LinkedHashSet<>();
        List<SessionInfo> merged = new ArrayList<>(fromIndex);
        for (SessionInfo s : fromIndex) {
            indexIds.add(s.id());
        }
        for (SessionInfo s : fromScan) {
            if (!indexIds.contains(s.id())) {
                merged.add(s);
            }
        }
        return merged;
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
                String agent = rec.has(LEGACY_KEY_AGENT) ? rec.get(LEGACY_KEY_AGENT).getAsString() : "Unknown";
                result.add(new SessionInfo(id, agent));
            }
        } catch (Exception e) {
            LOG.warn("JsonlToSqliteMigrator: failed to read sessions-index.json", e);
        }
        return result;
    }

    @NotNull
    private static List<SessionInfo> scanForSessionFiles(@NotNull Path sessionsDir) {
        Set<String> seenIds = new LinkedHashSet<>();
        List<SessionInfo> result = new ArrayList<>();
        try (var stream = Files.list(sessionsDir)) {
            stream.filter(p -> p.toString().endsWith(JSONL_EXT))
                .map(p -> extractSessionIdFromFilename(p.getFileName().toString()))
                .filter(id -> id != null && !id.isEmpty())
                .forEach(id -> {
                    if (seenIds.add(id)) {
                        result.add(new SessionInfo(id, "Unknown"));
                    }
                });
        } catch (IOException e) {
            LOG.warn("JsonlToSqliteMigrator: failed to scan sessions directory", e);
        }
        return result;
    }

    /**
     * Extracts the session ID from a JSONL filename.
     * Handles both the active file ({@code <id>.jsonl}) and part files
     * ({@code <id>.part-NNN.jsonl}).  Returns {@code null} for unrecognised names.
     */
    @Nullable
    static String extractSessionIdFromFilename(@NotNull String filename) {
        int partIdx = filename.indexOf(".part-");
        if (partIdx >= 0) {
            return filename.substring(0, partIdx);
        }
        if (filename.endsWith(JSONL_EXT)) {
            return filename.substring(0, filename.length() - JSONL_EXT.length());
        }
        return null;
    }

    /**
     * Minimal session info needed for migration.
     */
    record SessionInfo(@NotNull String id, @NotNull String agent) {
    }
}
