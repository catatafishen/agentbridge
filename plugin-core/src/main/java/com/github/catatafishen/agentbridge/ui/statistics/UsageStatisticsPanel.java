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
 * Main panel displaying usage statistics with a time-range selector, a
 * Group-by selector (Agent / Git Branch), and metric charts.
 *
 * <p>Two views, switched via {@link CardLayout}:
 * <ul>
 *   <li>{@code AGENT}: 2×3 grid of {@link UsageStatisticsChart} time-series
 *       (one per metric). Legend shows agents.</li>
 *   <li>{@code GIT_BRANCH}: 2×3 grid of {@link BranchComparisonChart} bar
 *       charts (one per metric) so users can compare per-feature spend.
 *       Bar charts are used instead of time-series because branches are
 *       discrete categories — a stacked or multi-line time chart over many
 *       branches becomes unreadable and "total spend per branch" is the
 *       actual question being answered.</li>
 * </ul>
 */
class UsageStatisticsPanel extends JBPanel<UsageStatisticsPanel> {

    private static final Logger LOG = Logger.getInstance(UsageStatisticsPanel.class);

    private static final String CARD_AGENT = "agent";
    private static final String CARD_BRANCH = "branch";

    private final Project project;
    private final ComboBox<UsageStatisticsData.TimeRange> rangeCombo;
    private final ComboBox<UsageStatisticsData.GroupBy> groupByCombo;

    private final Map<UsageStatisticsData.Metric, UsageStatisticsChart> agentCharts =
        new EnumMap<>(UsageStatisticsData.Metric.class);
    private final Map<UsageStatisticsData.Metric, BranchComparisonChart> branchCharts =
        new EnumMap<>(UsageStatisticsData.Metric.class);

    private final JPanel legendContainer;
    private final JPanel cardPanel;
    private final CardLayout cardLayout;
    private final JBLabel branchHintLabel;

    UsageStatisticsPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(12));

        // --- NORTH: toolbar with Period + Group-by selectors + legend ---
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
        rangeCombo.addActionListener(e -> reload());
        selectorPanel.add(rangeCombo);

        selectorPanel.add(Box.createHorizontalStrut(JBUI.scale(16)));
        selectorPanel.add(new JBLabel("Group by:"));
        selectorPanel.add(Box.createHorizontalStrut(JBUI.scale(6)));
        groupByCombo = new ComboBox<>(UsageStatisticsData.GroupBy.values());
        groupByCombo.setSelectedItem(UsageStatisticsData.GroupBy.AGENT);
        groupByCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof UsageStatisticsData.GroupBy groupBy) {
                    setText(groupBy.label());
                }
                return this;
            }
        });
        groupByCombo.addActionListener(e -> reload());
        selectorPanel.add(groupByCombo);

        toolbar.add(selectorPanel, BorderLayout.WEST);

        legendContainer = new JPanel();
        legendContainer.setLayout(new BoxLayout(legendContainer, BoxLayout.X_AXIS));
        toolbar.add(legendContainer, BorderLayout.EAST);

        add(toolbar, BorderLayout.NORTH);

        // --- CENTER: card layout switching between agent grid and branch grid ---
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);

        // Agent view: existing 2×3 time-series grid
        JPanel agentGrid = new JPanel(new GridLayout(2, 3, JBUI.scale(12), JBUI.scale(12)));
        for (UsageStatisticsData.Metric metric : UsageStatisticsData.Metric.values()) {
            UsageStatisticsChart chart = new UsageStatisticsChart(metric.displayName(), metric);
            agentCharts.put(metric, chart);
            agentGrid.add(chart);
        }
        cardPanel.add(agentGrid, CARD_AGENT);

        // Branch view: 2×3 grid of bar charts + hint label at bottom
        JPanel branchView = new JPanel(new BorderLayout());
        JPanel branchGrid = new JPanel(new GridLayout(2, 3, JBUI.scale(12), JBUI.scale(12)));
        for (UsageStatisticsData.Metric metric : UsageStatisticsData.Metric.values()) {
            BranchComparisonChart chart = new BranchComparisonChart(metric.displayName(), metric);
            branchCharts.put(metric, chart);
            branchGrid.add(chart);
        }
        branchView.add(branchGrid, BorderLayout.CENTER);

        branchHintLabel = new JBLabel(" ");
        branchHintLabel.setBorder(JBUI.Borders.emptyTop(8));
        branchHintLabel.setForeground(JBColor.GRAY);
        branchView.add(branchHintLabel, BorderLayout.SOUTH);

        cardPanel.add(branchView, CARD_BRANCH);

        add(cardPanel, BorderLayout.CENTER);

        // --- Initial load ---
        reload();
    }

    private void reload() {
        UsageStatisticsData.TimeRange range =
            (UsageStatisticsData.TimeRange) rangeCombo.getSelectedItem();
        UsageStatisticsData.GroupBy groupBy =
            (UsageStatisticsData.GroupBy) groupByCombo.getSelectedItem();
        if (range == null || groupBy == null) return;

        if (groupBy == UsageStatisticsData.GroupBy.AGENT) {
            cardLayout.show(cardPanel, CARD_AGENT);
            loadAgentData(range);
        } else {
            cardLayout.show(cardPanel, CARD_BRANCH);
            loadBranchData(range);
        }
    }

    private void loadAgentData(UsageStatisticsData.TimeRange range) {
        ModalityState modality = ModalityState.any();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                UsageStatisticsData.StatisticsSnapshot snapshot =
                    UsageStatisticsLoader.load(project, range);
                LOG.info("Statistics panel: loaded " + snapshot.dailyStats().size()
                    + " daily stats, " + snapshot.agentIds().size() + " agents");
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    updateAgentCharts(snapshot);
                }, modality);
            } catch (Exception e) {
                LOG.error("Statistics panel: failed to load agent data for range " + range, e);
            }
        });
    }

    private void loadBranchData(UsageStatisticsData.TimeRange range) {
        ModalityState modality = ModalityState.any();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                UsageStatisticsData.BranchSnapshot snapshot =
                    UsageStatisticsLoader.loadBranches(project, range);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    updateBranchCharts(snapshot);
                }, modality);
            } catch (Exception e) {
                LOG.error("Statistics panel: failed to load branch data for range " + range, e);
            }
        });
    }

    private void updateAgentCharts(UsageStatisticsData.StatisticsSnapshot snapshot) {
        for (UsageStatisticsChart chart : agentCharts.values()) {
            chart.update(snapshot);
        }
        legendContainer.removeAll();
        legendContainer.add(buildAgentLegend(snapshot));
        legendContainer.revalidate();
        legendContainer.repaint();
    }

    private void updateBranchCharts(UsageStatisticsData.BranchSnapshot snapshot) {
        for (BranchComparisonChart chart : branchCharts.values()) {
            chart.update(snapshot);
        }
        legendContainer.removeAll();
        legendContainer.revalidate();
        legendContainer.repaint();

        if (snapshot.unattributed() > 0) {
            branchHintLabel.setText(snapshot.unattributed()
                + " turn(s) in this period have no branch attribution"
                + " (recorded before per-branch tracking, or git was unavailable).");
        } else if (snapshot.branches().isEmpty()) {
            branchHintLabel.setText("No branch data yet. Submit a prompt while on a feature"
                + " branch to start tracking per-branch usage.");
        } else {
            branchHintLabel.setText(" ");
        }
    }

    private JPanel buildAgentLegend(UsageStatisticsData.StatisticsSnapshot snapshot) {
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
