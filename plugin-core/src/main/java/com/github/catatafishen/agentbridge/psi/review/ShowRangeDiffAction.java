package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * Phase 4 — shows an inline diff popup for the change range enclosing the caret.
 * <p>
 * Unlike the banner's "Show Diff" (which opens a full-file diff in a new tab), this
 * action scopes the diff to just the affected lines and anchors the popup near the
 * caret, so the user can inspect one change without losing their place in the editor.
 */
public final class ShowRangeDiffAction extends AnAction {

    /**
     * Number of context lines to include above/below the range in the popup.
     */
    private static final int CONTEXT_LINES = 2;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean enabled = project != null
            && vf != null
            && editor != null
            && AgentEditSession.getInstance(project).isActive()
            && AgentEditSession.getInstance(project).getSnapshot(vf) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || vf == null || editor == null) return;

        AgentEditSession session = AgentEditSession.getInstance(project);
        String before = session.getSnapshot(vf);
        if (before == null) return;

        NavigableMap<String, List<ChangeRange>> byPath =
            ChangeNavigator.collectOrderedChanges(project);
        int caretLine = editor.getCaretModel().getLogicalPosition().line;
        Optional<ChangeRange> enclosing =
            ChangeNavigator.findEnclosing(byPath, vf.getPath(), caretLine);
        ChangeRange range = enclosing.orElseGet(() -> firstRangeOf(byPath, vf.getPath()));
        if (range == null) return;

        show(project, vf, editor, before, range);
    }

    private static ChangeRange firstRangeOf(
        @NotNull NavigableMap<String, List<ChangeRange>> byPath, @NotNull String path) {
        List<ChangeRange> list = byPath.get(path);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static void show(@NotNull Project project,
                             @NotNull VirtualFile file,
                             @NotNull Editor editor,
                             @NotNull String before,
                             @NotNull ChangeRange range) {
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        String after = doc != null ? doc.getText() : "";

        String beforeSlice = extractSlice(before,
            range.deletedFromLine(), range.deletedFromLine() + range.deletedCount());
        String afterSlice = extractSlice(after, range.startLine(), range.endLine());

        Disposable disposable = Disposer.newDisposable("ShowRangeDiffAction");
        DiffRequestPanel panel = DiffManager.getInstance().createRequestPanel(project, disposable, null);
        SimpleDiffRequest request = new SimpleDiffRequest(
            "Agent edit — " + file.getName() + " (lines " + (range.startLine() + 1) + "…)",
            DiffContentFactory.getInstance().create(project, beforeSlice, file.getFileType()),
            DiffContentFactory.getInstance().create(project, afterSlice, file.getFileType()),
            "Before", "Current");
        panel.setRequest(request);

        JComponent component = panel.getComponent();
        component.setPreferredSize(new Dimension(800, 260));

        JBPopup popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(component, component)
            .setTitle("Before agent edit")
            .setMovable(true)
            .setResizable(true)
            .setFocusable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup();
        Disposer.register(popup, disposable);
        popup.showInBestPositionFor(editor);
    }

    /**
     * Extracts {@code [startLine, endLineExclusive)} from a document string, padded
     * by {@link #CONTEXT_LINES} on each side where possible. Line indices are 0-based.
     */
    static @NotNull String extractSlice(@NotNull String text, int startLine, int endLineExclusive) {
        String[] lines = text.isEmpty() ? new String[0] : text.split("\n", -1);
        if (lines.length == 0) return "";
        int from = Math.max(0, startLine - CONTEXT_LINES);
        int to = Math.min(lines.length, endLineExclusive + CONTEXT_LINES);
        if (to <= from) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines[i]);
            if (i < to - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
