package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.ui.ChatTheme;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;

/**
 * Main panel displaying usage statistics with a time-range selector, a legend,
 * and six metric charts arranged in a 2×3 grid.
 */
class UsageStatisticsPanel extends JBPanel<UsageStatisticsPanel> {

    private static final Logger LOG = Logger.getInstance(UsageStatisticsPanel.class);

    private final Project project;
    private final ComboBox<UsageStatisticsData.TimeRange> rangeCombo;
    private final Map<UsageStatisticsData.Metric, UsageStatisticsChart> charts = new EnumMap<>(UsageStatisticsData.Metric.class);
    private final JPanel legendContainer;

    UsageStatisticsPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(12));

        // --- NORTH: toolbar with time-range selector + legend ---
        JPanel toolbar = new JPanel(new BorderLayout());

        JPanel selectorPanel = new JPanel();
        selectorPanel.setLayout(new BoxLayout(selectorPanel, BoxLayout.X_AXIS));
        selectorPanel.add(new JBLabel("Period:"));
        selectorPanel.add(Box.createHorizontalStrut(JBUI.scale(6)));

        rangeCombo = new ComboBox<>(UsageStatisticsData.TimeRange.values());
        rangeCombo.setSelectedItem(UsageStatisticsData.TimeRange.MONTH_30);
        rangeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof UsageStatisticsData.TimeRange timeRange) {
                    setText(timeRange.label());
                }
                return this;
            }
        });
        rangeCombo.addActionListener(e -> {
            UsageStatisticsData.TimeRange selected = (UsageStatisticsData.TimeRange) rangeCombo.getSelectedItem();
            if (selected != null) {
                loadData(selected);
            }
        });
        selectorPanel.add(rangeCombo);
        toolbar.add(selectorPanel, BorderLayout.WEST);

        legendContainer = new JPanel();
        legendContainer.setLayout(new BoxLayout(legendContainer, BoxLayout.X_AXIS));
        toolbar.add(legendContainer, BorderLayout.EAST);

        add(toolbar, BorderLayout.NORTH);

        // --- CENTER: 2×3 grid of charts ---
        JPanel chartGrid = new JPanel(new GridLayout(2, 3, JBUI.scale(12), JBUI.scale(12)));
        for (UsageStatisticsData.Metric metric : UsageStatisticsData.Metric.values()) {
            UsageStatisticsChart chart = new UsageStatisticsChart(metric.displayName(), metric);
            charts.put(metric, chart);
            chartGrid.add(chart);
        }
        add(chartGrid, BorderLayout.CENTER);

        // --- Initial load ---
        loadData(UsageStatisticsData.TimeRange.MONTH_30);
    }

    private void loadData(UsageStatisticsData.TimeRange range) {
        // Use ModalityState.any() so the callback runs even inside a modal dialog
        ModalityState modality = ModalityState.any();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                UsageStatisticsData.StatisticsSnapshot snapshot = UsageStatisticsLoader.load(project, range);
                LOG.info("Statistics panel: loaded " + snapshot.dailyStats().size()
                    + " daily stats, " + snapshot.agentIds().size() + " agents");
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    updateCharts(snapshot);
                }, modality);
            } catch (Exception e) {
                LOG.error("Statistics panel: failed to load data for range " + range, e);
            }
        });
    }

    private void updateCharts(UsageStatisticsData.StatisticsSnapshot snapshot) {
        for (UsageStatisticsChart chart : charts.values()) {
            chart.update(snapshot);
        }
        legendContainer.removeAll();
        JPanel legend = buildLegend(snapshot);
        legendContainer.add(legend);
        legendContainer.revalidate();
        legendContainer.repaint();
    }

    private JPanel buildLegend(UsageStatisticsData.StatisticsSnapshot snapshot) {
        JPanel legend = new JPanel();
        legend.setLayout(new BoxLayout(legend, BoxLayout.X_AXIS));

        for (String agentId : snapshot.agentIds()) {
            int colorIndex = ChatTheme.INSTANCE.agentColorIndex(agentId);
            JBColor color = ChatTheme.INSTANCE.getSA_COLORS()[colorIndex];
            String displayName = snapshot.agentDisplayNames().get(agentId);

            if (legend.getComponentCount() > 0) {
                legend.add(Box.createHorizontalStrut(JBUI.scale(12)));
            }

            legend.add(new JLabel(new ColorDotIcon(color, JBUI.scale(8))));
            legend.add(Box.createHorizontalStrut(JBUI.scale(4)));
            legend.add(new JBLabel(displayName != null ? displayName : agentId));
        }

        return legend;
    }

    /**
     * Small filled-circle icon used in the legend.
     */
    private record ColorDotIcon(Color color, int size) implements Icon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x, y, size, size);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
