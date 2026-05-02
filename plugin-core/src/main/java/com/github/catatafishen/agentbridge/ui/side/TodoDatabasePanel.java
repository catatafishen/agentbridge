package com.github.catatafishen.agentbridge.ui.side;

import com.intellij.openapi.Disposable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Renders Copilot's SQLite task database as a structured list panel.
 * Shows status icon, title, and description for each task item.
 * Auto-refreshes when the database file changes.
 */
final class TodoDatabasePanel extends JPanel implements Disposable {

    private static final int POLL_INTERVAL_MS = 2000;

    private static final Color COLOR_IN_PROGRESS = new JBColor(
        new Color(59, 130, 246), new Color(96, 165, 250));
    private static final Color COLOR_PENDING = new JBColor(
        new Color(107, 114, 128), new Color(156, 163, 175));
    private static final Color COLOR_BLOCKED = new JBColor(
        new Color(239, 68, 68), new Color(248, 113, 113));
    private static final Color COLOR_DONE = new JBColor(
        new Color(34, 197, 94), new Color(74, 222, 128));

    private final JPanel listContainer;
    private final JBLabel headerLabel;
    private final JBLabel emptyLabel;
    private @Nullable File currentDbFile;
    private long lastModified;
    private transient List<TodoDatabaseReader.TodoItem> lastItems = Collections.emptyList();
    private Timer pollTimer;

    TodoDatabasePanel() {
        super(new BorderLayout());

        headerLabel = new JBLabel();
        headerLabel.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));

        listContainer = new JPanel();
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));

        JBScrollPane scrollPane = new JBScrollPane(listContainer);
        scrollPane.setBorder(JBUI.Borders.empty());

        emptyLabel = new JBLabel("No todo database found for this session.");
        emptyLabel.setForeground(JBColor.GRAY);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setBorder(JBUI.Borders.empty(20));

        add(headerLabel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    void setDatabaseFile(@Nullable File dbFile) {
        this.currentDbFile = dbFile;
        this.lastModified = 0;
        refresh();
    }

    void refresh() {
        if (currentDbFile == null || !currentDbFile.exists()) {
            showEmpty();
            return;
        }

        long mtime = currentDbFile.lastModified();
        if (mtime == lastModified && !lastItems.isEmpty()) {
            return;
        }
        lastModified = mtime;

        List<TodoDatabaseReader.TodoItem> items = TodoDatabaseReader.readTodos(currentDbFile);
        if (items.equals(lastItems)) return;
        lastItems = items;

        renderItems(items);
    }

    private void showEmpty() {
        listContainer.removeAll();
        headerLabel.setText("");
        listContainer.add(emptyLabel);
        listContainer.revalidate();
        listContainer.repaint();
    }

    private void renderItems(@NotNull List<TodoDatabaseReader.TodoItem> items) {
        listContainer.removeAll();

        if (items.isEmpty()) {
            showEmpty();
            return;
        }

        long done = items.stream().filter(TodoDatabaseReader.TodoItem::isDone).count();
        headerLabel.setText("Database: " + done + " / " + items.size() + " completed");
        if (done == items.size()) {
            headerLabel.setForeground(COLOR_DONE);
        } else {
            headerLabel.setForeground(UIManager.getColor("Label.foreground"));
        }

        for (TodoDatabaseReader.TodoItem item : items) {
            listContainer.add(createItemRow(item));
        }
        listContainer.add(Box.createVerticalGlue());

        listContainer.revalidate();
        listContainer.repaint();
    }

    private static @NotNull JPanel createItemRow(@NotNull TodoDatabaseReader.TodoItem item) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        JBLabel statusLabel = new JBLabel(statusIcon(item.status()));
        statusLabel.setForeground(statusColor(item.status()));
        statusLabel.setFont(statusLabel.getFont().deriveFont(14f));
        row.add(statusLabel, BorderLayout.WEST);

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JBLabel titleLabel = new JBLabel(item.title());
        if (item.isDone()) {
            titleLabel.setForeground(JBColor.GRAY);
        }
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
        textPanel.add(titleLabel);

        if (item.description() != null && !item.description().isBlank()) {
            String desc = item.description().length() > 120
                ? item.description().substring(0, 120) + "…"
                : item.description();
            JBLabel descLabel = new JBLabel(desc);
            descLabel.setForeground(JBColor.GRAY);
            descLabel.setFont(descLabel.getFont().deriveFont(11f));
            textPanel.add(descLabel);
        }

        row.add(textPanel, BorderLayout.CENTER);

        JBLabel idLabel = new JBLabel(item.id());
        idLabel.setForeground(JBColor.GRAY);
        idLabel.setFont(idLabel.getFont().deriveFont(Font.ITALIC, 10f));
        row.add(idLabel, BorderLayout.EAST);

        return row;
    }

    private static @NotNull String statusIcon(@NotNull String status) {
        return switch (status) {
            case "in_progress" -> "▶";
            case "pending" -> "○";
            case "blocked" -> "✖";
            case "done" -> "✓";
            default -> "?";
        };
    }

    private static @NotNull Color statusColor(@NotNull String status) {
        return switch (status) {
            case "in_progress" -> COLOR_IN_PROGRESS;
            case "pending" -> COLOR_PENDING;
            case "blocked" -> COLOR_BLOCKED;
            case "done" -> COLOR_DONE;
            default -> JBColor.GRAY;
        };
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (pollTimer == null) {
            pollTimer = new Timer(POLL_INTERVAL_MS, e -> refresh());
            pollTimer.start();
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        stopTimer();
    }

    @Override
    public void dispose() {
        stopTimer();
    }

    private void stopTimer() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
    }
}
