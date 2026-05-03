package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.LiveToolCallEntry;
import com.github.catatafishen.agentbridge.services.LiveToolCallService;
import com.github.catatafishen.agentbridge.services.hooks.HookRegistry;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Side-panel tab showing a live list of MCP tool calls.
 * Each row shows timestamp (HH:mm), display name (color-coded by kind), and a
 * live elapsed timer that turns green/red on completion.
 * <p>
 * Clicking a row expands it to show tool ID, category, raw input/output, and
 * other metadata.
 * <p>
 * Uses incremental rendering: only new rows are added on service changes,
 * rather than rebuilding the entire list.
 * <p>
 * Subscribes to {@link LiveToolCallService} for real-time updates.
 */
final class ToolCallListPanel extends JPanel implements Disposable {

    private static final DateTimeFormatter SHORT_TIME =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL_TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int ROW_HEIGHT = 28;

    private static final Color SUCCESS_COLOR = new JBColor(
        new Color(0x2E7D32), new Color(0x81C784));
    private static final Color ERROR_COLOR = new JBColor(
        new Color(0xC62828), new Color(0xEF5350));
    private static final Color RUNNING_COLOR = UIUtil.getLabelDisabledForeground();

    private final transient Project project;
    private final JPanel listPanel;
    private final JBLabel emptyLabel;
    private final JBScrollPane scrollPane;
    private final transient ChangeListener serviceListener;
    private final transient Timer runningTimer;
    private long expandedCallId = -1;
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

