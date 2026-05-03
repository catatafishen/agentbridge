package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.LiveToolCallEntry;
import com.github.catatafishen.agentbridge.services.LiveToolCallService;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.ui.ChatTheme;
import com.github.catatafishen.agentbridge.ui.ToolKindColors;
import com.github.catatafishen.agentbridge.ui.util.SidePanelFooter;
import com.github.catatafishen.agentbridge.ui.util.VerticalScrollablePanel;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Side-panel tab showing a live list of MCP tool calls with timestamps.
 * Each row shows timestamp, tool name (color-coded by tool kind), duration,
 * and success/failure status. Clicking a row expands it to show raw input
 * and output with explicit labels.
 * <p>
 * Uses incremental rendering: only new rows are added on service changes,
 * rather than rebuilding the entire list.
 * <p>
 * Subscribes to {@link LiveToolCallService} for real-time updates.
 */
final class ToolCallListPanel extends JPanel implements Disposable {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int ROW_HEIGHT = 28;

    private static final Color SUCCESS_COLOR = new JBColor(
        new Color(0x2E7D32), new Color(0x81C784));
    private static final Color ERROR_COLOR = new JBColor(
        new Color(0xC62828), new Color(0xEF5350));
    private static final Color RUNNING_COLOR = new JBColor(
        new Color(0xF57F17), new Color(0xFFD54F));

    private final transient Project project;
    private final JPanel listPanel;
    private final JBLabel emptyLabel;
    private final JBScrollPane scrollPane;
    private final transient ChangeListener serviceListener;
    private int expandedIndex = -1;
    private int renderedCount;

    ToolCallListPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        listPanel = new VerticalScrollablePanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        scrollPane = new JBScrollPane(listPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Empty state
        emptyLabel = new JBLabel("No tool calls yet");
        emptyLabel.setForeground(UIUtil.getLabelDisabledForeground());
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.ITALIC));
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(emptyLabel);

        add(scrollPane, BorderLayout.CENTER);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);
        add(SidePanelFooter.createToolbarFooter(toolbar), BorderLayout.SOUTH);

        // Subscribe to service updates
        serviceListener = e -> ApplicationManager.getApplication().invokeLater(this::onServiceChanged);
        LiveToolCallService.getInstance(project).addChangeListener(serviceListener);
    }

    private void onServiceChanged() {
        List<LiveToolCallEntry> entries = LiveToolCallService.getInstance(project).getEntries();

        if (entries.isEmpty()) {
            rebuild();
            return;
        }

        // If an existing entry was updated (completed), rebuild to update status
        if (entries.size() == renderedCount) {
            rebuild();
            return;
        }

        // Incremental: add only new entries at the top (rendered newest-first)
        if (entries.size() > renderedCount && expandedIndex < 0) {
            if (renderedCount == 0) {
                listPanel.remove(emptyLabel);
            }
            // Insert new rows at the top
            int newCount = entries.size() - renderedCount;
            for (int k = 0; k < newCount; k++) {
                int entryIndex = entries.size() - 1 - k;
                LiveToolCallEntry entry = entries.get(entryIndex);
                JPanel row = createRow(entry, entryIndex);
                listPanel.add(row, k);
            }
            renderedCount = entries.size();
            listPanel.revalidate();
            listPanel.repaint();
            SwingUtilities.invokeLater(() ->
                scrollPane.getVerticalScrollBar().setValue(0));
            return;
        }

        rebuild();
    }

    private void rebuild() {
        listPanel.removeAll();
        List<LiveToolCallEntry> entries = LiveToolCallService.getInstance(project).getEntries();

        if (entries.isEmpty()) {
            listPanel.add(emptyLabel);
            renderedCount = 0;
        } else {
            for (int i = entries.size() - 1; i >= 0; i--) {
                LiveToolCallEntry entry = entries.get(i);
                JPanel row = createRow(entry, i);
                listPanel.add(row);
            }
            renderedCount = entries.size();
        }

        listPanel.revalidate();
        listPanel.repaint();
        SwingUtilities.invokeLater(() ->
            scrollPane.getVerticalScrollBar().setValue(0));
    }

    private @NotNull ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new DumbAwareAction("Clear", "Clear tool call history", AllIcons.Actions.GC) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                LiveToolCallService.getInstance(project).clear();
                expandedIndex = -1;
                rebuild();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!LiveToolCallService.getInstance(project).getEntries().isEmpty());
            }
        });
        return ActionManager.getInstance().createActionToolbar("ToolCallsToolbar", group, true);
    }

    private JPanel createRow(LiveToolCallEntry entry, int index) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBorder(JBUI.Borders.empty(0, 8));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Fixed height for summary, unbounded for expansion
        Dimension fixedSize = new Dimension(Integer.MAX_VALUE, ROW_HEIGHT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, expandedIndex == index ? Integer.MAX_VALUE : ROW_HEIGHT));

        // Summary line
        JPanel summary = new JPanel(new BorderLayout());
        summary.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        summary.setPreferredSize(new Dimension(0, ROW_HEIGHT));
        summary.setMinimumSize(new Dimension(0, ROW_HEIGHT));
        summary.setMaximumSize(fixedSize);

        String time = TIME_FMT.format(entry.timestamp());
        String name = entry.toolName();
        Color nameColor = colorForKind(entry.category());

        String statusIcon;
        Color statusColor;
        String durationStr;
        if (entry.isRunning()) {
            statusIcon = "⏳";
            statusColor = RUNNING_COLOR;
            durationStr = "";
        } else if (Boolean.TRUE.equals(entry.success())) {
            statusIcon = "✓";
            statusColor = SUCCESS_COLOR;
            durationStr = formatDuration(entry.durationMs());
        } else {
            statusIcon = "✗";
            statusColor = ERROR_COLOR;
            durationStr = formatDuration(entry.durationMs());
        }

        JBLabel timeLabel = new JBLabel(time);
        timeLabel.setForeground(UIUtil.getLabelDisabledForeground());
        timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
        timeLabel.setBorder(JBUI.Borders.emptyRight(6));

        JBLabel nameLabel = new JBLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(nameColor);

        JBLabel statusLabel = new JBLabel(statusIcon + " " + durationStr);
        statusLabel.setForeground(statusColor);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftPanel.setOpaque(false);
        leftPanel.add(timeLabel);
        leftPanel.add(nameLabel);
        summary.add(leftPanel, BorderLayout.WEST);
        summary.add(statusLabel, BorderLayout.EAST);

        summary.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                expandedIndex = (expandedIndex == index) ? -1 : index;
                rebuild();
            }
        });

        row.add(summary, BorderLayout.NORTH);

        // Expanded detail: labeled input & output sections
        if (expandedIndex == index) {
            JPanel detail = createDetailPanel(entry);
            row.add(detail, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        }

        // Separator at bottom
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        row.add(sep, BorderLayout.SOUTH);
        return row;
    }

    private static JPanel createDetailPanel(LiveToolCallEntry entry) {
        JPanel detail = new JPanel();
        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setBorder(JBUI.Borders.empty(4, 12));

        // Input section
        JBLabel inputLabel = new JBLabel("Input:");
        inputLabel.setFont(inputLabel.getFont().deriveFont(Font.BOLD, 11f));
        inputLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.add(inputLabel);

        JTextArea inputArea = createReadOnlyTextArea(entry.input());
        JBScrollPane inputScroll = new JBScrollPane(inputArea);
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        detail.add(inputScroll);

        detail.add(Box.createVerticalStrut(6));

        // Output section
        JBLabel outputLabel = new JBLabel("Output:");
        outputLabel.setFont(outputLabel.getFont().deriveFont(Font.BOLD, 11f));
        outputLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.add(outputLabel);

        String output = entry.isRunning() ? "(still running…)" : entry.output();
        JTextArea outputArea = createReadOnlyTextArea(output);
        JBScrollPane outputScroll = new JBScrollPane(outputArea);
        outputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        outputScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        outputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        detail.add(outputScroll);

        return detail;
    }

    private static JTextArea createReadOnlyTextArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setRows(Math.min(text.split("\n").length, 6));
        area.setBackground(UIUtil.getPanelBackground());
        return area;
    }

    private Color colorForKind(String kind) {
        if (kind == null) return ChatTheme.INSTANCE.getKIND_OTHER_COLOR();
        McpServerSettings settings = McpServerSettings.getInstance(project);
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "read", "file", "git_read" -> ToolKindColors.readColor(settings);
            case "search" -> ToolKindColors.searchColor(settings);
            case "edit", "delete", "move", "write", "git_write" -> ToolKindColors.editColor(settings);
            case "execute", "run", "terminal", "shell" -> ToolKindColors.executeColor(settings);
            default -> ChatTheme.INSTANCE.getKIND_OTHER_COLOR();
        };
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm %ds", ms / 60_000, (ms % 60_000) / 1000);
    }

    @Override
    public void dispose() {
        LiveToolCallService.getInstance(project).removeChangeListener(serviceListener);
    }
}
