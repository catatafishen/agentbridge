package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.v2.SessionFileRotation;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills turn statistics from session JSONL files into the SQLite database.
 *
 * <p>Session files contain {@code "type":"turnStats"} entries with per-turn token counts,
 * tool call counts, duration, and code change metrics. This class scans those entries
 * and inserts them as {@link ToolCallStatisticsService.TurnStatsRecord}s.</p>
 *
 * <p>Backfill is idempotent: it skips entries whose timestamp already exists in the
 * database (uses the ISO 8601 timestamp for deduplication).</p>
 */
public final class TurnStatisticsBackfill {

    private static final Logger LOG = Logger.getInstance(TurnStatisticsBackfill.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String KEY_COMMIT_HASHES = "commitHashes";
    private static final String KEY_GIT_BRANCH_START = "gitBranchStart";
    private static final String KEY_GIT_BRANCH_END = "gitBranchEnd";

    private TurnStatisticsBackfill() {
        throw new IllegalStateException("Utility class");
    }

    public record BackfillResult(int inserted, int skipped, int errors) {
        @NotNull
        @Override
        public String toString() {
            return "BackfillResult{inserted=" + inserted + ", skipped=" + skipped
                + ", errors=" + errors + "}";
        }
    }

    /**
     * Scans all session JSONL files and backfills turn stats records into the
     * given service. Skips entries that already exist (by timestamp).
     *
     * @param service  the statistics service to insert records into
     * @param basePath the project base path (for locating session files)
     * @return the backfill result with counts
     */
    public static BackfillResult backfill(@NotNull ToolCallStatisticsService service,
                                          @NotNull String basePath) {
        SessionStoreV2 store = new SessionStoreV2();
        List<SessionStoreV2.SessionRecord> sessions = store.listSessions(basePath);
        if (sessions.isEmpty()) {
            LOG.info("Turn stats backfill: no sessions found");
            return new BackfillResult(0, 0, 0);
        }

        Map<String, String> sessionToAgent = buildSessionAgentMap(sessions);
        File sessionsDir = ExportUtils.sessionsDir(basePath);
        int inserted = 0;
        int skipped = 0;
        int errors = 0;

        for (SessionStoreV2.SessionRecord session : sessions) {
            List<Path> jsonlFiles = SessionFileRotation.listAllFiles(sessionsDir, session.id());
            if (jsonlFiles.isEmpty()) continue;

            String agentId = sessionToAgent.get(session.id());
            BackfillResult sessionResult = backfillSession(
                service, jsonlFiles, session.id(), agentId);
            inserted += sessionResult.inserted();
            skipped += sessionResult.skipped();
            errors += sessionResult.errors();
        }

        LOG.info("Turn stats backfill complete: " + inserted + " inserted, "
            + skipped + " skipped, " + errors + " errors");
        return new BackfillResult(inserted, skipped, errors);
    }

    private static Map<String, String> buildSessionAgentMap(
        @NotNull List<SessionStoreV2.SessionRecord> sessions) {
        Map<String, String> map = new HashMap<>();
        for (SessionStoreV2.SessionRecord session : sessions) {
            map.put(session.id(), AgentIdMapper.toAgentId(session.agent()));
        }
        return map;
    }

    private static BackfillResult backfillSession(@NotNull ToolCallStatisticsService service,
                                                  @NotNull List<Path> jsonlFiles,
                                                  @NotNull String sessionId,
                                                  @NotNull String agentId) {
        int inserted = 0;
        int skipped = 0;
        int errors = 0;
        String[] lastSeenTimestamp = {null};

        for (Path jsonlPath : jsonlFiles) {
            try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    trackTimestamp(line, lastSeenTimestamp);
                    if (!line.contains("\"turnStats\"")) continue;

                    EntryResult entryResult = processEntry(
                        service, line, sessionId, agentId, lastSeenTimestamp[0]);
                    switch (entryResult) {
                        case INSERTED -> inserted++;
                        case SKIPPED -> skipped++;
                        case ERROR -> errors++;
                    }
                }
            } catch (IOException e) {
                LOG.warn("Turn stats backfill: failed to read " + jsonlPath.getFileName(), e);
            }
        }

