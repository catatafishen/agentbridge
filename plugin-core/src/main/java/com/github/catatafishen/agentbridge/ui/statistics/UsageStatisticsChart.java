package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.ui.ChatTheme;
import com.intellij.ui.JBColor;
import com.intellij.ui.charts.Coordinates;
import com.intellij.ui.charts.XYLineChart;
import com.intellij.ui.charts.XYLineDataset;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chart component that wraps JetBrains' {@link XYLineChart} to display usage
 * metrics over time, with per-agent color coding.
 * <p>
 * {@code com.intellij.ui.charts} is marked {@code @ApiStatus.Experimental} but is the only
 * native JetBrains charting API. It has been stable since IntelliJ 2020.2 and there is no
 * non-experimental alternative. If the API changes in a future platform version, only this
 * class needs updating.
 */
@SuppressWarnings("UnstableApiUsage")
class UsageStatisticsChart extends JBPanel<UsageStatisticsChart> {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM/dd");

    private final UsageStatisticsData.Metric metric;
    private final XYLineChart<Long, Long> chart;
    private final JBLabel emptyLabel;

    UsageStatisticsChart(String title, UsageStatisticsData.Metric metric) {
        super(new BorderLayout());
        this.metric = metric;

        setPreferredSize(JBUI.size(350, 200));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        add(titleLabel, BorderLayout.NORTH);

        chart = new XYLineChart<>();
        configureGrid();
        add(chart.getComponent(), BorderLayout.CENTER);

        emptyLabel = new JBLabel("No data");
        emptyLabel.setHorizontalAlignment(JBLabel.CENTER);
        emptyLabel.setVisible(false);
    }

    void update(UsageStatisticsData.StatisticsSnapshot snapshot) {
        if (snapshot == null || snapshot.dailyStats().isEmpty()) {
            remove(chart.getComponent());
            emptyLabel.setVisible(true);
            add(emptyLabel, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        emptyLabel.setVisible(false);
        remove(emptyLabel);
        add(chart.getComponent(), BorderLayout.CENTER);

        Map<String, List<UsageStatisticsData.DailyAgentStats>> byAgent = snapshot.dailyStats().stream()
            .collect(Collectors.groupingBy(UsageStatisticsData.DailyAgentStats::agentId));

        List<XYLineDataset<Long, Long>> datasets = new ArrayList<>();

        for (Map.Entry<String, List<UsageStatisticsData.DailyAgentStats>> entry : byAgent.entrySet()) {
            String agentId = entry.getKey();
            List<UsageStatisticsData.DailyAgentStats> agentStats = entry.getValue();

            XYLineDataset<Long, Long> dataset = new XYLineDataset<>();
            dataset.setLabel(agentId);
            dataset.setStacked(true);
            dataset.setStroke(new BasicStroke(2.0f));

            int colorIndex = ChatTheme.INSTANCE.agentColorIndex(agentId);
            JBColor agentColor = ChatTheme.INSTANCE.getSA_COLORS()[colorIndex];
            dataset.setLineColor(agentColor);
            dataset.setFillColor(new Color(agentColor.getRed(), agentColor.getGreen(), agentColor.getBlue(), 38));

            for (UsageStatisticsData.DailyAgentStats stats : agentStats) {
                long x = stats.date().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long y = extractMetricValue(stats);
                //noinspection unchecked — varargs generic erasure in XYLineDataset.add(T...)
                dataset.add(Coordinates.of(x, y));
            }

            datasets.add(dataset);
        }

        chart.setDatasets(datasets);
        revalidate();
        repaint();
    }

    private long extractMetricValue(UsageStatisticsData.DailyAgentStats stats) {
        return switch (metric) {
            case PREMIUM_REQUESTS -> (long) stats.premiumRequests();
            case TURNS -> stats.turns();
            case TOKENS -> stats.inputTokens() + stats.outputTokens();
            case TOOL_CALLS -> stats.toolCalls();
            case CODE_CHANGES -> stats.linesAdded() + stats.linesRemoved();
            case AGENT_TIME -> stats.durationMs() / 1000;
        };
    }

    private void configureGrid() {
        chart.getRanges().setXPainter(gl -> {
            long millis = gl.getValue();
            gl.setLabel(LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DATE_FMT));
        });
        chart.getRanges().setYPainter(gl -> gl.setLabel(formatCompact(gl.getValue())));
    }

    private static String formatCompact(long value) {
        if (value >= 1_000_000) {
            double v = value / 1_000_000.0;
            return v == (long) v ? (long) v + "M" : String.format("%.1fM", v);
        }
        if (value >= 1_000) {
            double v = value / 1_000.0;
            return v == (long) v ? (long) v + "K" : String.format("%.1fK", v);
        }
        return Long.toString(value);
    }
}
