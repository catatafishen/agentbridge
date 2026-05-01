package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.ui.ChatTheme;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chart component that renders usage metrics over time using Java2D.
 * Each agent gets its own colored line and fill area rendered independently and filled to the baseline.
 */
class UsageStatisticsChart extends JBPanel<UsageStatisticsChart> {

    private static final Logger LOG = Logger.getInstance(UsageStatisticsChart.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM/dd");

    private static final int MARGIN_LEFT = JBUI.scale(48);
    private static final int MARGIN_RIGHT = JBUI.scale(12);
    private static final int MARGIN_TOP = JBUI.scale(8);
    private static final int MARGIN_BOTTOM = JBUI.scale(24);

    private static final Color GRID_LINE_COLOR = new JBColor(
        new Color(220, 220, 220), new Color(60, 60, 60));
    private static final Color GRID_LABEL_COLOR = new JBColor(
        new Color(120, 120, 120), new Color(150, 150, 150));

    private final UsageStatisticsData.Metric metric;
    private final ChartCanvas canvas;
    private final JBLabel emptyLabel;

    UsageStatisticsChart(String title, UsageStatisticsData.Metric metric) {
        super(new BorderLayout());
        this.metric = metric;

        setPreferredSize(JBUI.size(350, 200));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        add(titleLabel, BorderLayout.NORTH);

        canvas = new ChartCanvas(metric);
        add(canvas, BorderLayout.CENTER);

        emptyLabel = new JBLabel("No data");
        emptyLabel.setHorizontalAlignment(JBLabel.CENTER);
        emptyLabel.setVisible(false);
    }

    void update(UsageStatisticsData.StatisticsSnapshot snapshot) {
        if (snapshot == null || snapshot.dailyStats().isEmpty()) {
            remove(canvas);
            emptyLabel.setVisible(true);
            add(emptyLabel, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        emptyLabel.setVisible(false);
        remove(emptyLabel);
        add(canvas, BorderLayout.CENTER);

        LocalDate rangeStart = snapshot.startDate();
        LocalDate rangeEnd = snapshot.endDate();

        List<DataSeries> seriesList;
        if (metric == UsageStatisticsData.Metric.CODE_CHANGES) {
            seriesList = buildCodeChangeSeries(snapshot.dailyStats(), rangeStart, rangeEnd);
        } else {
            seriesList = buildStandardSeries(snapshot.dailyStats(), rangeStart, rangeEnd);
        }

        canvas.setData(seriesList);

        long totalPoints = seriesList.stream().mapToInt(s -> s.points.size()).sum();
        LOG.info("Chart '" + metric + "': " + seriesList.size() + " series, " + totalPoints + " points");

        revalidate();
        repaint();
    }

    private List<DataSeries> buildStandardSeries(
        List<UsageStatisticsData.DailyAgentStats> dailyStats,
        LocalDate rangeStart, LocalDate rangeEnd) {

        Map<String, List<UsageStatisticsData.DailyAgentStats>> byAgent = dailyStats.stream()
            .collect(Collectors.groupingBy(UsageStatisticsData.DailyAgentStats::agentId));

        List<DataSeries> seriesList = new ArrayList<>();
        for (var entry : byAgent.entrySet()) {
            String agentId = entry.getKey();

            Map<LocalDate, Long> valuesByDate = new LinkedHashMap<>();
            for (UsageStatisticsData.DailyAgentStats stats : entry.getValue()) {
                valuesByDate.merge(stats.date(), extractMetricValue(stats), Long::sum);
            }

            seriesList.add(new DataSeries(agentId, agentColor(agentId),
                fillDateRange(valuesByDate, rangeStart, rangeEnd)));
        }
        return seriesList;
    }

    private List<DataSeries> buildCodeChangeSeries(
        List<UsageStatisticsData.DailyAgentStats> dailyStats,
        LocalDate rangeStart, LocalDate rangeEnd) {

        Map<String, List<UsageStatisticsData.DailyAgentStats>> byAgent = dailyStats.stream()
            .collect(Collectors.groupingBy(UsageStatisticsData.DailyAgentStats::agentId));

        List<DataSeries> seriesList = new ArrayList<>();
        for (var entry : byAgent.entrySet()) {
            String agentId = entry.getKey();
            JBColor color = agentColor(agentId);

            Map<LocalDate, Long> addByDate = new LinkedHashMap<>();
            Map<LocalDate, Long> removeByDate = new LinkedHashMap<>();
            for (UsageStatisticsData.DailyAgentStats stats : entry.getValue()) {
                addByDate.merge(stats.date(), (long) stats.linesAdded(), Long::sum);
                removeByDate.merge(stats.date(), (long) stats.linesRemoved(), Long::sum);
            }

            seriesList.add(new DataSeries(agentId, color,
                fillDateRange(addByDate, rangeStart, rangeEnd)));
            // Negate removals so they render below the zero baseline
            Map<LocalDate, Long> negRemoveByDate = new LinkedHashMap<>();
            removeByDate.forEach((date, val) -> negRemoveByDate.put(date, -val));
            seriesList.add(new DataSeries(agentId, color,
                fillDateRange(negRemoveByDate, rangeStart, rangeEnd)));
        }
        return seriesList;
    }

    private static JBColor agentColor(String agentId) {
        int colorIndex = ChatTheme.INSTANCE.agentColorIndex(agentId);
        return ChatTheme.INSTANCE.getSA_COLORS()[colorIndex];
    }

    private static List<DataPoint> fillDateRange(Map<LocalDate, Long> valuesByDate,
                                                 LocalDate start, LocalDate end) {
        List<DataPoint> points = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            long x = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long y = valuesByDate.getOrDefault(d, 0L);
            points.add(new DataPoint(x, y, d));
        }
        return points;
    }

    private long extractMetricValue(UsageStatisticsData.DailyAgentStats stats) {
        return extractMetricValue(metric, stats);
    }

    static long extractMetricValue(UsageStatisticsData.Metric metric, UsageStatisticsData.DailyAgentStats stats) {
        return switch (metric) {
            case PREMIUM_REQUESTS -> Math.round(stats.premiumRequests());
            case TURNS -> stats.turns();
            case TOKENS -> stats.inputTokens() + stats.outputTokens();
            case TOOL_CALLS -> stats.toolCalls();
            case CODE_CHANGES -> stats.linesAdded() + stats.linesRemoved();
            case AGENT_TIME -> stats.durationMs() / 60_000;
        };
    }

    record DataPoint(long x, long y, LocalDate date) {
    }

    record DataSeries(String agentId, JBColor color, List<DataPoint> points) {
    }

    record Bounds(long xMin, long xMax, long yMin, long yMax) {
    }

    /**
     * Custom JPanel that renders the chart using Java2D.
     */
    private static final class ChartCanvas extends JPanel {

        private final UsageStatisticsData.Metric metric;
        private transient List<DataSeries> seriesList = List.of();
        private long xMin;
        private long xMax;
        private long yMin;
        private long yMax;

        ChartCanvas(UsageStatisticsData.Metric metric) {
            this.metric = metric;
            // Must stay opaque so Swing's RepaintManager always
            // repaints this component when repaint() is called.
        }

        void setData(List<DataSeries> seriesList) {
            this.seriesList = seriesList;
            computeMinMax();
            repaint();
        }

        private void computeMinMax() {
            Bounds bounds = UsageStatisticsChart.computeMinMax(seriesList);
            xMin = bounds.xMin();
            xMax = bounds.xMax();
            yMin = bounds.yMin();
            yMax = bounds.yMax();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (seriesList.isEmpty()) return;

            int w = getWidth();
            int h = getHeight();

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int plotLeft = MARGIN_LEFT;
                int plotRight = w - MARGIN_RIGHT;
                int plotTop = MARGIN_TOP;
                int plotBottom = h - MARGIN_BOTTOM;
                int plotW = plotRight - plotLeft;
                int plotH = plotBottom - plotTop;

                if (plotW <= 0 || plotH <= 0) return;

                paintGrid(g2, plotLeft, plotTop, plotW, plotH);
                paintData(g2, plotLeft, plotTop, plotW, plotH);
                paintBorder(g2, plotLeft, plotTop, plotW, plotH);
            } finally {
                g2.dispose();
            }
        }

        private void paintGrid(Graphics2D g2, int plotLeft, int plotTop, int plotW, int plotH) {
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, (float) JBUI.scale(10)));
            FontMetrics fm = g2.getFontMetrics();
            int plotBottom = plotTop + plotH;
            int plotRight = plotLeft + plotW;

            // Y-axis grid lines
            int yTicks = UsageStatisticsChart.computeNiceTickCount(plotH, JBUI.scale(40));
            if (yTicks > 0 && yMax > yMin) {
                for (int i = 0; i <= yTicks; i++) {
                    long yVal = yMin + (yMax - yMin) * i / yTicks;
                    int py = plotBottom - (int) ((double) (yVal - yMin) / (yMax - yMin) * plotH);
                    g2.setColor(GRID_LINE_COLOR);
                    g2.drawLine(plotLeft, py, plotRight, py);
                    g2.setColor(GRID_LABEL_COLOR);
                    String label = formatYLabel(yVal, metric);
                    int labelW = fm.stringWidth(label);
                    g2.drawString(label, plotLeft - labelW - 4, py + fm.getAscent() / 2);
                }
            }

            // Draw a prominent zero baseline when the chart spans negative values
            if (yMin < 0 && yMax > 0) {
                int zeroY = plotBottom - (int) ((double) (-yMin) / (yMax - yMin) * plotH);
                g2.setColor(GRID_LABEL_COLOR);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(plotLeft, zeroY, plotRight, zeroY);
                g2.setStroke(new BasicStroke(1.0f));
            }

            // X-axis date labels
            List<LocalDate> dates = collectUniqueDates();
            if (!dates.isEmpty()) {
                g2.setColor(GRID_LABEL_COLOR);
                int labelY = plotBottom + fm.getAscent() + 4;
                int prevLabelEnd = Integer.MIN_VALUE;
                for (LocalDate date : dates) {
                    long xVal = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    int px = plotLeft + mapX(xVal, plotW);
                    String label = date.format(DATE_FMT);
                    int labelW = fm.stringWidth(label);
                    int labelStart = px - labelW / 2;
                    // Skip overlapping labels
                    if (labelStart > prevLabelEnd + 4) {
                        g2.drawString(label, labelStart, labelY);
                        prevLabelEnd = labelStart + labelW;
                    }
                }
            }
        }

