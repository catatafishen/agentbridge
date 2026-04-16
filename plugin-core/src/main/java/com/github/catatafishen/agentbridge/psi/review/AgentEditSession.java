package com.github.catatafishen.agentbridge.psi.review;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks file state before agent edits during a review session.
 * <p>
 * <b>Lifecycle:</b> auto-starts on first agent file edit (when enabled) and continues
 * until the user explicitly ends the session. Captures a "before" snapshot of each file
 * on first modification; supports per-file revert with optional reason nudge.
 * <p>
 * <b>Snapshot strategy:</b> lazy — only files that are actually modified get captured.
 * The before-content is stored with {@code putIfAbsent} so only the very first capture
 * (the pre-session state) is kept, regardless of how many subsequent edits occur.
 */
public final class AgentEditSession implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentEditSession.class);

    /** Skip snapshotting files larger than 5 MB to avoid memory bloat. */
    private static final long MAX_SNAPSHOT_BYTES = 5L * 1024 * 1024;

    /** UserData key for tracking old path during rename/move events. */
    private static final Key<String> OLD_PATH_KEY = Key.create("AgentEditSession.oldPath");

    private final Project project;
    private volatile boolean active;

    /** Before-content snapshots keyed by canonical VFS path. */
    private final Map<String, String> snapshots = new ConcurrentHashMap<>();

    /** Content of files deleted during the session, keyed by path. */
    private final Map<String, String> deletedFiles = new ConcurrentHashMap<>();

    /** Paths of files created during the session. */
    private final Set<String> newFiles = ConcurrentHashMap.newKeySet();

    /** Disposable for session-scoped listeners; null when inactive. */
    private Disposable sessionDisposable;

    public AgentEditSession(@NotNull Project project) {
        this.project = project;
    }

    public static AgentEditSession getInstance(@NotNull Project project) {
        return project.getService(AgentEditSession.class);
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Starts the review session if not already active.
     * Registers document and VFS listeners to capture external changes as a safety net
     * (tool hooks are the primary capture path).
     */
    public synchronized void ensureStarted() {
        if (active) return;
        active = true;

        sessionDisposable = Disposer.newDisposable("AgentEditSession");
        Disposer.register(this, sessionDisposable);

        EditorFactory.getInstance()
            .getEventMulticaster()
            .addDocumentListener(new SessionDocumentListener(), sessionDisposable);

        project.getMessageBus().connect(sessionDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, new SessionVfsListener());

        LOG.info("Agent edit review session started");
    }

    /**
     * Captures the before-content of a file. Only the first capture per file is retained.
     * Called from tool hooks before writes and from the {@link DocumentListener} safety net.
     *
     * @param vf      the virtual file about to be modified
     * @param content the current content (before the pending modification)
     */
    public void captureBeforeContent(@NotNull VirtualFile vf, @NotNull String content) {
        if (!active) return;
        if (content.length() > MAX_SNAPSHOT_BYTES) return;
        if (!isProjectFile(vf)) return;
        snapshots.putIfAbsent(vf.getPath(), content);
    }

    /**
     * Captures the before-content of a file using just its path and content.
     * Used when a VirtualFile is not available (e.g., files that don't exist yet on VFS).
     */
    public void captureBeforeContent(@NotNull String path, @NotNull String content) {
        if (!active) return;
        if (content.length() > MAX_SNAPSHOT_BYTES) return;
        snapshots.putIfAbsent(path, content);
    }

    /**
     * Registers a file as newly created during this session.
     */
    public void registerNewFile(@NotNull String path) {
        if (!active) return;
        newFiles.add(path);
    }

    /**
     * Registers a file as deleted during this session, capturing its content for restore.
     */
    public void registerDeletedFile(@NotNull String path, @NotNull String content) {
        if (!active) return;
        if (content.length() > MAX_SNAPSHOT_BYTES) return;
        deletedFiles.put(path, content);
    }

    /**
     * Computes change ranges between the before-snapshot and the current document content.
     *
     * @return list of change ranges, or empty if no snapshot exists or content is unchanged
     */
    public @NotNull List<ChangeRange> computeRanges(@NotNull VirtualFile vf) {
        String before = snapshots.get(vf.getPath());
        if (before == null) return Collections.emptyList();

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return Collections.emptyList();

        String after = ReadAction.compute(() -> doc.getText());
        if (before.equals(after)) return Collections.emptyList();

        return computeRanges(before, after);
    }

    /**
     * Computes line-level change ranges between two strings.
     * Package-visible for testing.
     */
    @SuppressWarnings("RedundantThrows") // Diff.buildChanges throws checked exception in some SDK versions but not others
    static @NotNull List<ChangeRange> computeRanges(@NotNull String before, @NotNull String after) {
        String[] beforeLines = Diff.splitLines(before);
        String[] afterLines = Diff.splitLines(after);

        try {
            Diff.Change change = Diff.buildChanges(beforeLines, afterLines);
            List<ChangeRange> ranges = new ArrayList<>();

            while (change != null) {
                ChangeType type;
                if (change.inserted > 0 && change.deleted > 0) {
                    type = ChangeType.MODIFIED;
                } else if (change.inserted > 0) {
                    type = ChangeType.ADDED;
                } else {
                    type = ChangeType.DELETED;
                }

                ranges.add(new ChangeRange(
                    change.line1,
                    change.line1 + change.inserted,
                    type,
                    change.line0,
                    change.deleted
                ));
                change = change.link;
            }

            return ranges;
        } catch (FilesTooBigForDiffException e) {
            LOG.warn("File too large for diff", e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns the before-content snapshot for a file, or null if not captured.
     */
    public @Nullable String getSnapshot(@NotNull VirtualFile vf) {
        return snapshots.get(vf.getPath());
    }

    /**
     * Returns all paths that have been modified during this session (not including new or deleted).
     */
    public @NotNull Set<String> getModifiedFilePaths() {
        return Collections.unmodifiableSet(snapshots.keySet());
    }

    public @NotNull Map<String, String> getDeletedFiles() {
        return Collections.unmodifiableMap(deletedFiles);
    }

    public @NotNull Set<String> getNewFilePaths() {
        return Collections.unmodifiableSet(newFiles);
    }

    /**
     * Checks whether any changes have been captured in this session.
     */
    public boolean hasChanges() {
        return !snapshots.isEmpty() || !deletedFiles.isEmpty() || !newFiles.isEmpty();
    }

    /**
     * Reverts a file to its pre-session state.
     *
     * @param vf     the file to revert
     * @param reason optional reason for the revert (sent as nudge to agent)
     */
    public void revertFile(@NotNull VirtualFile vf, @Nullable String reason) {
        String before = snapshots.remove(vf.getPath());
        if (before == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        WriteCommandAction.runWriteCommandAction(project, "Revert Agent Edit", null,
            () -> doc.setText(before));
        FileDocumentManager.getInstance().saveDocument(doc);

        if (reason != null && !reason.isBlank()) {
            sendRevertNudge(vf, reason);
        }
    }

    /**
     * Ends the review session, cleaning up listeners and clearing all tracked state.
     */
    public synchronized void endSession() {
        if (!active) return;
        active = false;

        if (sessionDisposable != null) {
            Disposer.dispose(sessionDisposable);
            sessionDisposable = null;
        }

        snapshots.clear();
        deletedFiles.clear();
        newFiles.clear();

        LOG.info("Agent edit review session ended");
    }

    @Override
    public void dispose() {
        endSession();
    }

    private boolean isProjectFile(@NotNull VirtualFile vf) {
        if (!vf.isValid() || vf.isDirectory()) return false;
        return ReadAction.compute(() -> ProjectFileIndex.getInstance(project).isInContent(vf));
    }

    private void sendRevertNudge(@NotNull VirtualFile vf, @NotNull String reason) {
        String relativePath = getRelativePath(vf);
        String nudge = "[User reverted " + relativePath + "]: " + reason
            + "\nPlease try a different approach for this file.";
        PsiBridgeService.getInstance(project).setPendingNudge(nudge);
    }

    private @NotNull String getRelativePath(@NotNull VirtualFile vf) {
        String basePath = project.getBasePath();
        String filePath = vf.getPath();
        if (basePath != null && filePath.startsWith(basePath + "/")) {
            return filePath.substring(basePath.length() + 1);
        }
        return vf.getName();
    }

    /**
     * Updates path-based tracking maps when a file is renamed or moved.
     * Transfers snapshot and newFiles entries from the old path to the new path.
     */
    private void transferPathTracking(@NotNull VirtualFile vf) {
        String oldPath = vf.getUserData(OLD_PATH_KEY);
        if (oldPath == null) return;
        vf.putUserData(OLD_PATH_KEY, null);

        String snapshot = snapshots.remove(oldPath);
        if (snapshot != null) {
            snapshots.put(vf.getPath(), snapshot);
        }
        if (newFiles.remove(oldPath)) {
            newFiles.add(vf.getPath());
        }
    }

    /**
     * Safety-net document listener: captures before-content for files modified
     * outside of MCP tool hooks (e.g., IntelliJ refactoring, auto-format).
     */
    private class SessionDocumentListener implements DocumentListener {

        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            if (!active) return;

            Document doc = event.getDocument();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            if (vf == null || !vf.isValid()) return;

            captureBeforeContent(vf, doc.getText());
        }
    }

    /**
     * Safety-net VFS listener: captures file lifecycle events (create, delete, rename, move)
     * that the DocumentListener cannot detect.
     */
    private class SessionVfsListener implements BulkFileListener {

        @Override
        public void before(@NotNull List<? extends VFileEvent> events) {
            if (!active) return;

            for (VFileEvent event : events) {
                if (event instanceof VFileDeleteEvent deleteEvent) {
                    handleBeforeDelete(deleteEvent);
                } else if (event instanceof VFileMoveEvent moveEvent) {
                    tagOldPath(moveEvent.getFile());
                } else if (event instanceof VFilePropertyChangeEvent propEvent
                    && VirtualFile.PROP_NAME.equals(propEvent.getPropertyName())) {
                    tagOldPath(propEvent.getFile());
                }
            }
        }

        @Override
        public void after(@NotNull List<? extends VFileEvent> events) {
            if (!active) return;

            for (VFileEvent event : events) {
                if (event instanceof VFileCreateEvent) {
                    VirtualFile vf = event.getFile();
                    if (vf != null && !vf.isDirectory() && isProjectFile(vf)) {
                        registerNewFile(vf.getPath());
                    }
                } else if (event instanceof VFileMoveEvent moveEvent) {
                    transferPathTracking(moveEvent.getFile());
                } else if (event instanceof VFilePropertyChangeEvent propEvent
                    && VirtualFile.PROP_NAME.equals(propEvent.getPropertyName())) {
                    transferPathTracking(propEvent.getFile());
                }
            }
        }

        private void handleBeforeDelete(@NotNull VFileDeleteEvent event) {
            VirtualFile vf = event.getFile();
            if (vf.isDirectory() || !isProjectFile(vf)) return;

            if (newFiles.remove(vf.getPath())) return;

            try {
                byte[] bytes = vf.contentsToByteArray();
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (content.length() <= MAX_SNAPSHOT_BYTES) {
                    registerDeletedFile(vf.getPath(), content);
                }
            } catch (Exception e) {
                LOG.warn("Failed to capture content of deleted file: " + vf.getPath(), e);
            }
        }

        /**
         * Tags a file with its current path before rename/move so we can update
         * path-based tracking after the event completes.
         */
        private void tagOldPath(@NotNull VirtualFile vf) {
            if (vf.isDirectory()) return;
            String path = vf.getPath();
            if (snapshots.containsKey(path) || newFiles.contains(path)) {
                vf.putUserData(OLD_PATH_KEY, path);
            }
        }
    }
}
