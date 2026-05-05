package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.services.AgentIdMapper;
import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService;
import com.github.catatafishen.agentbridge.session.db.ConversationService;
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.v2.EntryDataJsonAdapter;
import com.github.catatafishen.agentbridge.session.v2.SessionFileRotation;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads session data from V2 JSONL files and aggregates into daily per-agent statistics.
 * Scans only {@code TurnStats} entries for efficiency — all other entry types are skipped.
 */
final class UsageStatisticsLoader {

    private static final Logger LOG = Logger.getInstance(UsageStatisticsLoader.class);

    private UsageStatisticsLoader() {
    }

    static UsageStatisticsData.StatisticsSnapshot load(@NotNull Project project,
                                                       @NotNull UsageStatisticsData.TimeRange range) {
        LocalDate startDate = range.startDate();
        LocalDate endDate = LocalDate.now();

        // Try SQLite first — much faster than scanning JSONL files
        UsageStatisticsData.StatisticsSnapshot sqliteResult =
            loadFromSqlite(project, range, startDate, endDate);
        if (sqliteResult != null) return sqliteResult;

        // Fall back to JSONL scan (DB empty or not initialized)
        return loadFromJsonl(project, range, startDate, endDate);
    }

    /**
     * Loads per-branch totals for the branch-comparison view. Always reads from
     * SQLite — JSONL has no branch info, so a JSONL fallback would always be
     * empty and would just confuse the user.
     *
     * @return branch snapshot. {@link UsageStatisticsData.BranchSnapshot#branches()}
     * is empty when no rows in the range have a git branch attached.
     */
    static UsageStatisticsData.BranchSnapshot loadBranches(@NotNull Project project,
                                                           @NotNull UsageStatisticsData.TimeRange range) {
        LocalDate startDate = range.startDate();
        LocalDate endDate = LocalDate.now();

        ToolCallStatisticsService service = ToolCallStatisticsService.getInstance(project);
        String startStr = startDate.toString();
        String endStr = endDate.toString();

        List<ToolCallStatisticsService.BranchAggregate> aggregates =
            service.queryBranchTotals(startStr, endStr);
        int unattributed = service.countUnattributedTurns(startStr, endStr);

        List<UsageStatisticsData.BranchStats> branches = new ArrayList<>();
        for (ToolCallStatisticsService.BranchAggregate agg : aggregates) {
            branches.add(new UsageStatisticsData.BranchStats(
                agg.branch(), agg.firstDetectedDate(),
                agg.turns(), agg.inputTokens(), agg.outputTokens(),
                agg.toolCalls(), agg.durationMs(),
                agg.linesAdded(), agg.linesRemoved(), agg.premiumRequests()
            ));
        }

        // For "all time", narrow start to earliest data date if available
        if (range == UsageStatisticsData.TimeRange.ALL) {
            LocalDate earliest = service.getEarliestTurnDate();
            if (earliest != null) {
                startDate = earliest;
            }
        }

        LOG.info("Statistics (branches): " + branches.size() + " branches, "
            + unattributed + " unattributed turns");

        return new UsageStatisticsData.BranchSnapshot(branches, startDate, endDate, unattributed);
    }

    /**
     * Loads statistics from the SQLite turn_stats table. Returns null if the
     * table is empty (triggers JSONL fallback with backfill).
     */
    @Nullable
    private static UsageStatisticsData.StatisticsSnapshot loadFromSqlite(
        @NotNull Project project,
        @NotNull UsageStatisticsData.TimeRange range,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate) {

        ToolCallStatisticsService service = ToolCallStatisticsService.getInstance(project);
        if (service.getTurnStatsCount() == 0) return null;

        String startStr = startDate.toString();
        String endStr = endDate.toString();
        List<ToolCallStatisticsService.DailyTurnAggregate> aggregates =
            service.queryDailyTurnStats(startStr, endStr);
        if (aggregates.isEmpty()) return null;

        // Build agent display names from the sessions index
        Map<String, String> agentDisplayNames = new LinkedHashMap<>();
        Set<String> agentIds = new LinkedHashSet<>();

        List<ConversationService.SessionRecord> sessions =
            ConversationService.getInstance(project).listSessions();
        for (ConversationService.SessionRecord session : sessions) {
            String agentId = toAgentId(session.agent());
            agentIds.add(agentId);
            agentDisplayNames.putIfAbsent(agentId, session.agent());
        }

        // Also include agents from the query results
        for (ToolCallStatisticsService.DailyTurnAggregate agg : aggregates) {
            agentIds.add(agg.agentId());
        }

        // Convert to DailyAgentStats
        List<UsageStatisticsData.DailyAgentStats> dailyStats = new ArrayList<>();
        for (ToolCallStatisticsService.DailyTurnAggregate agg : aggregates) {
            dailyStats.add(new UsageStatisticsData.DailyAgentStats(
                agg.date(), agg.agentId(),
                agg.turns(), agg.inputTokens(), agg.outputTokens(),
                agg.toolCalls(), agg.durationMs(),
                agg.linesAdded(), agg.linesRemoved(), agg.premiumRequests()
            ));
        }

        // For "all time", narrow start to earliest data date
        if (range == UsageStatisticsData.TimeRange.ALL) {
            LocalDate earliest = service.getEarliestTurnDate();
            if (earliest != null) {
                startDate = earliest;
            }
        }

        LOG.info("Statistics (SQLite): loaded " + dailyStats.size() + " day/agent buckets, "
            + dailyStats.stream().mapToInt(UsageStatisticsData.DailyAgentStats::turns).sum() + " total turns");

        return new UsageStatisticsData.StatisticsSnapshot(
            dailyStats, startDate, endDate, agentIds, agentDisplayNames);
    }

