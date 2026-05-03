package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.ui.ChatTheme;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Horizontal bar chart that compares one metric across git branches.
 * One bar per branch. Uses the same color palette as the agent-grouped chart
 * (via {@link ChatTheme#agentColorIndex(String)}, which hashes any string key)
 * so each branch gets a distinctive but stable color across reloads.
 *
 * <p>Layout: title (north), bar canvas (center), empty-state label (centered
 * over canvas when there are no rows for the selected metric).
 */
final class BranchComparisonChart extends JBPanel<BranchComparisonChart> {

    private static final int ROW_HEIGHT = JBUI.scale(22);
    private static final int ROW_GAP = JBUI.scale(4);
    private static final int MIN_LABEL_WIDTH = JBUI.scale(160);
    private static final int PREFERRED_LABEL_WIDTH = JBUI.scale(260);
    private static final int VALUE_WIDTH = JBUI.scale(70);
    private static final int CANVAS_PADDING = JBUI.scale(8);
    private static final int MIN_BAR_AREA_WIDTH = JBUI.scale(32);

    private static final Color BAR_TRACK_COLOR = new JBColor(
        com.intellij.ui.Gray._235, com.intellij.ui.Gray._60);
    private static final Color LABEL_COLOR = new JBColor(
        com.intellij.ui.Gray._60, com.intellij.ui.Gray._190);

    private final UsageStatisticsData.Metric metric;
    private final BarCanvas canvas;
    private final JBLabel emptyLabel;

    BranchComparisonChart(String title, UsageStatisticsData.Metric metric) {
        super(new BorderLayout());
        this.metric = metric;

        setPreferredSize(new Dimension(JBUI.scale(350), JBUI.scale(220)));
        setMinimumSize(new Dimension(JBUI.scale(200), JBUI.scale(120)));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        add(titleLabel, BorderLayout.NORTH);

        canvas = new BarCanvas();
        add(canvas, BorderLayout.CENTER);

        emptyLabel = new JBLabel("No branch data");
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setVisible(false);
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }

    void update(UsageStatisticsData.BranchSnapshot snapshot, UsageStatisticsData.BranchSort sort) {
        if (snapshot == null || snapshot.branches().isEmpty()) {
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

        canvas.setData(BranchComparisonSorter.sort(snapshot.branches(), sort, metric));
        revalidate();
        repaint();
    }

    private static String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        long minutes = ms / 60_000;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        long remMin = minutes % 60;
        if (remMin == 0) return hours + "h";
        return hours + "h " + remMin + "m";
    }

    private static String formatLargeNumber(long value) {
        if (value < 1_000) return Long.toString(value);
        if (value < 1_000_000) return String.format("%.1fK", value / 1_000.0);
        return String.format("%.1fM", value / 1_000_000.0);
    }

    private static String formatPremium(double value) {
        if (value < 100) return String.format("%.1f", value);
        return String.format("%.0f", value);
    }

    private final class BarCanvas extends JPanel {
        private transient List<UsageStatisticsData.BranchStats> branches = List.of();

        BarCanvas() {
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(
                CANVAS_PADDING, CANVAS_PADDING, CANVAS_PADDING, CANVAS_PADDING));
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        void setData(List<UsageStatisticsData.BranchStats> branches) {
            this.branches = branches;
            int rowsHeight = branches.size() * (ROW_HEIGHT + ROW_GAP);
            setPreferredSize(new Dimension(0, rowsHeight + 2 * CANVAS_PADDING));
            revalidate();
        }

        private String formatValue(double value) {
            return switch (metric) {
                case AGENT_TIME -> formatDuration((long) value);
                case TOKENS, TOOL_CALLS, CODE_CHANGES, TURNS -> formatLargeNumber((long) value);
                case PREMIUM_REQUESTS -> formatPremium(value);
            };
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAt(event.getY());
            if (row < 0) return null;
            return branches.get(row).branch();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (branches.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int width = getWidth();
                int labelWidth = labelWidth(width);
                int barAreaX = CANVAS_PADDING + labelWidth;
                int barAreaWidth = width - barAreaX - VALUE_WIDTH - CANVAS_PADDING;
                if (barAreaWidth < MIN_BAR_AREA_WIDTH) return;

                // Find max for bar scaling. All bars are scaled to the largest value
                // so the leader stretches the full width.
                double maxValue = branches.stream()
                    .mapToDouble(branch -> BranchComparisonSorter.valueFor(metric, branch))
                    .max().orElse(0);
                if (maxValue <= 0) return;

                FontMetrics fm = g2.getFontMetrics();
                int textBaselineOffset = (ROW_HEIGHT + fm.getAscent()) / 2 - JBUI.scale(2);

                int y = CANVAS_PADDING;
                for (UsageStatisticsData.BranchStats branch : branches) {
                    double value = BranchComparisonSorter.valueFor(metric, branch);

                    // Branch label (left), truncated with ellipsis if too long
                    g2.setColor(LABEL_COLOR);
                    String label = truncate(g2, branch.branch(), labelWidth - JBUI.scale(8));
                    g2.drawString(label, CANVAS_PADDING, y + textBaselineOffset);

                    // Bar
                    int barWidth = (int) (barAreaWidth * (value / maxValue));
                    g2.setColor(BAR_TRACK_COLOR);
                    g2.fillRect(barAreaX, y + JBUI.scale(4), barAreaWidth, ROW_HEIGHT - JBUI.scale(8));

                    int colorIndex = ChatTheme.INSTANCE.agentColorIndex(branch.branch());
                    JBColor barColor = ChatTheme.INSTANCE.getSA_COLORS()[colorIndex];
                    g2.setColor(barColor);
                    g2.fillRect(barAreaX, y + JBUI.scale(4), barWidth, ROW_HEIGHT - JBUI.scale(8));

                    // Value (right)
                    g2.setColor(LABEL_COLOR);
                    String valueStr = formatValue(value);
                    int valueX = barAreaX + barAreaWidth + JBUI.scale(4);
                    g2.drawString(valueStr, valueX, y + textBaselineOffset);

                    y += ROW_HEIGHT + ROW_GAP;
                }
            } finally {
                g2.dispose();
            }
        }

        private int labelWidth(int canvasWidth) {
            int availableBeforeValue = Math.max(0, canvasWidth - VALUE_WIDTH - (2 * CANVAS_PADDING));
            int widthForWideLayouts = Math.clamp(canvasWidth / 3, MIN_LABEL_WIDTH, PREFERRED_LABEL_WIDTH);
            return (int) Math.clamp((long) availableBeforeValue - MIN_BAR_AREA_WIDTH, 0L, widthForWideLayouts);
        }

        private int rowAt(int y) {
            int relativeY = y - CANVAS_PADDING;
            if (relativeY < 0) return -1;
            int rowStride = ROW_HEIGHT + ROW_GAP;
            int row = relativeY / rowStride;
            if (row >= branches.size()) return -1;
            return relativeY % rowStride < ROW_HEIGHT ? row : -1;
        }

        private String truncate(Graphics2D g2, String text, int maxWidth) {
            FontMetrics fm = g2.getFontMetrics();
            if (maxWidth <= 0) return "";
            if (fm.stringWidth(text) <= maxWidth) return text;
            String ellipsis = "…";
            int ellipsisWidth = fm.stringWidth(ellipsis);
            if (ellipsisWidth > maxWidth) return "";
            for (int i = text.length() - 1; i > 0; i--) {
                String candidate = text.substring(0, i);
                if (fm.stringWidth(candidate) + ellipsisWidth <= maxWidth) {
                    return candidate + ellipsis;
                }
            }
            return ellipsis;
        }
    }
}