        private void paintData(Graphics2D g2, int plotLeft, int plotTop, int plotW, int plotH) {
            int plotBottom = plotTop + plotH;

            for (DataSeries series : seriesList) {
                List<DataPoint> points = series.points;
                if (points.isEmpty()) continue;

                Color lineColor = series.color;
                Color fillColor = new Color(
                    lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 38);

                // Build the line path
                Path2D.Double linePath = new Path2D.Double();
                boolean first = true;
                for (DataPoint pt : points) {
                    int px = plotLeft + mapX(pt.x, plotW);
                    int py = plotBottom - mapY(pt.y, plotH);
                    if (first) {
                        linePath.moveTo(px, py);
                        first = false;
                    } else {
                        linePath.lineTo(px, py);
                    }
                }

                // Build the fill path (extend line to zero baseline, close)
                Path2D.Double fillPath = new Path2D.Double(linePath);
                DataPoint lastPt = points.getLast();
                DataPoint firstPt = points.getFirst();
                int lastPx = plotLeft + mapX(lastPt.x, plotW);
                int firstPx = plotLeft + mapX(firstPt.x, plotW);
                int zeroY = plotBottom - mapY(0, plotH);
                fillPath.lineTo(lastPx, zeroY);
                fillPath.lineTo(firstPx, zeroY);
                fillPath.closePath();

                // Clip to the plot area
                Shape oldClip = g2.getClip();
                g2.clipRect(plotLeft, plotTop, plotW, plotH);

                // Fill area
                g2.setColor(fillColor);
                g2.fill(fillPath);

                // Draw line
                g2.setColor(lineColor);
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(linePath);

                g2.setClip(oldClip);
            }
        }

