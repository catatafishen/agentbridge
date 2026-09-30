package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.DiffEditorTabFilesManager;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Opens and tracks diff tabs in the editor area for pending agent edit
 * approvals (Current vs Proposed by agent), so the tab can be closed programmatically
 * when the approval resolves (allow/deny/timeout). After a resolve the proposed change is
 * either applied (visible in the real editor) or dropped, so a stale diff tab is noise.
 * <p>
 * State is keyed by the approval-request id the chat panels use. Opening is idempotent
 * per request (a repeated auto-open for the same request reuses the tab), and
 * {@link #close} is safe for unknown ids (e.g. command approvals).
 * <p>
 * UI/Logic separation (AGENTS.md): this class is open/close bookkeeping around the
 * platform diff API; detection of edit approvals and decision routing live in
 * {@code AgentPermissionRequests} and the ACP client respectively.
 */
public final class EditApprovalDiffTabs {

    private static final ConcurrentMap<Project, EditApprovalDiffTabs> INSTANCES = new ConcurrentHashMap<>();

    /** The diff tab opened for one pending approval request. */
    private static final class DiffTabHandle {
        final SimpleDiffVirtualFile file;
        volatile boolean closed;

        DiffTabHandle(@NotNull SimpleDiffVirtualFile file) {
            this.file = file;
        }
    }

    /** Editor tabs opened for pending approvals, keyed by approval request id. */
    private final ConcurrentMap<String, DiffTabHandle> openTabs = new ConcurrentHashMap<>();

    private EditApprovalDiffTabs() {
    }

    public static EditApprovalDiffTabs getInstance(@NotNull Project project) {
        EditApprovalDiffTabs instance = INSTANCES.computeIfAbsent(project, p -> {
            // Remove the per-project instance when the project closes so the static map
            // never retains a disposed Project (computeIfAbsent runs exactly once).
            Disposer.register(p, (Disposable) () -> INSTANCES.remove(p));
            return new EditApprovalDiffTabs();
        });
        return instance;
    }

    /**
     * Opens (or reuses) the diff tab for an edit approval and registers it under
     * {@code requestId} so {@link #close} can close it on resolve. If the tab is still
     * open (repeated click, or the auto-open already showed it), it is activated rather
     * than duplicated; if it was closed manually, a fresh tab is opened. Works after the
     * approval resolved too — such tabs are user-initiated and stay open until closed
     * manually, like any other editor tab.
     * Must run on the EDT; callers on other threads wrap in
     * {@code ApplicationManager.getApplication().invokeLater}.
     *
     * @param requestId approval-request id (the chat-panel prompt id)
     * @param path      display path of the target file (drives the file type and tab title)
     * @param oldText   current content; {@code null} for new files
     * @param newText   proposed content
     */
    public void open(@NotNull Project project, @NotNull String requestId, @Nullable String path,
                     @Nullable String oldText, @NotNull String newText) {
        DiffTabHandle existing = openTabs.get(requestId);
        if (existing != null && FileEditorManager.getInstance(project).isFileOpen(existing.file)) {
            // Still open (auto-open, repeated click, or a re-open after resolve):
            // bring the existing tab to the front instead of stacking a duplicate.
            FileEditorManager.getInstance(project).openFile(existing.file, true);
            return;
        }
        if (path == null) path = "";
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        VirtualFile vf = normalized.isEmpty()
            ? null : LocalFileSystem.getInstance().findFileByPath(normalized);
        FileType fileType = vf != null
            ? vf.getFileType()
            : FileTypeManager.getInstance().getFileTypeByFileName(name.isEmpty() ? "file.txt" : name);
        DiffContent left = DiffContentFactory.getInstance()
            .create(project, oldText == null ? "" : oldText, fileType);
        DiffContent right = DiffContentFactory.getInstance().create(project, newText, fileType);
        SimpleDiffRequest request = new SimpleDiffRequest(
            "Agent edit approval" + (name.isEmpty() ? "" : ": " + name),
            left, right, "Current", "Proposed by agent");
        SimpleDiffVirtualFile diffFile = new SimpleDiffVirtualFile(request);
        openTabs.put(requestId, new DiffTabHandle(diffFile));
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true);
    }

    /**
     * Closes the diff tab opened for the given approval request, if any (called when the
     * approval resolves: allow/deny/timeout). Safe for unknown ids. Any thread; hops to
     * the EDT for the actual close.
     */
    public void close(@NotNull Project project, @NotNull String requestId) {
        DiffTabHandle handle = openTabs.remove(requestId);
        if (handle == null || handle.closed) return;
        handle.closed = true;
        ApplicationManager.getApplication().invokeLater(() ->
            FileEditorManager.getInstance(project).closeFile(handle.file), ModalityState.any());
    }
}