    /**
     * Original JSONL-based loader — used as fallback when the SQLite table is empty.
     */
    private static UsageStatisticsData.StatisticsSnapshot loadFromJsonl(
        @NotNull Project project,
        @NotNull UsageStatisticsData.TimeRange range,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate) {

        String basePath = project.getBasePath();
        List<ConversationService.SessionRecord> sessions =
            ConversationService.getInstance(project).listSessions();
        if (sessions.isEmpty()) {
            LOG.info("Statistics: no sessions found for basePath=" + basePath);
            return emptySnapshot(startDate, endDate);
        }

        Map<DayAgentKey, Accumulator> accumulators = new LinkedHashMap<>();
        Set<String> agentIds = new LinkedHashSet<>();
        Map<String, String> agentDisplayNames = new LinkedHashMap<>();

        File sessionsDir = ExportUtils.sessionsDir(project);

        for (ConversationService.SessionRecord session : sessions) {
            String agentDisplay = session.agent();
            String fallbackAgentId = toAgentId(agentDisplay);
            agentIds.add(fallbackAgentId);
            agentDisplayNames.putIfAbsent(fallbackAgentId, agentDisplay);

            List<Path> jsonlFiles = SessionFileRotation.listAllFiles(sessionsDir, session.id());
            if (jsonlFiles.isEmpty()) {
                LOG.debug("Statistics: no JSONL files found for session " + session.id());
                continue;
            }

            collectTurnStats(jsonlFiles, fallbackAgentId, startDate, endDate,
                accumulators);
        }

        List<UsageStatisticsData.DailyAgentStats> dailyStats = buildDailyStats(accumulators);
        LOG.info("Statistics (JSONL fallback): loaded " + sessions.size() + " sessions, "
            + accumulators.size() + " day/agent buckets, "
            + dailyStats.stream().mapToInt(UsageStatisticsData.DailyAgentStats::turns).sum() + " total turns");

        if (range == UsageStatisticsData.TimeRange.ALL && !accumulators.isEmpty()) {
            startDate = accumulators.keySet().stream()
                .map(DayAgentKey::date)
                .min(Comparator.naturalOrder())
                .orElse(startDate);
        }

        return new UsageStatisticsData.StatisticsSnapshot(
            dailyStats, startDate, endDate, agentIds, agentDisplayNames);
    }

    private static List<UsageStatisticsData.DailyAgentStats> buildDailyStats(
        Map<DayAgentKey, Accumulator> accumulators) {
        List<UsageStatisticsData.DailyAgentStats> result = new ArrayList<>();
        for (var entry : accumulators.entrySet()) {
            DayAgentKey key = entry.getKey();
            Accumulator acc = entry.getValue();
            result.add(new UsageStatisticsData.DailyAgentStats(
                key.date, key.agentId,
                acc.turns, acc.inputTokens, acc.outputTokens,
                acc.toolCalls, acc.durationMs,
                acc.linesAdded, acc.linesRemoved, acc.premiumRequests
            ));
        }
        result.sort(Comparator.comparing(UsageStatisticsData.DailyAgentStats::date)
            .thenComparing(UsageStatisticsData.DailyAgentStats::agentId));
        return result;
    }

    /**
     * Collects turn statistics from one or more JSONL files for a session.
     * Preserves {@code lastSeenTimestamp} state across part file boundaries so that
     * TurnStats entries without their own timestamp inherit the correct date.
     */
    private static void collectTurnStats(List<Path> jsonlFiles, String sessionAgentId,
                                         LocalDate startDate, LocalDate endDate,
                                         Map<DayAgentKey, Accumulator> accumulators) {
        String lastSeenTimestamp = null;
        for (Path jsonlPath : jsonlFiles) {
            lastSeenTimestamp = collectTurnStatsFromFile(
                jsonlPath, lastSeenTimestamp, sessionAgentId, startDate, endDate, accumulators);
        }
    }

