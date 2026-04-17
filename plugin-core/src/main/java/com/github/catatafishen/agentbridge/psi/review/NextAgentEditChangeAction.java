package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * Phase 5 — moves the caret to the next agent-edit change across the entire
 * review session, wrapping from the last file back to the first.
 */
public class NextAgentEditChangeAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = project != null
            && AgentEditSession.getInstance(project).isActive()
            && AgentEditSession.getInstance(project).hasChanges();
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        NavigableMap<String, List<ChangeRange>> byPath =
            ChangeNavigator.collectOrderedChanges(project);
        if (byPath.isEmpty()) return;

        String currentPath = null;
        int currentLine = -1;
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (vf != null) currentPath = vf.getPath();
        if (editor != null) currentLine = editor.getCaretModel().getLogicalPosition().line;

        Optional<ChangeNavigator.Location> target = pick(byPath, currentPath, currentLine);
        target.ifPresent(location -> navigate(project, location));
    }

    /**
     * Extracted for unit-test parity with {@link ChangeNavigator#findNext}.
     */
    protected Optional<ChangeNavigator.Location> pick(
        @NotNull NavigableMap<String, List<ChangeRange>> byPath,
        String currentPath, int currentLine) {
        return ChangeNavigator.findNext(byPath, currentPath, currentLine);
    }

    static void navigate(@NotNull Project project, @NotNull ChangeNavigator.Location target) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(target.path());
        if (vf == null || !vf.isValid()) return;

        int line = target.range().startLine();
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, vf, line, 0);
        descriptor.setUseCurrentWindow(true);
        Editor editor = FileEditorManager.getInstance(project)
            .openTextEditor(descriptor, true);
        if (editor != null) {
            editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, 0));
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        }
    }
}