        emptyLabel = new JBLabel("No tool calls yet");
        emptyLabel.setForeground(UIUtil.getLabelDisabledForeground());
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.ITALIC));
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(emptyLabel);

        add(scrollPane, BorderLayout.CENTER);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);

        ProjectFilesPanel hookFilesPanel = new ProjectFilesPanel(
            project, HookRegistry.getInstance(project).getHooksDirectory());
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(hookFilesPanel, BorderLayout.CENTER);
        bottomPanel.add(SidePanelFooter.createToolbarFooter(toolbar), BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);

        serviceListener = e -> ApplicationManager.getApplication().invokeLater(this::onServiceChanged);
        LiveToolCallService.getInstance(project).addChangeListener(serviceListener);

        // Tick every second to update running timers
        runningTimer = new Timer(1000, e -> updateRunningTimers());
        runningTimer.setRepeats(true);
        runningTimer.start();
    }

    private void onServiceChanged() {
        List<LiveToolCallEntry> entries = LiveToolCallService.getInstance(project).getEntries();

        if (entries.isEmpty()) {
            rebuild();
            return;
        }

        if (entries.size() == renderedCount) {
            rebuild();
            return;
        }

        if (entries.size() > renderedCount && expandedCallId < 0) {
            if (renderedCount == 0) {
                listPanel.remove(emptyLabel);
            }
            int newCount = entries.size() - renderedCount;
            for (int k = 0; k < newCount; k++) {
                int entryIndex = entries.size() - 1 - k;
                LiveToolCallEntry entry = entries.get(entryIndex);
                JPanel row = createRow(entry);
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
                listPanel.add(createRow(entries.get(i)));
            }
            renderedCount = entries.size();
        }

        listPanel.revalidate();
        listPanel.repaint();
        SwingUtilities.invokeLater(() ->
            scrollPane.getVerticalScrollBar().setValue(0));
    }

    /**
     * Refreshes duration labels on running entries without a full rebuild.
     */
    private void updateRunningTimers() {
        List<LiveToolCallEntry> entries = LiveToolCallService.getInstance(project).getEntries();
        boolean hasRunning = false;
        for (LiveToolCallEntry entry : entries) {
            if (entry.isRunning()) {
                hasRunning = true;
                break;
            }
        }
        if (hasRunning) {
            rebuild();
        }
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
                expandedCallId = -1;
                rebuild();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!LiveToolCallService.getInstance(project).getEntries().isEmpty());
            }
        });
        return ActionManager.getInstance().createActionToolbar("ToolCallsToolbar", group, true);
    }

    private JPanel createRow(LiveToolCallEntry entry) {
        boolean expanded = entry.callId() == expandedCallId;
        JPanel row = new JPanel(new BorderLayout());
        row.setBorder(JBUI.Borders.empty(0, 8));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension fixedSize = new Dimension(Integer.MAX_VALUE, ROW_HEIGHT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, expanded ? Integer.MAX_VALUE : ROW_HEIGHT));

        JPanel summary = new JPanel(new BorderLayout());
        summary.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        summary.setPreferredSize(new Dimension(0, ROW_HEIGHT));
        summary.setMinimumSize(new Dimension(0, ROW_HEIGHT));
        summary.setMaximumSize(fixedSize);

        // Timestamp: show HH:mm, full HH:mm:ss on hover
        String shortTime = SHORT_TIME.format(entry.timestamp());
        String fullTime = FULL_TIME.format(entry.timestamp());
        JBLabel timeLabel = new JBLabel(shortTime);
        timeLabel.setToolTipText(fullTime);
        timeLabel.setForeground(UIUtil.getLabelDisabledForeground());
        timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
        timeLabel.setBorder(JBUI.Borders.emptyRight(6));

        // Display name (humanized) by default
        String shownName = entry.displayName();
        Color nameColor = colorForKind(entry.category());
        JBLabel nameLabel = new JBLabel(shownName);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(nameColor);

        // Duration/status: gray timer while running, green/red when done
        String durationStr;
        Color durationColor;
        if (entry.isRunning()) {
            long elapsed = Duration.between(entry.timestamp(), Instant.now()).toMillis();
            durationStr = formatDuration(elapsed);
            durationColor = RUNNING_COLOR;
        } else {
            durationStr = formatDuration(entry.durationMs());
            durationColor = Boolean.TRUE.equals(entry.success()) ? SUCCESS_COLOR : ERROR_COLOR;
        }
        JBLabel durationLabel = new JBLabel(durationStr);
        durationLabel.setForeground(durationColor);
        durationLabel.setFont(durationLabel.getFont().deriveFont(11f));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftPanel.setOpaque(false);
        leftPanel.add(timeLabel);
        leftPanel.add(nameLabel);
        if (entry.hasHooks()) {
            JBLabel hookLabel = new JBLabel(" 🪝");
            hookLabel.setToolTipText("Hook config active for this tool");
            hookLabel.setFont(hookLabel.getFont().deriveFont(10f));
            leftPanel.add(hookLabel);
        }
        summary.add(leftPanel, BorderLayout.WEST);
        summary.add(durationLabel, BorderLayout.EAST);

        long callId = entry.callId();
        summary.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                expandedCallId = (expandedCallId == callId) ? -1 : callId;
                rebuild();
            }
        });

        row.add(summary, BorderLayout.NORTH);

        if (expanded) {
            JPanel detail = createDetailPanel(entry);
            row.add(detail, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        }

        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        row.add(sep, BorderLayout.SOUTH);
        return row;
    }

    private static JPanel createDetailPanel(LiveToolCallEntry entry) {
        JPanel detail = new JPanel();
        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setBorder(JBUI.Borders.empty(4, 12));

        // Metadata section: tool ID, category, hooks
        addMetadataRow(detail, "Tool ID", entry.toolName());
        if (entry.category() != null) {
            addMetadataRow(detail, "Category", entry.category());
        }
        if (entry.hasHooks()) {
            addMetadataRow(detail, "Hooks", "Active 🪝");
        }
        detail.add(Box.createVerticalStrut(6));

        // Input section
        JBLabel inputLabel = new JBLabel("Input:");
        inputLabel.setFont(inputLabel.getFont().deriveFont(Font.BOLD, 11f));
        inputLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.add(inputLabel);

        JTextArea inputArea = createReadOnlyTextArea(entry.input());
        JBScrollPane inputScroll = wrapInScrollPane(inputArea);
        detail.add(inputScroll);

        detail.add(Box.createVerticalStrut(6));

        // Output section
        JBLabel outputLabel = new JBLabel("Output:");
        outputLabel.setFont(outputLabel.getFont().deriveFont(Font.BOLD, 11f));
        outputLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.add(outputLabel);

        String output = entry.isRunning() ? "(still running…)" : entry.output();
        JTextArea outputArea = createReadOnlyTextArea(output);
        JBScrollPane outputScroll = wrapInScrollPane(outputArea);
        detail.add(outputScroll);

        return detail;
    }

    private static void addMetadataRow(JPanel parent, String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JBLabel keyLabel = new JBLabel(label + ":");
        keyLabel.setForeground(UIUtil.getLabelDisabledForeground());
        keyLabel.setFont(keyLabel.getFont().deriveFont(11f));
        row.add(keyLabel);

        JBLabel valLabel = new JBLabel(value);
        valLabel.setFont(valLabel.getFont().deriveFont(Font.PLAIN, 11f));
        row.add(valLabel);

        parent.add(row);
    }

    private static JBScrollPane wrapInScrollPane(JTextArea area) {
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        return scroll;
    }

    private static JTextArea createReadOnlyTextArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setRows(Math.min(text.split("\n").length, 6));
        area.setBackground(UIUtil.getPanelBackground());
        area.setBorder(JBUI.Borders.empty(4, 6));
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
        runningTimer.stop();
        LiveToolCallService.getInstance(project).removeChangeListener(serviceListener);
    }
}
