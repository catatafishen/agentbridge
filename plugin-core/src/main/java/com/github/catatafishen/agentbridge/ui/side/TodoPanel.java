package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.ui.MarkdownRenderer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Side-panel tab that renders the active agent session's plan/todo file.
 * <p>
 * Different agents store their task-list in different places and formats, but the plugin
 * treats {@code plan.md} inside the active agent's session directory as the canonical
 * todo file (see {@code SessionSwitchService#PLAN_FILE_NAME}). This panel shows that
 * file as rendered Markdown and exposes a {@code (done/total)} progress summary that
 * the parent tab container uses to badge the tab title.
 * <p>
 * The panel polls the file's modification time every {@link #POLL_INTERVAL_MS} ms while
 * showing, so edits made by the agent (or the user) appear without requiring a manual
 * refresh. Polling stops when the panel is removed from the component hierarchy.
 */
final class TodoPanel extends JPanel implements Disposable {

    private static final Pattern CHECKBOX_LINE = Pattern.compile("^\\s*[-*]\\s+\\[([ xX])]\\s+.+$");
    private static final int POLL_INTERVAL_MS = 1500;

    private final transient Project project;
    private final JEditorPane markdownPane;
    private final JBLabel emptyLabel;
    private final JBLabel headerLabel;
    private final JPanel contentPanel;
    private final transient Timer pollTimer;
    private @Nullable Runnable onProgressChanged;

    /**
     * Last observed (path, mtime, done, total) so the poll timer can skip work when nothing changed.
     */
    private @Nullable Path lastPath;
    private long lastMtime = -1L;
    private int lastDone = -1;
    private int lastTotal = -1;

    TodoPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        headerLabel = new JBLabel();
        headerLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
        headerLabel.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        headerLabel.setVisible(false);

        markdownPane = createMarkdownPane();

        emptyLabel = new JBLabel(
            "<html><div style='text-align:center; color:#888;'>"
                + "No todos for the current agent session."
                + "<br><br><small>Ask the agent to create a plan, or start a new task.</small>"
                + "</div></html>",
            SwingConstants.CENTER
        );
        emptyLabel.setVerticalAlignment(SwingConstants.CENTER);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);

        contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(emptyLabel, BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout());
        body.add(headerLabel, BorderLayout.NORTH);
        body.add(contentPanel, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        pollTimer = new Timer(POLL_INTERVAL_MS, e -> pollIfVisible());
        pollTimer.setRepeats(true);
    }

    /**
     * Registers a callback fired whenever the todo count changes. Called on the EDT.
     * The consumer receives {@code (done, total)} — both zero when no checkbox-style
     * items exist (in which case the parent should drop the badge).
     */
    void setOnProgressChanged(@NotNull Runnable callback) {
        this.onProgressChanged = callback;
    }

    /**
     * Zero when no checkbox-style todos were found.
     */
    int getTotal() {
        return Math.max(0, lastTotal);
    }

    /**
     * Zero when no checkbox-style todos were found.
     */
    int getDone() {
        return Math.max(0, lastDone);
    }

    private JEditorPane createMarkdownPane() {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.setOpaque(true);
        pane.setBackground(UIUtil.getPanelBackground());
        pane.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openPlanInEditor();
            }
        });
        pane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openPlanInEditor();
            }
        });
        return pane;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refresh();
        pollTimer.start();
    }

    @Override
    public void removeNotify() {
        pollTimer.stop();
        super.removeNotify();
    }

    @Override
    public void dispose() {
        pollTimer.stop();
    }

    private void pollIfVisible() {
        refresh();
    }

    /**
     * Re-reads the plan file and repaints if anything changed. Safe to call from the EDT.
     */
    void refresh() {
        Path path = resolvePlanPath();
        if (path == null || !Files.isRegularFile(path)) {
            renderEmpty();
            return;
        }

        long mtime;
        String content;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
            if (path.equals(lastPath) && mtime == lastMtime) return;
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            renderEmpty();
            return;
        }

        lastPath = path;
        lastMtime = mtime;
        renderMarkdown(content);
    }

    private void renderEmpty() {
        boolean changed = lastTotal != 0 || lastDone != 0 || lastPath != null;
        lastPath = null;
        lastMtime = -1L;
        lastDone = 0;
        lastTotal = 0;

        headerLabel.setVisible(false);
        contentPanel.removeAll();
        contentPanel.add(emptyLabel, BorderLayout.CENTER);
        contentPanel.revalidate();
        contentPanel.repaint();
        if (changed && onProgressChanged != null) onProgressChanged.run();
    }

    private void renderMarkdown(@NotNull String markdown) {
        int[] progress = countCheckboxes(markdown);
        int done = progress[0];
        int total = progress[1];

        boolean progressChanged = done != lastDone || total != lastTotal;
        lastDone = done;
        lastTotal = total;

        if (total > 0) {
            headerLabel.setText(done + " / " + total + " completed"
                + (done == total ? "  ✓" : ""));
            headerLabel.setForeground(done == total
                ? new JBColor(new Color(0x2F9E44), new Color(0x6BD968))
                : UIUtil.getLabelForeground());
            headerLabel.setVisible(true);
        } else {
            headerLabel.setVisible(false);
        }

        String html = renderHtml(markdown);
        markdownPane.setText(html);
        markdownPane.setCaretPosition(0);

        contentPanel.removeAll();
        JBScrollPane scroll = new JBScrollPane(markdownPane);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        contentPanel.add(scroll, BorderLayout.CENTER);
        contentPanel.revalidate();
        contentPanel.repaint();

        if (progressChanged && onProgressChanged != null) onProgressChanged.run();
    }

    private static @NotNull String renderHtml(@NotNull String markdown) {
        String body = MarkdownRenderer.INSTANCE.markdownToHtml(markdown);
        Color fg = UIUtil.getLabelForeground();
        String fgHex = String.format("#%02x%02x%02x", fg.getRed(), fg.getGreen(), fg.getBlue());
        return "<html><body style='font-family: sans-serif; font-size: 12px; color: "
            + fgHex + "; margin: 0; padding: 0;'>"
            + body
            + "</body></html>";
    }

    /**
     * Counts markdown checkbox lines. Returns {@code [done, total]}. Both zero if none found.
     */
    private static int[] countCheckboxes(@NotNull String markdown) {
        int done = 0;
        int total = 0;
        for (String line : markdown.split("\\R", -1)) {
            Matcher m = CHECKBOX_LINE.matcher(line);
            if (m.matches()) {
                total++;
                if (!" ".equals(m.group(1))) done++;
            }
        }
        return new int[]{done, total};
    }

    /**
     * Resolves the plan file for the currently-active agent session.
     * Returns {@code null} if no agent is running or the session dir is unknown.
     */
    private @Nullable Path resolvePlanPath() {
        try {
            ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
            Path sessionDir = manager.getClient().getSessionDirectory();
            if (sessionDir == null) return null;
            return sessionDir.resolve("plan.md");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void openPlanInEditor() {
        Path path = resolvePlanPath();
        if (path == null || !Files.isRegularFile(path)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true);
            }
        });
    }
}
