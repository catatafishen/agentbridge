package com.github.catatafishen.agentbridge.ui.statistics;

import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for helper methods in {@link UsageStatisticsChart},
 * including the newly extracted package-private static methods.
 */
class UsageStatisticsChartTest {

    /**
     * Pre-initialize JBUI scale factors so that the static initializer in
     * {@link UsageStatisticsChart} (which calls {@code JBUI.scale()}) does not
     * fail in a headless test environment.
     */
    @BeforeAll
    static void initJBUIScale() {
        JBUIScale.setSystemScaleFactor(1.0f);
        JBUIScale.setUserScaleFactorForTest(1.0f);
    }

    // ── reflection helpers ──────────────────────────────────────────────

    private static String invokeFormatCompact(long value) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod("formatCompact", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    private static String invokeFormatDuration(long minutes) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod("formatDuration", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, minutes);
    }

    private static String invokeFormatYLabel(long value, UsageStatisticsData.Metric metric) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod(
            "formatYLabel", long.class, UsageStatisticsData.Metric.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value, metric);
    }

    private static List<?> invokeFillDateRange(Map<LocalDate, Long> valuesByDate,
                                               LocalDate start, LocalDate end) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod(
            "fillDateRange", Map.class, LocalDate.class, LocalDate.class);
        m.setAccessible(true);
        return (List<?>) m.invoke(null, valuesByDate, start, end);
    }

    /**
     * Extract the {@code y} field from a private {@code DataPoint} record via reflection.
     */
    private static long dataPointY(Object dataPoint) throws Exception {
        Field f = dataPoint.getClass().getDeclaredField("y");
        f.setAccessible(true);
        return f.getLong(dataPoint);
    }

    /**
     * Extract the {@code date} field from a private {@code DataPoint} record via reflection.
     */
    private static LocalDate dataPointDate(Object dataPoint) throws Exception {
        Field f = dataPoint.getClass().getDeclaredField("date");
        f.setAccessible(true);
        return (LocalDate) f.get(dataPoint);
    }

    // ── test data helper ────────────────────────────────────────────────

    private static UsageStatisticsData.DailyAgentStats makeStats(
        int turns, long inputTokens, long outputTokens, int toolCalls,
        long durationMs, int linesAdded, int linesRemoved, double premiumRequests) {
        return new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2025, 1, 1), "agent-1",
            turns, inputTokens, outputTokens, toolCalls,
            durationMs, linesAdded, linesRemoved, premiumRequests
        );
    }

    private static final JBColor TEST_COLOR = new JBColor(0x0000FF, 0x0000FF);

    private static UsageStatisticsChart.DataSeries series(UsageStatisticsChart.DataPoint... points) {
        return new UsageStatisticsChart.DataSeries("test-agent", TEST_COLOR, List.of(points));
    }

    private static UsageStatisticsChart.DataPoint pt(long x, long y) {
        return new UsageStatisticsChart.DataPoint(x, y, LocalDate.of(2025, 1, 1));
    }

    // ── formatCompact tests ─────────────────────────────────────────────

    @Test
    void formatCompact_zero() throws Exception {
        assertEquals("0", invokeFormatCompact(0));
    }

    @Test
    void formatCompact_smallValue() throws Exception {
        assertEquals("42", invokeFormatCompact(42));
    }

    @Test
    void formatCompact_justBelowThousand() throws Exception {
        assertEquals("999", invokeFormatCompact(999));
    }

    @Test
    void formatCompact_exactlyOneThousand() throws Exception {
        assertEquals("1K", invokeFormatCompact(1000));
    }

    @Test
    void formatCompact_fractionalThousand() throws Exception {
        assertEquals("1.5K", invokeFormatCompact(1500));
    }

    @Test
    void formatCompact_exactlyTwoThousand() throws Exception {
        assertEquals("2K", invokeFormatCompact(2000));
    }

    @Test
    void formatCompact_exactlyOneMillion() throws Exception {
        assertEquals("1M", invokeFormatCompact(1_000_000));
    }

    @Test
    void formatCompact_fractionalMillion() throws Exception {
        assertEquals("1.5M", invokeFormatCompact(1_500_000));
    }

    @Test
    void formatCompact_negativeValue() throws Exception {
        assertEquals("-1.5K", invokeFormatCompact(-1500));
    }

    @Test
    void formatCompact_largeThousand() throws Exception {
        // 999_999 → 1000.0K which is not an integer, so String.format("%.1fK", 1000.0) → "1000.0K"
        // Actually 999_999 / 1000.0 = 999.999 → not == 999 → "1000.0K"
        String result = invokeFormatCompact(999_999);
        assertTrue(result.endsWith("K"), "Expected a K suffix for 999999, got: " + result);
    }

    // ── formatDuration tests ────────────────────────────────────────────

    @Test
    void formatDuration_zero() throws Exception {
        assertEquals("0m", invokeFormatDuration(0));
    }

    @Test
    void formatDuration_singleMinute() throws Exception {
        assertEquals("1m", invokeFormatDuration(1));
    }

    @Test
    void formatDuration_underOneHour() throws Exception {
        assertEquals("45m", invokeFormatDuration(45));
    }

    @Test
    void formatDuration_exactlyOneHour() throws Exception {
        assertEquals("1h", invokeFormatDuration(60));
    }

    @Test
    void formatDuration_hourAndMinutes() throws Exception {
        assertEquals("1h 30m", invokeFormatDuration(90));
    }

    @Test
    void formatDuration_exactlyTwoHours() throws Exception {
        assertEquals("2h", invokeFormatDuration(120));
    }

    // ── formatYLabel tests ──────────────────────────────────────────────

    @Test
    void formatYLabel_agentTimeMetricDelegatesToDuration() throws Exception {
        assertEquals("1h 30m", invokeFormatYLabel(90, UsageStatisticsData.Metric.AGENT_TIME));
    }

    @Test
    void formatYLabel_nonAgentTimeMetricDelegatesToCompact() throws Exception {
        assertEquals("1.5K", invokeFormatYLabel(1500, UsageStatisticsData.Metric.TOKENS));
    }

    @Test
    void formatYLabel_turnsMetricDelegatesToCompact() throws Exception {
        assertEquals("42", invokeFormatYLabel(42, UsageStatisticsData.Metric.TURNS));
    }

    // ── fillDateRange tests ─────────────────────────────────────────────

    @Test
    void fillDateRange_emptyMap_allZeros() throws Exception {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 1, 3);

        List<?> points = invokeFillDateRange(Map.of(), start, end);

        assertEquals(3, points.size());
        for (Object pt : points) {
            assertEquals(0L, dataPointY(pt));
        }
    }

    @Test
    void fillDateRange_gapsFilledWithZeros() throws Exception {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 1, 5);

        Map<LocalDate, Long> values = new LinkedHashMap<>();
        values.put(LocalDate.of(2025, 1, 1), 10L);
        values.put(LocalDate.of(2025, 1, 3), 30L);
        values.put(LocalDate.of(2025, 1, 5), 50L);

        List<?> points = invokeFillDateRange(values, start, end);

        assertEquals(5, points.size());
        assertEquals(10L, dataPointY(points.getFirst()));
        assertEquals(0L, dataPointY(points.get(1)));  // Jan 2 – gap
        assertEquals(30L, dataPointY(points.get(2)));
        assertEquals(0L, dataPointY(points.get(3)));  // Jan 4 – gap
        assertEquals(50L, dataPointY(points.get(4)));
    }

    @Test
    void fillDateRange_singleDay() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 15);
        Map<LocalDate, Long> values = Map.of(day, 99L);

        List<?> points = invokeFillDateRange(values, day, day);

        assertEquals(1, points.size());
        assertEquals(99L, dataPointY(points.getFirst()));
        assertEquals(day, dataPointDate(points.getFirst()));
    }

    @Test
    void fillDateRange_startEqualsEnd_noData() throws Exception {
        LocalDate day = LocalDate.of(2025, 3, 10);

        List<?> points = invokeFillDateRange(Map.of(), day, day);

        assertEquals(1, points.size());
        assertEquals(0L, dataPointY(points.getFirst()));
    }

    @Test
    void fillDateRange_datesAreInOrder() throws Exception {
        LocalDate start = LocalDate.of(2025, 2, 1);
        LocalDate end = LocalDate.of(2025, 2, 4);

        List<?> points = invokeFillDateRange(Map.of(), start, end);

        assertEquals(4, points.size());
        assertEquals(LocalDate.of(2025, 2, 1), dataPointDate(points.get(0)));
        assertEquals(LocalDate.of(2025, 2, 2), dataPointDate(points.get(1)));
        assertEquals(LocalDate.of(2025, 2, 3), dataPointDate(points.get(2)));
        assertEquals(LocalDate.of(2025, 2, 4), dataPointDate(points.get(3)));
    }

    // ── extractMetricValue tests ────────────────────────────────────────

    @Test
    void extractMetricValue_premiumRequests() {
        var stats = makeStats(0, 0, 0, 0, 0, 0, 0, 3.7);
        assertEquals(4L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.PREMIUM_REQUESTS, stats));
    }

    @Test
    void extractMetricValue_premiumRequests_roundsDown() {
        var stats = makeStats(0, 0, 0, 0, 0, 0, 0, 3.2);
        assertEquals(3L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.PREMIUM_REQUESTS, stats));
    }

    @Test
    void extractMetricValue_turns() {
        var stats = makeStats(15, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(15L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.TURNS, stats));
    }

    @Test
    void extractMetricValue_tokens_sumsInputAndOutput() {
        var stats = makeStats(0, 1000, 2500, 0, 0, 0, 0, 0);
        assertEquals(3500L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.TOKENS, stats));
    }

    @Test
    void extractMetricValue_toolCalls() {
        var stats = makeStats(0, 0, 0, 42, 0, 0, 0, 0);
        assertEquals(42L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.TOOL_CALLS, stats));
    }

    @Test
    void extractMetricValue_codeChanges_sumsAddedAndRemoved() {
        var stats = makeStats(0, 0, 0, 0, 0, 100, 50, 0);
        assertEquals(150L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.CODE_CHANGES, stats));
    }

    @Test
    void extractMetricValue_agentTime_convertsToMinutes() {
        // 90_000 ms = 1.5 minutes → integer division → 1 minute
        var stats = makeStats(0, 0, 0, 0, 90_000, 0, 0, 0);
        assertEquals(1L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.AGENT_TIME, stats));
    }

    @Test
    void extractMetricValue_agentTime_exactMinutes() {
        // 120_000 ms = 2 minutes
        var stats = makeStats(0, 0, 0, 0, 120_000, 0, 0, 0);
        assertEquals(2L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.AGENT_TIME, stats));
    }

    @Test
    void extractMetricValue_allMetricsCovered() {
        // Verify all six metrics produce distinct correct values from a single stats record
        var stats = new UsageStatisticsData.DailyAgentStats(
            LocalDate.of(2025, 1, 1), "test",
            10,         // turns
            500,        // inputTokens
            300,        // outputTokens
            25,         // toolCalls
            180_000,    // durationMs (3 min)
            40,         // linesAdded
            20,         // linesRemoved
            2.0         // premiumRequests
        );

        assertEquals(2L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.PREMIUM_REQUESTS, stats));
        assertEquals(10L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.TURNS, stats));
        assertEquals(800L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.TOKENS, stats));
        assertEquals(25L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.TOOL_CALLS, stats));
        assertEquals(60L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.CODE_CHANGES, stats));
        assertEquals(3L, UsageStatisticsChart.extractMetricValue(
            UsageStatisticsData.Metric.AGENT_TIME, stats));
    }

    // ── computeMinMax tests ─────────────────────────────────────────────

    @Test
    void computeMinMax_emptySeries_sensibleDefaults() {
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of());
        // xMin, xMax default to 0; yMin=0, yMax = yMin+1 = 1
        assertEquals(0.0, bounds[0], "xMin");
        assertEquals(0.0, bounds[1], "xMax");
        assertEquals(0.0, bounds[2], "yMin");
        assertEquals(1.0, bounds[3], "yMax");
    }

    @Test
    void computeMinMax_singleDataPoint() {
        var s = series(pt(100, 50));
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of(s));

        assertEquals(100.0, bounds[0], "xMin");
        assertEquals(100.0, bounds[1], "xMax");
        // yMin = min(0, 50) = 0; yMax = max(0, 50) = 50
        // padding = 50/10 = 5; yMax = 55
        assertEquals(0.0, bounds[2], "yMin");
        assertEquals(55.0, bounds[3], "yMax");
    }

    @Test
    void computeMinMax_multipleSeries_globalBounds() {
        var s1 = series(pt(10, 20), pt(30, 40));
        var s2 = series(pt(15, 5), pt(25, 100));
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of(s1, s2));

        assertEquals(10.0, bounds[0], "xMin");
        assertEquals(30.0, bounds[1], "xMax");
        // yMin = min(0, 5) = 0; yMax = max(0, 100) = 100
        // padding = 100/10 = 10; yMax = 110
        assertEquals(0.0, bounds[2], "yMin");
        assertEquals(110.0, bounds[3], "yMax");
    }

    @Test
    void computeMinMax_negativeValues_paddingOnBothSides() {
        var s = series(pt(0, -30), pt(100, 70));
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of(s));

        assertEquals(0.0, bounds[0], "xMin");
        assertEquals(100.0, bounds[1], "xMax");
        // yMin = min(0, -30) = -30; yMax = max(0, 70) = 70
        // padding = (70 - (-30))/10 = 10
        // yMax = 80; yMin = -40 (since yMin < 0)
        assertEquals(-40.0, bounds[2], "yMin");
        assertEquals(80.0, bounds[3], "yMax");
    }

    @Test
    void computeMinMax_allZeroValues_noDivisionByZero() {
        var s = series(pt(0, 0), pt(100, 0));
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of(s));

        assertEquals(0.0, bounds[0], "xMin");
        assertEquals(100.0, bounds[1], "xMax");
        // yMin=0, yMax=0, so yMax == yMin → yMax = yMin + 1 = 1
        assertEquals(0.0, bounds[2], "yMin");
        assertEquals(1.0, bounds[3], "yMax");
    }

    @Test
    void computeMinMax_singleSeriesAllPositive_zeroBaseline() {
        // Even though min data value is 10, yMin should still be 0 (zero baseline)
        var s = series(pt(0, 10), pt(50, 20));
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of(s));

        assertEquals(0.0, bounds[2], "yMin should include zero baseline");
        assertTrue(bounds[3] > 20.0, "yMax should be padded above max value");
    }

    @Test
    void computeMinMax_emptySeriesInList() {
        // A series with no data points mixed with a populated series
        var empty = new UsageStatisticsChart.DataSeries("empty", TEST_COLOR, List.of());
        var populated = series(pt(10, 50));
        double[] bounds = UsageStatisticsChart.computeMinMax(List.of(empty, populated));

        assertEquals(10.0, bounds[0], "xMin");
        assertEquals(10.0, bounds[1], "xMax");
        assertEquals(0.0, bounds[2], "yMin");
        assertEquals(55.0, bounds[3], "yMax");
    }

    // ── computeNiceTickCount tests ──────────────────────────────────────

    @Test
    void computeNiceTickCount_smallHeight_minimumTickCount() {
        // plotH=30, minSpacing=40 → maxTicks = max(1, 30/40) = max(1, 0) = 1 → min(1, 5) = 1
        assertEquals(1, UsageStatisticsChart.computeNiceTickCount(30, 40));
    }

    @Test
    void computeNiceTickCount_moderateHeight() {
        // plotH=120, minSpacing=40 → maxTicks = max(1, 3) = 3 → min(3, 5) = 3
        assertEquals(3, UsageStatisticsChart.computeNiceTickCount(120, 40));
    }

    @Test
    void computeNiceTickCount_largeHeight_cappedAtFive() {
        // plotH=500, minSpacing=40 → maxTicks = max(1, 12) = 12 → min(12, 5) = 5
        assertEquals(5, UsageStatisticsChart.computeNiceTickCount(500, 40));
    }

    @Test
    void computeNiceTickCount_exactlyOneTickSpacing() {
        // plotH=40, minSpacing=40 → maxTicks = max(1, 1) = 1 → min(1, 5) = 1
        assertEquals(1, UsageStatisticsChart.computeNiceTickCount(40, 40));
    }

    @Test
    void computeNiceTickCount_zeroHeight_doesNotCrash() {
        // plotH=0 → maxTicks = max(1, 0) = 1 → min(1, 5) = 1
        assertEquals(1, UsageStatisticsChart.computeNiceTickCount(0, 40));
    }

    @Test
    void computeNiceTickCount_negativeHeight_doesNotCrash() {
        // plotH=-10 → maxTicks = max(1, -10/40) = max(1, 0 or -1) = 1 → min(1, 5) = 1
        assertEquals(1, UsageStatisticsChart.computeNiceTickCount(-10, 40));
    }

    @Test
    void computeNiceTickCount_zeroMinSpacing_doesNotCrash() {
        // Guard: scaledMinSpacing <= 0 → returns 1
        assertEquals(1, UsageStatisticsChart.computeNiceTickCount(200, 0));
    }

    @Test
    void computeNiceTickCount_negativeMinSpacing_doesNotCrash() {
        assertEquals(1, UsageStatisticsChart.computeNiceTickCount(200, -5));
    }

    @Test
    void computeNiceTickCount_twoTickSpacings() {
        // plotH=80, minSpacing=40 → maxTicks = max(1, 2) = 2 → min(2, 5) = 2
        assertEquals(2, UsageStatisticsChart.computeNiceTickCount(80, 40));
    }
}
