package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.v2.EntryDataJsonAdapter;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
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

        String basePath = project.getBasePath();
        List<SessionStoreV2.SessionRecord> sessions =
            SessionStoreV2.getInstance(project).listSessions(basePath);
        if (sessions.isEmpty()) {
            LOG.info("Statistics: no sessions found for basePath=" + basePath);
            return emptySnapshot(startDate, endDate);
        }

        Map<DayAgentKey, Accumulator> accumulators = new LinkedHashMap<>();
        Set<String> agentIds = new LinkedHashSet<>();
        Map<String, String> agentDisplayNames = new LinkedHashMap<>();

        File sessionsDir = ExportUtils.sessionsDir(basePath);

        for (SessionStoreV2.SessionRecord session : sessions) {
            String agentDisplay = session.agent();
            String agentId = toAgentId(agentDisplay);
            agentIds.add(agentId);
            agentDisplayNames.putIfAbsent(agentId, agentDisplay);

            Path jsonlPath = sessionsDir.toPath().resolve(session.id() + ".jsonl");
            if (!Files.exists(jsonlPath)) {
                LOG.debug("Statistics: JSONL file not found: " + jsonlPath);
                continue;
            }

            collectTurnStats(jsonlPath, agentId, startDate, endDate, accumulators);
        }

        List<UsageStatisticsData.DailyAgentStats> dailyStats = buildDailyStats(accumulators);
        LOG.info("Statistics: loaded " + sessions.size() + " sessions, "
            + accumulators.size() + " day/agent buckets, "
            + dailyStats.stream().mapToInt(UsageStatisticsData.DailyAgentStats::turns).sum() + " total turns");

        // For "all time", start from the earliest data date instead of 2020-01-01
        // to avoid creating thousands of zero-fill points in the chart
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

    private static void collectTurnStats(Path jsonlPath, String agentId,
                                         LocalDate startDate, LocalDate endDate,
                                         Map<DayAgentKey, Accumulator> accumulators) {
        try (BufferedReader reader = Files.newBufferedReader(jsonlPath)) {
            String lastSeenTimestamp = null;
            String line;
            while ((line = reader.readLine()) != null) {
                // Track timestamps from all entries for date attribution.
                // TurnStats entries added before v2.5 lack their own timestamp, so we also
                // track the most recent timestamp seen in any preceding entry as a fallback.
                int tsIdx = line.indexOf("\"timestamp\":\"");
                if (tsIdx >= 0) {
                    int start = tsIdx + "\"timestamp\":\"".length();
                    int end = line.indexOf('"', start);
                    if (end > start) {
                        String candidate = line.substring(start, end);
                        if (!candidate.isEmpty()) {
                            lastSeenTimestamp = candidate;
                        }
                    }
                }

                if (!line.contains("\"turnStats\"")) continue;

                try {
                    JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                    EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                    if (!(entry instanceof EntryData.TurnStats stats)) continue;

                    LocalDate date = extractDate(obj, lastSeenTimestamp);
                    if (date == null) {
                        LOG.debug("Skipping TurnStats entry with no resolvable timestamp in " + jsonlPath.getFileName());
                        continue;
                    }
                    if (date.isBefore(startDate) || date.isAfter(endDate)) continue;

                    DayAgentKey key = new DayAgentKey(date, agentId);
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
        } catch (IOException e) {
            LOG.warn("Failed to read session file: " + jsonlPath, e);
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
        if (agentDisplayName == null || agentDisplayName.isEmpty()) return "unknown";
        String lower = agentDisplayName.toLowerCase();
        if (lower.contains("copilot")) return "copilot";
        if (lower.contains("claude")) return "claude-cli";
        if (lower.contains("opencode")) return "opencode";
        if (lower.contains("junie")) return "junie";
        if (lower.contains("kiro")) return "kiro";
        if (lower.contains("codex")) return "codex";
        return lower.replaceAll("[^a-z0-9]", "-");
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
