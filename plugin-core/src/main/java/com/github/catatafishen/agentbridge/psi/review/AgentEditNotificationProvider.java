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
import java.util.List;
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
        panel.setText(buildStatusText(project, file));

        panel.createActionLabel("Show diff", () -> showDiff(project, file, before));

        panel.createActionLabel("Accept", () -> {
            AgentEditSession.getInstance(project).acceptFile(file.getPath());
            EditorNotifications.getInstance(project).updateNotifications(file);
        });

        panel.createActionLabel("Previous", () -> navigateFromBanner(project, file, fileEditor, false));
        panel.createActionLabel("Next", () -> navigateFromBanner(project, file, fileEditor, true));

        panel.createActionLabel("Revert…", () -> {
            String relativePath = toRelativePath(project, file);
            AgentEditSession s = AgentEditSession.getInstance(project);
            RevertReasonDialog dialog = new RevertReasonDialog(project, file, relativePath, s.isGateActive());
            if (!dialog.showAndGet()) return;
            AgentEditSession.RevertGateAction gateAction = switch (dialog.getResult()) {
                case CONTINUE_REVIEWING -> AgentEditSession.RevertGateAction.CONTINUE_REVIEWING;
                case SEND_NOW -> AgentEditSession.RevertGateAction.SEND_NOW;
                default -> AgentEditSession.RevertGateAction.DEFAULT;
            };
            s.revertFile(file.getPath(), dialog.getReason(), gateAction);
            EditorNotifications.getInstance(project).updateNotifications(file);
        });

        return panel;
    }

    /**
     * Builds the banner text with file and change counters.
     * Example: "Edited by agent: File 3/7 · 5 changes"
     */
    private static @NotNull String buildStatusText(@NotNull Project project,
                                                   @NotNull VirtualFile file) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> items = session.getReviewItems();

        // File counter: only count non-DELETED items (deleted files have no editor banner)
        List<String> bannerPaths = items.stream()
            .filter(i -> i.status() != ReviewItem.Status.DELETED)
            .map(ReviewItem::path)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        int fileIndex = bannerPaths.indexOf(file.getPath()) + 1;
        int fileTotal = bannerPaths.size();

        // Change counter: number of diff ranges in this file
        List<ChangeRange> ranges = session.computeRanges(file);
        int changeCount = ranges.size();

        return formatBannerText(fileIndex, fileTotal, changeCount);
    }

    static @NotNull String formatBannerText(int fileIndex, int fileTotal, int changeCount) {
        StringBuilder sb = new StringBuilder("Edited by agent: ");
        if (fileTotal > 0) {
            sb.append("File ").append(Math.max(fileIndex, 1)).append('/').append(fileTotal);
        }
        if (changeCount > 0) {
            if (fileTotal > 0) sb.append(" · ");
            sb.append(changeCount).append(changeCount == 1 ? " change" : " changes");
        }
        if (fileTotal == 0 && changeCount == 0) {
            sb.append("No outstanding changes");
        }
        return sb.toString();
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
