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
        int linesRemoved,
        double premiumRequests
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

        double totalPremiumRequests() {
            return dailyStats.stream().mapToDouble(DailyAgentStats::premiumRequests).sum();
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
        PREMIUM_REQUESTS("Premium Requests"),
        TURNS("Turns"),
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

    /**
     * Grouping dimension for the usage statistics charts. Driven by the
     * "Group by" combo box in {@link UsageStatisticsPanel}.
     */
    enum GroupBy {
        AGENT("Agent"),
        GIT_BRANCH("Git Branch");

        private final String label;

        GroupBy(String label) {
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
        int turns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved,
        double premiumRequests
    ) {
    }

    /**
     * Snapshot for branch-grouped view. Populated only when the user picks
     * "Git Branch" in the Group by combo.
     *
     * @param branches      one entry per branch, sorted by premium-request cost desc
     * @param unattributed  count of turns in the date range with NULL/empty git_branch
     *                      (recorded before branch tracking was added, or when git
     *                      was unavailable). Surfaced as a warning in the panel so
     *                      users understand why the totals differ from the agent view.
     */
    record BranchSnapshot(
        List<BranchStats> branches,
        LocalDate startDate,
        LocalDate endDate,
        int unattributed
    ) {
    }
}