        private void paintBorder(Graphics2D g2, int plotLeft, int plotTop, int plotW, int plotH) {
            g2.setColor(GRID_LINE_COLOR);
            g2.drawRect(plotLeft, plotTop, plotW, plotH);
        }

        private int mapX(long xVal, int plotW) {
            if (xMax == xMin) return plotW / 2;
            return (int) ((double) (xVal - xMin) / (xMax - xMin) * plotW);
        }

        private int mapY(long yVal, int plotH) {
            if (yMax == yMin) return plotH / 2;
            return (int) ((double) (yVal - yMin) / (yMax - yMin) * plotH);
        }

        private List<LocalDate> collectUniqueDates() {
            return seriesList.stream()
                .flatMap(s -> s.points.stream())
                .map(DataPoint::date)
                .distinct()
                .sorted()
                .toList();
        }
    }

    static Bounds computeMinMax(List<DataSeries> allSeries) {
        boolean first = true;
        long xLo = 0;
        long xHi = 0;
        long yLo = 0;
        long yHi = 0;
        for (DataSeries series : allSeries) {
            for (DataPoint pt : series.points) {
                if (first) {
                    xLo = xHi = pt.x;
                    yLo = yHi = pt.y;
                    first = false;
                } else {
                    xLo = Math.min(xLo, pt.x);
                    xHi = Math.max(xHi, pt.x);
                    yLo = Math.min(yLo, pt.y);
                    yHi = Math.max(yHi, pt.y);
                }
            }
        }
        long xMin = xLo;
        long xMax = xHi;
        // Always include zero on the Y axis so fills are grounded at the baseline
        long yMin = Math.min(0, yLo);
        long yMax = Math.max(0, yHi);
        // Pad top/bottom by 10% so peaks aren't clipped against the border
        if (yMax > yMin) {
            long padding = (yMax - yMin) / 10;
            yMax += padding;
            if (yMin < 0) yMin -= padding;
        } else {
            yMax = yMin + 1;
        }
        return new Bounds(xMin, xMax, yMin, yMax);
    }

    static int computeNiceTickCount(int plotH, int scaledMinSpacing) {
        if (scaledMinSpacing <= 0) return 1;
        int maxTicks = Math.max(1, plotH / scaledMinSpacing);
        return Math.min(maxTicks, 5);
    }

    private static String formatCompact(long value) {
        if (value < 0) return "-" + formatCompact(-value);
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

    private static String formatDuration(long minutes) {
        if (minutes >= 60) {
            long h = minutes / 60;
            long m = minutes % 60;
            return m == 0 ? h + "h" : h + "h " + m + "m";
        }
        return minutes + "m";
    }

    private static String formatYLabel(long value, UsageStatisticsData.Metric metric) {
        if (metric == UsageStatisticsData.Metric.AGENT_TIME) {
            return formatDuration(value);
        }
        return formatCompact(value);
    }
}
