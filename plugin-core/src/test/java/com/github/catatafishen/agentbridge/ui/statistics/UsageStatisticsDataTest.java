package com.github.catatafishen.agentbridge.ui.statistics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UsageStatisticsDataTest {

    // ── DailyAgentStats record ──────────────────────────────────────────

    @Test
    void dailyAgentStats_recordFields() {
        var stats = new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 6, 15), "copilot",
            5, 1000, 2000, 10, 5000, 100, 50, 1.5
        );
        assertEquals(LocalDate.of(2024, 6, 15), stats.date());
        assertEquals("copilot", stats.agentId());
        assertEquals(5, stats.turns());
        assertEquals(1000, stats.inputTokens());
        assertEquals(2000, stats.outputTokens());
        assertEquals(10, stats.toolCalls());
        assertEquals(5000, stats.durationMs());
        assertEquals(100, stats.linesAdded());
        assertEquals(50, stats.linesRemoved());
        assertEquals(1.5, stats.premiumRequests());
    }

    @Test
    void dailyAgentStats_equality() {
        var a = new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 1, 1), "copilot", 1, 100, 200, 5, 1000, 10, 5, 1.0);
        var b = new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 1, 1), "copilot", 1, 100, 200, 5, 1000, 10, 5, 1.0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ── StatisticsSnapshot aggregations ─────────────────────────────────

    @Test
    void snapshot_totalTurns() {
        var snapshot = createSnapshot(
            stats("copilot", 3, 100, 200, 5, 1000, 10, 5, 1.0),
            stats("claude", 7, 300, 400, 15, 2000, 20, 10, 2.0)
        );
        assertEquals(10, snapshot.totalTurns());
    }

    @Test
    void snapshot_totalTokens() {
        var snapshot = createSnapshot(
            stats("copilot", 1, 100, 200, 0, 0, 0, 0, 0),
            stats("claude", 1, 300, 400, 0, 0, 0, 0, 0)
        );
        assertEquals(1000, snapshot.totalTokens()); // (100+200) + (300+400)
    }

    @Test
    void snapshot_totalToolCalls() {
        var snapshot = createSnapshot(
            stats("copilot", 1, 0, 0, 5, 0, 0, 0, 0),
            stats("claude", 1, 0, 0, 15, 0, 0, 0, 0)
        );
        assertEquals(20, snapshot.totalToolCalls());
    }

    @Test
    void snapshot_totalDurationMs() {
        var snapshot = createSnapshot(
            stats("copilot", 1, 0, 0, 0, 3000, 0, 0, 0),
            stats("claude", 1, 0, 0, 0, 7000, 0, 0, 0)
        );
        assertEquals(10000, snapshot.totalDurationMs());
    }

    @Test
    void snapshot_totalPremiumRequests() {
        var snapshot = createSnapshot(
            stats("copilot", 1, 0, 0, 0, 0, 0, 0, 1.5),
            stats("claude", 1, 0, 0, 0, 0, 0, 0, 0.5)
        );
        assertEquals(2.0, snapshot.totalPremiumRequests(), 0.001);
    }

    @Test
    void snapshot_emptyStats() {
        var snapshot = createSnapshot();
        assertEquals(0, snapshot.totalTurns());
        assertEquals(0, snapshot.totalTokens());
        assertEquals(0, snapshot.totalToolCalls());
        assertEquals(0, snapshot.totalDurationMs());
        assertEquals(0.0, snapshot.totalPremiumRequests(), 0.001);
    }

    // ── TimeRange ───────────────────────────────────────────────────────

    @Test
    void timeRange_week() {
        assertEquals("7 days", UsageStatisticsData.TimeRange.WEEK_7.label());
        assertEquals(7, UsageStatisticsData.TimeRange.WEEK_7.days());
        assertEquals(LocalDate.now().minusDays(6), UsageStatisticsData.TimeRange.WEEK_7.startDate());
    }

    @Test
    void timeRange_month() {
        assertEquals("30 days", UsageStatisticsData.TimeRange.MONTH_30.label());
        assertEquals(30, UsageStatisticsData.TimeRange.MONTH_30.days());
        assertEquals(LocalDate.now().minusDays(29), UsageStatisticsData.TimeRange.MONTH_30.startDate());
    }

    @Test
    void timeRange_quarter() {
        assertEquals("90 days", UsageStatisticsData.TimeRange.QUARTER_90.label());
        assertEquals(90, UsageStatisticsData.TimeRange.QUARTER_90.days());
        assertEquals(LocalDate.now().minusDays(89), UsageStatisticsData.TimeRange.QUARTER_90.startDate());
    }

    @Test
    void timeRange_all() {
        assertEquals("All time", UsageStatisticsData.TimeRange.ALL.label());
        assertEquals(-1, UsageStatisticsData.TimeRange.ALL.days());
        assertEquals(LocalDate.of(2020, 1, 1), UsageStatisticsData.TimeRange.ALL.startDate());
    }

    // ── Metric ──────────────────────────────────────────────────────────

    @Test
    void metric_displayNames() {
        assertEquals("Premium Requests", UsageStatisticsData.Metric.PREMIUM_REQUESTS.displayName());
        assertEquals("Turns", UsageStatisticsData.Metric.TURNS.displayName());
        assertEquals("Tokens", UsageStatisticsData.Metric.TOKENS.displayName());
        assertEquals("Tool Calls", UsageStatisticsData.Metric.TOOL_CALLS.displayName());
        assertEquals("Code Changes (lines)", UsageStatisticsData.Metric.CODE_CHANGES.displayName());
        assertEquals("Agent Time", UsageStatisticsData.Metric.AGENT_TIME.displayName());
    }

    @Test
    void metric_valuesCount() {
        assertEquals(6, UsageStatisticsData.Metric.values().length);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static UsageStatisticsData.DailyAgentStats stats(
        String agent, int turns, long inTok, long outTok, int tools,
        long durationMs, int added, int removed, double premium) {
        return new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2024, 1, 1), agent,
            turns, inTok, outTok, tools, durationMs, added, removed, premium);
    }

    private static UsageStatisticsData.StatisticsSnapshot createSnapshot(
        UsageStatisticsData.DailyAgentStats... stats) {
        return new UsageStatisticsData.StatisticsSnapshot(
            List.of(stats),
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 12, 31),
            Set.of("copilot", "claude"),
            Map.of("copilot", "GitHub Copilot", "claude", "Claude"));
    }
}
