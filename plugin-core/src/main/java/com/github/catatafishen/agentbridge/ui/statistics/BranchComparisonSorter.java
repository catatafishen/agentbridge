package com.github.catatafishen.agentbridge.ui.statistics;

import java.util.Comparator;
import java.util.List;

final class BranchComparisonSorter {

    private BranchComparisonSorter() {
    }

    static List<UsageStatisticsData.BranchStats> sort(
        List<UsageStatisticsData.BranchStats> branches,
        UsageStatisticsData.BranchSort sort,
        UsageStatisticsData.Metric metric
    ) {
        return branches.stream()
            .sorted(comparator(sort, metric))
            .toList();
    }

    static double valueFor(UsageStatisticsData.Metric metric, UsageStatisticsData.BranchStats branch) {
        return switch (metric) {
            case PROMPTS -> branch.turns();
            case TOKENS -> branch.inputTokens() + branch.outputTokens();
            case TOOL_CALLS -> branch.toolCalls();
            case CODE_CHANGES -> branch.linesAdded() + branch.linesRemoved();
            case AGENT_TIME -> branch.durationMs();
        };
    }

    private static Comparator<UsageStatisticsData.BranchStats> comparator(
        UsageStatisticsData.BranchSort sort,
        UsageStatisticsData.Metric metric
    ) {
        return switch (sort) {
            case BRANCH_NAME -> branchNameComparator();
            case FIRST_DETECTED -> Comparator.comparing(UsageStatisticsData.BranchStats::firstDetectedDate)
                .thenComparing(branchNameComparator());
            case BAR_VALUE -> Comparator.<UsageStatisticsData.BranchStats>comparingDouble(
                    branch -> valueFor(metric, branch))
                .reversed()
                .thenComparing(branchNameComparator());
        };
    }

    private static Comparator<UsageStatisticsData.BranchStats> branchNameComparator() {
        return Comparator.comparing(UsageStatisticsData.BranchStats::branch, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(UsageStatisticsData.BranchStats::branch);
    }
}
