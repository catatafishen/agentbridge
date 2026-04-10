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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Loads session data from V2 JSONL files and aggregates into daily per-agent statistics.
 * Scans only {@code TurnStats} entries for efficiency — all other entry types are skipped.
 */
final class UsageStatisticsLoader {

    private static final Logger LOG = Logger.getInstance(UsageStatisticsLoader.class);

    private UsageStatisticsLoader() {
    }

    /**
     * Loads and aggregates usage statistics for the given project and time range.
     */
    static UsageStatisticsData.StatisticsSnapshot load(@NotNull Project project,
                                                       @NotNull UsageStatisticsData.TimeRange range) {
        LocalDate startDate = range.startDate();
        LocalDate endDate = LocalDate.now();

        String basePath = project.getBasePath();
        List<SessionStoreV2.SessionRecord> sessions =
                SessionStoreV2.getInstance(project).listSessions(basePath);
        if (sessions.isEmpty()) {
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
            if (!Files.exists(jsonlPath)) continue;

            collectTurnStats(jsonlPath, agentId, startDate, endDate, accumulators);
        }

        List<UsageStatisticsData.DailyAgentStats> dailyStats = buildDailyStats(accumulators);

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
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"turnStats\"")) continue;

                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                if (!(entry instanceof EntryData.TurnStats stats)) continue;

                LocalDate date = extractDate(obj);
                if (date == null || date.isBefore(startDate) || date.isAfter(endDate)) continue;

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
            }
        } catch (IOException e) {
            LOG.warn("Failed to read session file: " + jsonlPath, e);
        }
    }

    private static LocalDate extractDate(JsonObject obj) {
        if (obj.has("timestamp")) {
            String ts = obj.get("timestamp").getAsString();
            if (!ts.isEmpty()) {
                try {
                    return Instant.parse(ts).atZone(ZoneId.systemDefault()).toLocalDate();
                } catch (Exception ignored) {
                    // Fall through to default
                }
            }
        }
        return LocalDate.now();
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
        try {
            return Double.parseDouble(multiplier);
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
