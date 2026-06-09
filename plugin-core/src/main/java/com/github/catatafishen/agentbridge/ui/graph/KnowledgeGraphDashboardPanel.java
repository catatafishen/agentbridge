package com.github.catatafishen.agentbridge.ui.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dashboard tab for the Knowledge Graph tool window.
 * Shows stat cards, hotspots ranking, and status info.
 * Settings, rebuild, and export are in the tool window title bar.
 */
public final class KnowledgeGraphDashboardPanel implements Disposable {

    private static final String TOOL_ID = "query_knowledge_graph";

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());

    // Stat card labels
    private final JBLabel nodesLabel = new JBLabel("0");
    private final JBLabel edgesLabel = new JBLabel("0");
    private final JBLabel filesLabel = new JBLabel("0");
    private final JBLabel commitsLabel = new JBLabel("0");

    // Status
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JBLabel toolStatusLabel = new JBLabel(" ");

    // Hotspots
    private final JPanel hotspotsPanel = new JPanel(new VerticalLayout(JBUI.scale(2)));

    // Recent Activity
    private final JPanel activityPanel = new JPanel(new VerticalLayout(JBUI.scale(2)));

    private Runnable toolChangeDisconnect;

    public KnowledgeGraphDashboardPanel(@NotNull Project project) {
        this.project = project;
        build();
        refreshAll();

        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                && root.isShowing()) {
                refreshAll();
            }
        });

        ApplicationManager.getApplication().invokeLater(this::refreshAll, ModalityState.nonModal());

        toolChangeDisconnect = com.github.catatafishen.agentbridge.psi.PlatformApiCompat
            .subscribeToolsChanged(project, () ->
                ApplicationManager.getApplication().invokeLater(this::refreshAll, ModalityState.nonModal()));
    }

    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public void dispose() {
        if (toolChangeDisconnect != null) {
            toolChangeDisconnect.run();
            toolChangeDisconnect = null;
        }
    }

    /**
     * Called by {@link KnowledgeGraphRebuildAction} when a rebuild finishes.
     */
    void onRebuildFinished() {
        refreshAll();
        setStatus("Build finished — query_knowledge_graph ready.");
    }

    private void build() {
        JPanel content = new JPanel(new VerticalLayout(JBUI.scale(12)));
        content.setBorder(JBUI.Borders.empty(12));

        // Description
        JBLabel description = new JBLabel(
            "<html><body style='width:280px'>"
                + "Persistent index of code structure, git history, and agent activity. "
                + "Powers the <code>query_knowledge_graph</code> MCP tool."
                + "</body></html>");
        content.add(description);

        // Stat cards
        content.add(buildStatCardsPanel());

        // Tool status
        toolStatusLabel.setForeground(JBColor.GRAY);
        content.add(toolStatusLabel);

        // Hotspots section
        JBLabel hotspotsTitle = new JBLabel("Top Hotspots (most depended-upon files)");
        hotspotsTitle.setFont(hotspotsTitle.getFont().deriveFont(Font.BOLD));
        content.add(hotspotsTitle);
        hotspotsPanel.setBorder(JBUI.Borders.empty(4, 0));
        content.add(hotspotsPanel);

        // Recent Activity section
        JBLabel activityTitle = new JBLabel("Recent Activity");
        activityTitle.setFont(activityTitle.getFont().deriveFont(Font.BOLD));
        content.add(activityTitle);
        activityPanel.setBorder(JBUI.Borders.empty(4, 0));
        content.add(activityPanel);

        // Status
        statusLabel.setForeground(JBColor.GRAY);
        content.add(statusLabel);

        root.add(new JBScrollPane(content), BorderLayout.CENTER);
    }

    private @NotNull JPanel buildStatCardsPanel() {
        JPanel cards = new JPanel(new GridLayout(1, 4, JBUI.scale(8), 0));
        cards.add(buildStatCard(nodesLabel, "Nodes"));
        cards.add(buildStatCard(edgesLabel, "Edges"));
        cards.add(buildStatCard(filesLabel, "Files"));
        cards.add(buildStatCard(commitsLabel, "Commits"));
        return cards;
    }

    private static @NotNull JPanel buildStatCard(@NotNull JBLabel valueLabel, @NotNull String title) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(8)
        ));

        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(18f)));
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setForeground(JBColor.GRAY);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);

        card.add(valueLabel, BorderLayout.CENTER);
        card.add(titleLabel, BorderLayout.SOUTH);
        return card;
    }

    private void refreshAll() {
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        CodeGraphStore store = CodeGraphStore.getInstance(project);
        CodeGraphStore.GraphStats stats = store.getStats();

        nodesLabel.setText(formatCount(stats.nodeCount()));
        edgesLabel.setText(formatCount(stats.edgeCount()));
        filesLabel.setText(formatCount(stats.fileCount()));
        commitsLabel.setText(formatCount(stats.commitCount()));

        // Tool status
        boolean toolInRegistry = ToolRegistry.getInstance(project).findById(TOOL_ID) != null;
        boolean advertised = toolInRegistry && settings.isEnabled();
        String lastIndexed = formatTime(stats.lastIndexedAt());
        if (!toolInRegistry) {
            toolStatusLabel.setText("Tool: pending registration… | Last indexed: " + lastIndexed);
        } else if (advertised) {
            toolStatusLabel.setText("● Active — last indexed " + lastIndexed);
            toolStatusLabel.setForeground(new JBColor(new Color(0x2E7D32), new Color(0x81C784)));
        } else {
            toolStatusLabel.setText("○ Disabled — tool hidden from agents");
            toolStatusLabel.setForeground(JBColor.GRAY);
        }

        refreshHotspots(store);
        refreshActivity(store);
    }

    private void refreshHotspots(@NotNull CodeGraphStore store) {
        List<CodeGraphStore.HotspotEntry> hotspots = store.getHotspots(7);
        hotspotsPanel.removeAll();

        if (hotspots.isEmpty()) {
            hotspotsPanel.add(new JBLabel("No data — rebuild to populate."));
        } else {
            int maxCount = hotspots.get(0).dependentCount();
            for (CodeGraphStore.HotspotEntry entry : hotspots) {
                hotspotsPanel.add(buildHotspotRow(entry, maxCount));
            }
        }
        hotspotsPanel.revalidate();
        hotspotsPanel.repaint();
    }

    private void refreshActivity(@NotNull CodeGraphStore store) {
        List<CodeGraphStore.ActivityEntry> activity = store.getRecentActivity(8);
        activityPanel.removeAll();

        if (activity.isEmpty()) {
            activityPanel.add(new JBLabel("No activity yet — rebuild to index git history."));
        } else {
            for (CodeGraphStore.ActivityEntry entry : activity) {
                activityPanel.add(buildActivityRow(entry));
            }
        }
        activityPanel.revalidate();
        activityPanel.repaint();
    }

    private static @NotNull JPanel buildActivityRow(@NotNull CodeGraphStore.ActivityEntry entry) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));

        String icon = switch (entry.type()) {
            case "commit" -> "●";
            case "agent_edit" -> "✎";
            default -> "○";
        };

        JBLabel iconLabel = new JBLabel(icon);
        iconLabel.setForeground(switch (entry.type()) {
            case "commit" -> new JBColor(new Color(0x1565C0), new Color(0x42A5F5));
            case "agent_edit" -> new JBColor(new Color(0x2E7D32), new Color(0x81C784));
            default -> JBColor.GRAY;
        });

        JBLabel summaryLabel = new JBLabel(entry.summary());
        summaryLabel.setToolTipText(entry.summary());

        JBLabel timeLabel = new JBLabel(formatRelativeTime(entry.timestamp()));
        timeLabel.setForeground(JBColor.GRAY);

        row.add(iconLabel, BorderLayout.WEST);
        row.add(summaryLabel, BorderLayout.CENTER);
        row.add(timeLabel, BorderLayout.EAST);
        return row;
    }

    private static @NotNull String formatRelativeTime(@NotNull String timestamp) {
        try {
            java.time.Instant instant = java.time.OffsetDateTime.parse(timestamp).toInstant();
            long seconds = java.time.Duration.between(instant, java.time.Instant.now()).getSeconds();
            if (seconds < 60) return seconds + "s ago";
            if (seconds < 3600) return (seconds / 60) + "m ago";
            if (seconds < 86400) return (seconds / 3600) + "h ago";
            return (seconds / 86400) + "d ago";
        } catch (Exception e) {
            return timestamp.length() > 10 ? timestamp.substring(0, 10) : timestamp;
        }
    }

    private static @NotNull JPanel buildHotspotRow(@NotNull CodeGraphStore.HotspotEntry entry, int maxCount) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));

        String shortName = entry.path().contains("/")
            ? entry.path().substring(entry.path().lastIndexOf('/') + 1)
            : entry.path();
        JBLabel nameLabel = new JBLabel(shortName);
        nameLabel.setToolTipText(entry.path());

        JPanel bar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                double ratio = maxCount > 0 ? (double) entry.dependentCount() / maxCount : 0;
                int barWidth = (int) (getWidth() * ratio);
                g.setColor(new JBColor(new Color(0x42A5F5), new Color(0x1565C0)));
                g.fillRect(0, 0, barWidth, getHeight());
            }
        };
        bar.setPreferredSize(new Dimension(0, JBUI.scale(14)));
        bar.setOpaque(false);

        JBLabel countLabel = new JBLabel(String.valueOf(entry.dependentCount()));
        countLabel.setForeground(JBColor.GRAY);

        row.add(nameLabel, BorderLayout.WEST);
        row.add(bar, BorderLayout.CENTER);
        row.add(countLabel, BorderLayout.EAST);
        return row;
    }

    private void setStatus(@NotNull String text) {
        ApplicationManager.getApplication().invokeLater(() -> statusLabel.setText(text));
    }

    private static @NotNull String formatTime(long epochMs) {
        if (epochMs <= 0) return "never";
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMs));
        } catch (Exception e) {
            return String.valueOf(epochMs);
        }
    }

    private static @NotNull String formatCount(long count) {
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000) return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }
}