        return new BackfillResult(inserted, skipped, errors);
    }

    private static void trackTimestamp(@NotNull String line, @NotNull String[] holder) {
        int tsIdx = line.indexOf("\"timestamp\":\"");
        if (tsIdx < 0) return;
        int start = tsIdx + "\"timestamp\":\"".length();
        int end = line.indexOf('"', start);
        if (end > start) {
            String candidate = line.substring(start, end);
            if (!candidate.isEmpty()) {
                holder[0] = candidate;
            }
        }
    }

    private enum EntryResult {INSERTED, SKIPPED, ERROR}

    private static EntryResult processEntry(@NotNull ToolCallStatisticsService service,
                                            @NotNull String line,
                                            @NotNull String sessionId,
                                            @NotNull String agentId,
                                            @Nullable String fallbackTimestamp) {
        ToolCallStatisticsService.TurnStatsRecord turnRecord =
            parseTurnStatsEntry(line, sessionId, agentId, fallbackTimestamp);
        if (turnRecord == null) return EntryResult.ERROR;

        if (service.hasTurnStatsAt(turnRecord.timestamp())) {
            return EntryResult.SKIPPED;
        }
        service.recordTurnStats(turnRecord);
        return EntryResult.INSERTED;
    }

    @Nullable
    private static ToolCallStatisticsService.TurnStatsRecord parseTurnStatsEntry(
        @NotNull String line,
        @NotNull String sessionId,
        @NotNull String agentId,
        @Nullable String fallbackTimestamp) {

        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();

        String type = getStr(obj, "type");
        if (!"turnStats".equals(type)) return null;

        // Resolve timestamp — prefer entry's own, fall back to last seen
        String timestamp = getStr(obj, "timestamp");
        if (timestamp.isEmpty()) {
            timestamp = fallbackTimestamp;
        }
        if (timestamp == null || timestamp.isEmpty()) return null;

        // Derive date from timestamp
        String date;
        try {
            date = Instant.parse(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }

        long inputTokens = getLong(obj, "inputTokens");
        long outputTokens = getLong(obj, "outputTokens");
        int toolCalls = getInt(obj, "toolCallCount");
        long durationMs = getLong(obj, "durationMs");
        int linesAdded = getInt(obj, "linesAdded");
        int linesRemoved = getInt(obj, "linesRemoved");
        double premiumRequests = parsePremiumMultiplier(getStr(obj, "multiplier"));

        String commitHashes = null;
        if (obj.has(KEY_COMMIT_HASHES) && obj.get(KEY_COMMIT_HASHES).isJsonArray()) {
            List<String> hashes = new ArrayList<>();
            for (var el : obj.getAsJsonArray(KEY_COMMIT_HASHES)) {
                hashes.add(el.getAsString());
            }
            if (!hashes.isEmpty()) {
                commitHashes = String.join(",", hashes);
            }
        }

        String gitBranchStart = nullableStr(obj, KEY_GIT_BRANCH_START);
        String gitBranchEnd = nullableStr(obj, KEY_GIT_BRANCH_END);

        return new ToolCallStatisticsService.TurnStatsRecord(
            sessionId, agentId, date,
            inputTokens, outputTokens, toolCalls, durationMs,
            linesAdded, linesRemoved, premiumRequests, timestamp, commitHashes,
            null, gitBranchStart, gitBranchEnd);
    }

    private static double parsePremiumMultiplier(String multiplier) {
        if (multiplier == null || multiplier.isEmpty()) return 1.0;
        String cleaned = multiplier.endsWith("x")
            ? multiplier.substring(0, multiplier.length() - 1)
            : multiplier;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    @NotNull
    private static String getStr(@NotNull JsonObject obj, @NotNull String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    private static long getLong(@NotNull JsonObject obj, @NotNull String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return 0;
    }

    private static int getInt(@NotNull JsonObject obj, @NotNull String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return 0;
    }

    @Nullable
    private static String nullableStr(@NotNull JsonObject obj, @NotNull String key) {
        String value = getStr(obj, key);
        return value.isEmpty() ? null : value;
    }
}
