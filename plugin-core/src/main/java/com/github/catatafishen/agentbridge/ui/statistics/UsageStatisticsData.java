package com.github.catatafishen.agentbridge.ui.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data model for aggregated usage statistics.
 */
final class UsageStatisticsData {

    private UsageStatisticsData() {
    }

    /**
     * Aggregated metrics for a single day and a single agent.
     */
    record DailyAgentStats(
        LocalDate date,
        String agentId,
        int turns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved
    ) {
    }

    /**
     * Complete statistics snapshot for a date range, ready for chart rendering.
     */
    record StatisticsSnapshot(
        List<DailyAgentStats> dailyStats,
        LocalDate startDate,
        LocalDate endDate,
        Set<String> agentIds,
        Map<String, String> agentDisplayNames
    ) {
        long totalTurns() {
            return dailyStats.stream().mapToInt(DailyAgentStats::turns).sum();
        }

        long totalTokens() {
            return dailyStats.stream()
                .mapToLong(s -> s.inputTokens() + s.outputTokens())
                .sum();
        }

        long totalToolCalls() {
            return dailyStats.stream().mapToInt(DailyAgentStats::toolCalls).sum();
        }

        long totalDurationMs() {
            return dailyStats.stream().mapToLong(DailyAgentStats::durationMs).sum();
        }
    }

    enum TimeRange {
        WEEK_7("7 days", 7),
        MONTH_30("30 days", 30),
        QUARTER_90("90 days", 90),
        ALL("All time", -1);

        private final String label;
        private final int days;

        TimeRange(String label, int days) {
            this.label = label;
            this.days = days;
        }

        String label() {
            return label;
        }

        int days() {
            return days;
        }

        LocalDate startDate() {
            if (days < 0) return LocalDate.of(2020, 1, 1);
            return LocalDate.now().minusDays(days - 1L);
        }
    }

    enum Metric {
        PROMPTS("Prompts"),
        TOKENS("Tokens"),
        TOOL_CALLS("Tool Calls"),
        CODE_CHANGES("Code Changes (lines)"),
        AGENT_TIME("Agent Time");

        private final String displayName;

        Metric(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    enum BranchSort {
        BAR_VALUE("Bar value"),
        BRANCH_NAME("Branch name"),
        FIRST_DETECTED("First detected");

        private final String label;

        BranchSort(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * Aggregated metrics for a single git branch over the selected date range.
     * Used by the branch-comparison bar charts; one entry per distinct branch.
     */
    record BranchStats(
        String branch,
        LocalDate firstDetectedDate,
        int turns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved
    ) {
    }

    /**
     * Snapshot for branch-comparison view.
     *
     * @param branches     one entry per branch
     * @param unattributed count of turns in the date range with NULL/empty git_branch
     *                     (recorded before branch tracking was added, or when git
     *                     was unavailable). Surfaced as a warning in the panel so
     *                     users understand why the totals differ from the agent view.
     */
    record BranchSnapshot(
        List<BranchStats> branches,
        LocalDate startDate,
        LocalDate endDate,
        int unattributed
    ) {
    }
}