    /**
     * Reads one JSONL file and accumulates TurnStats into {@code accumulators}.
     * Returns the most-recently-seen raw timestamp string (for carry-over into the next file).
     */
    @Nullable
    private static String collectTurnStatsFromFile(Path jsonlPath,
                                                   @Nullable String lastSeenTimestamp,
                                                   String sessionAgentId,
                                                   LocalDate startDate, LocalDate endDate,
                                                   Map<DayAgentKey, Accumulator> accumulators) {
        try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Track timestamps from all entries for date attribution.
                // TurnStats entries added before v2.5 lack their own timestamp, so we also
                // track the most recent timestamp seen in any preceding entry as a fallback.
                String ts = extractTimestampFromLine(line);
                if (ts != null) lastSeenTimestamp = ts;

                // Agent attribution uses the session-level agent exclusively.
                // Individual entry "agent" fields are ignored here because they can contain
                // model names or sub-agent identifiers that would create phantom chart series.
                if (!line.contains("\"turnStats\"")) continue;

                applyTurnStatsLine(line, jsonlPath, lastSeenTimestamp,
                    sessionAgentId, startDate, endDate, accumulators);
            }
        } catch (IOException e) {
            LOG.warn("Failed to read session file: " + jsonlPath, e);
        }
        return lastSeenTimestamp;
    }

    /**
     * Extracts the raw {@code "timestamp"} value from a single JSONL line using
     * lightweight string scanning (no JSON parse). Returns {@code null} when absent.
     */
    @Nullable
    private static String extractTimestampFromLine(String line) {
        int tsIdx = line.indexOf("\"timestamp\":\"");
        if (tsIdx < 0) return null;
        int start = tsIdx + "\"timestamp\":\"".length();
        int end = line.indexOf('"', start);
        if (end <= start) return null;
        String candidate = line.substring(start, end);
        return candidate.isEmpty() ? null : candidate;
    }

    /**
     * Parses one JSONL line as a {@link EntryData.TurnStats} entry and, if it falls within
     * the requested date window, merges its counters into {@code accumulators}.
     */
    private static void applyTurnStatsLine(String line, Path jsonlPath,
                                           @Nullable String lastSeenTimestamp,
                                           String sessionAgentId,
                                           LocalDate startDate, LocalDate endDate,
                                           Map<DayAgentKey, Accumulator> accumulators) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            EntryData entry = EntryDataJsonAdapter.deserialize(obj);
            if (!(entry instanceof EntryData.TurnStats stats)) return;

            LocalDate date = extractDate(obj, lastSeenTimestamp);
            if (date == null) {
                LOG.debug("Skipping TurnStats entry with no resolvable timestamp in " + jsonlPath.getFileName());
                return;
            }
            if (date.isBefore(startDate) || date.isAfter(endDate)) return;

            DayAgentKey key = new DayAgentKey(date, sessionAgentId);
            Accumulator acc = accumulators.computeIfAbsent(key, k -> new Accumulator());
            acc.turns++;
            acc.inputTokens += stats.getInputTokens();
            acc.outputTokens += stats.getOutputTokens();
            acc.toolCalls += stats.getToolCallCount();
            acc.durationMs += stats.getDurationMs();
            acc.linesAdded += stats.getLinesAdded();
            acc.linesRemoved += stats.getLinesRemoved();
            acc.premiumRequests += parsePremiumMultiplier(stats.getMultiplier());
        } catch (Exception e) {
            LOG.debug("Skipping malformed JSONL line in " + jsonlPath.getFileName() + ": " + e.getMessage());
        }
    }

    @Nullable
    private static LocalDate extractDate(JsonObject obj, @Nullable String fallbackTimestamp) {
        String ts = null;
        if (obj.has("timestamp")) {
            String val = obj.get("timestamp").getAsString();
            if (!val.isEmpty()) ts = val;
        }
        if (ts == null && fallbackTimestamp != null) {
            ts = fallbackTimestamp;
        }
        if (ts != null) {
            try {
                return Instant.parse(ts).atZone(ZoneId.systemDefault()).toLocalDate();
            } catch (Exception ignored) {
                // Unparseable timestamp — fall through
            }
        }
        return null;
    }

    /**
     * Maps agent display names (e.g. "GitHub Copilot") to profile IDs (e.g. "copilot")
     * for color lookup via {@code ChatTheme.agentColorIndex()}.
     */
    static String toAgentId(String agentDisplayName) {
        return AgentIdMapper.toAgentId(agentDisplayName);
    }

    private static double parsePremiumMultiplier(String multiplier) {
        if (multiplier == null || multiplier.isEmpty()) return 1.0;
        // Strip trailing "x" suffix (e.g. "1x", "0.5x") before parsing
        String cleaned = multiplier.endsWith("x")
            ? multiplier.substring(0, multiplier.length() - 1)
            : multiplier;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private static UsageStatisticsData.StatisticsSnapshot emptySnapshot(LocalDate start, LocalDate end) {
        return new UsageStatisticsData.StatisticsSnapshot(
            List.of(), start, end, Set.of(), Map.of());
    }

    private record DayAgentKey(LocalDate date, String agentId) {
    }

    private static final class Accumulator {
        int turns;
        long inputTokens;
        long outputTokens;
        int toolCalls;
        long durationMs;
        int linesAdded;
        int linesRemoved;
        double premiumRequests;
    }
}
