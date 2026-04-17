package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.function.Function;

/**
 * Shows a banner at the top of any editor whose file has an active agent-edit
 * snapshot. The banner provides two quick actions:
 * <ul>
 *   <li><b>Revert</b> — opens {@link RevertReasonDialog} and restores the before-content</li>
 *   <li><b>Show Diff</b> — opens IntelliJ's diff viewer with the snapshot on the left and
 *       the live document on the right</li>
 * </ul>
 * The banner vanishes automatically when the session ends or the file is reverted —
 * call {@link EditorNotifications#updateNotifications} after any state change to refresh.
 */
public final class AgentEditNotificationProvider implements EditorNotificationProvider {

    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(
        @NotNull Project project, @NotNull VirtualFile file) {

        AgentEditSession session = AgentEditSession.getInstance(project);
        if (!session.isActive()) return null;
        String before = session.getSnapshot(file);
        if (before == null) return null;

        return fileEditor -> buildBanner(project, file, fileEditor, before);
    }

    private @NotNull EditorNotificationPanel buildBanner(@NotNull Project project,
                                                         @NotNull VirtualFile file,
                                                         @NotNull FileEditor fileEditor,
                                                         @NotNull String before) {
        EditorNotificationPanel panel = new EditorNotificationPanel(fileEditor,
            EditorNotificationPanel.Status.Info);
        panel.setText("Agent edited this file during the current review session.");

        panel.createActionLabel("Show diff", () -> showDiff(project, file, before));

        panel.createActionLabel("Previous", () -> navigateFromBanner(project, file, fileEditor, false));
        panel.createActionLabel("Next", () -> navigateFromBanner(project, file, fileEditor, true));

        panel.createActionLabel("Revert…", () -> {
            String relativePath = toRelativePath(project, file);
            RevertReasonDialog dialog = new RevertReasonDialog(project, file, relativePath);
            if (dialog.showAndGet()) {
                AgentEditSession.getInstance(project).revertFile(file, dialog.getReason());
                EditorNotifications.getInstance(project).updateNotifications(file);
            }
        });

        return panel;
    }

    private static void showDiff(@NotNull Project project,
                                 @NotNull VirtualFile file,
                                 @NotNull String before) {
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        String after = doc != null ? doc.getText() : "";
        com.intellij.diff.contents.DocumentContent left =
            DiffContentFactory.getInstance().create(project, before, file.getFileType());
        com.intellij.diff.contents.DocumentContent right =
            DiffContentFactory.getInstance().create(project, after, file.getFileType());

        SimpleDiffRequest request = new SimpleDiffRequest(
            "Agent edits: " + file.getName(),
            left, right,
            "Before agent edits", "Current");
        DiffManager.getInstance().showDiff(project, request);
    }

    private static void navigateFromBanner(@NotNull Project project,
                                           @NotNull VirtualFile file,
                                           @NotNull FileEditor fileEditor,
                                           boolean forward) {
        int caretLine = -1;
        if (fileEditor instanceof com.intellij.openapi.fileEditor.TextEditor textEditor) {
            caretLine = textEditor.getEditor().getCaretModel().getLogicalPosition().line;
        }
        java.util.NavigableMap<String, java.util.List<ChangeRange>> byPath =
            ChangeNavigator.collectOrderedChanges(project);
        java.util.Optional<ChangeNavigator.Location> target = forward
            ? ChangeNavigator.findNext(byPath, file.getPath(), caretLine)
            : ChangeNavigator.findPrevious(byPath, file.getPath(), caretLine);
        target.ifPresent(loc -> NextAgentEditChangeAction.navigate(project, loc));
    }

    private static @NotNull String toRelativePath(@NotNull Project project,
                                                  @NotNull VirtualFile vf) {
        String basePath = project.getBasePath();
        String filePath = vf.getPath();
        if (basePath != null && filePath.startsWith(basePath + "/")) {
            return filePath.substring(basePath.length() + 1);
        }
        return vf.getName();
    }
}
