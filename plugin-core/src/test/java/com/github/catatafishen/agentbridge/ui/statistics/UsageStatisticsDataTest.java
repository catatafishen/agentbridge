package com.github.catatafishen.agentbridge.ui.statistics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UsageStatisticsDataTest {

    // ── Shared test data ────────────────────────────────────────────────

    private static final UsageStatisticsData.DailyAgentStats STAT1 =
        new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 1, 1), "copilot", 5, 1000, 2000, 10, 5000, 50, 20);

    private static final UsageStatisticsData.DailyAgentStats STAT2 =
        new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 1, 2), "claude-cli", 3, 500, 1000, 8, 3000, 30, 10);

    private static UsageStatisticsData.StatisticsSnapshot snapshotOf(
        List<UsageStatisticsData.DailyAgentStats> stats) {
        return new UsageStatisticsData.StatisticsSnapshot(
            stats,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            Set.of("copilot", "claude-cli"),
            Map.of("copilot", "GitHub Copilot", "claude-cli", "Claude Code"));
    }

    // ── DailyAgentStats record ──────────────────────────────────────────

    @Test
    void dailyAgentStats_constructableAndFieldsAccessible() {
        var stat = new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 1, 1), "copilot", 5, 1000, 2000, 10, 5000, 50, 20);
        assertEquals(LocalDate.of(2024, 1, 1), stat.date());
        assertEquals("copilot", stat.agentId());
        assertEquals(5, stat.turns());
        assertEquals(1000, stat.inputTokens());
        assertEquals(2000, stat.outputTokens());
        assertEquals(10, stat.toolCalls());
        assertEquals(5000, stat.durationMs());
        assertEquals(50, stat.linesAdded());
        assertEquals(20, stat.linesRemoved());
    }

    // ── StatisticsSnapshot aggregations (tests 1–5) ────────────────────

    @Test
    void totalTurns() {
        var snapshot = snapshotOf(List.of(STAT1, STAT2));
        assertEquals(8, snapshot.totalTurns()); // 5 + 3
    }

    @Test
    void totalTokens() {
        var snapshot = snapshotOf(List.of(STAT1, STAT2));
        assertEquals(4500, snapshot.totalTokens()); // (1000+2000) + (500+1000)
    }

    @Test
    void totalToolCalls() {
        var snapshot = snapshotOf(List.of(STAT1, STAT2));
        assertEquals(18, snapshot.totalToolCalls()); // 10 + 8
    }

    @Test
    void totalDurationMs() {
        var snapshot = snapshotOf(List.of(STAT1, STAT2));
        assertEquals(8000, snapshot.totalDurationMs()); // 5000 + 3000
    }

    // ── StatisticsSnapshot edge cases (tests 6–7) ──────────────────────

    @Test
    void emptyDailyStats_totalsAreZero() {
        var snapshot = snapshotOf(List.of());
        assertEquals(0, snapshot.totalTurns());
        assertEquals(0, snapshot.totalTokens());
        assertEquals(0, snapshot.totalToolCalls());
        assertEquals(0, snapshot.totalDurationMs());
    }

    @Test
    void singleDayStat_totalsMatchDirectly() {
        var snapshot = snapshotOf(List.of(STAT1));
        assertEquals(5, snapshot.totalTurns());
        assertEquals(3000, snapshot.totalTokens()); // 1000 + 2000
        assertEquals(10, snapshot.totalToolCalls());
        assertEquals(5000, snapshot.totalDurationMs());
    }

    // ── TimeRange labels (tests 8–11) ───────────────────────────────────

    @Test
    void timeRange_weekLabel() {
        assertEquals("7 days", UsageStatisticsData.TimeRange.WEEK_7.label());
    }

    @Test
    void timeRange_monthLabel() {
        assertEquals("30 days", UsageStatisticsData.TimeRange.MONTH_30.label());
    }

    @Test
    void timeRange_quarterLabel() {
        assertEquals("90 days", UsageStatisticsData.TimeRange.QUARTER_90.label());
    }

    @Test
    void timeRange_allLabel() {
        assertEquals("All time", UsageStatisticsData.TimeRange.ALL.label());
    }

    // ── TimeRange days (tests 12–15) ────────────────────────────────────

    @Test
    void timeRange_weekDays() {
        assertEquals(7, UsageStatisticsData.TimeRange.WEEK_7.days());
    }

    @Test
    void timeRange_monthDays() {
        assertEquals(30, UsageStatisticsData.TimeRange.MONTH_30.days());
    }

    @Test
    void timeRange_quarterDays() {
        assertEquals(90, UsageStatisticsData.TimeRange.QUARTER_90.days());
    }

    @Test
    void timeRange_allDays() {
        assertEquals(-1, UsageStatisticsData.TimeRange.ALL.days());
    }

    // ── TimeRange startDate (tests 16–18) ───────────────────────────────

    @Test
    void timeRange_allStartDate_is2020() {
        assertEquals(LocalDate.of(2020, 1, 1), UsageStatisticsData.TimeRange.ALL.startDate());
    }

    @Test
    void timeRange_weekStartDate() {
        assertEquals(LocalDate.now().minusDays(6), UsageStatisticsData.TimeRange.WEEK_7.startDate());
    }

    @Test
    void timeRange_monthStartDate() {
        assertEquals(LocalDate.now().minusDays(29), UsageStatisticsData.TimeRange.MONTH_30.startDate());
    }

    // ── Metric enum (tests 19–20) ───────────────────────────────────────

    @Test
    void metric_displayNames() {
        assertEquals("Prompts", UsageStatisticsData.Metric.PROMPTS.displayName());
        assertEquals("Tokens", UsageStatisticsData.Metric.TOKENS.displayName());
        assertEquals("Tool Calls", UsageStatisticsData.Metric.TOOL_CALLS.displayName());
        assertEquals("Code Changes (lines)", UsageStatisticsData.Metric.CODE_CHANGES.displayName());
        assertEquals("Agent Time", UsageStatisticsData.Metric.AGENT_TIME.displayName());
    }

    @Test
    void metric_allValuesHaveNonEmptyDisplayNames() {
        UsageStatisticsData.Metric[] values = UsageStatisticsData.Metric.values();
        assertEquals(5, values.length);
        for (UsageStatisticsData.Metric metric : values) {
            assertNotNull(metric.displayName(), metric.name() + " has null displayName");
            assertFalse(metric.displayName().isEmpty(), metric.name() + " has empty displayName");
        }
    }
}
