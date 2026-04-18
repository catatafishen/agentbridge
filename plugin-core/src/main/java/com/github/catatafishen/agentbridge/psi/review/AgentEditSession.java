package com.github.catatafishen.agentbridge.psi.review;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.services.ChatWebServer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.AppIcon;
import com.intellij.ui.SystemNotifications;
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

    /**
     * Skip snapshotting files larger than 5 MB to avoid memory bloat.
     */
    private static final long MAX_SNAPSHOT_BYTES = 5L * 1024 * 1024;

    /**
     * UserData key for tracking old path during rename/move events.
     */
    private static final Key<String> OLD_PATH_KEY = Key.create("AgentEditSession.oldPath");

    private final Project project;
    private volatile boolean active;

    /**
     * Thread-local marker set during agent-originated tool edits.
     * The {@link SessionDocumentListener} uses this to distinguish agent edits
     * from unrelated document changes (branch switches, IDE reformats, user typing).
     */
    private static final ThreadLocal<Boolean> agentEditActive = ThreadLocal.withInitial(() -> false);

    /**
     * Marks the current thread as executing an agent-originated edit.
     * Must be paired with {@link #markAgentEditEnd()} in a finally block.
     */
    public static void markAgentEditStart() {
        agentEditActive.set(true);
    }

    /**
     * Clears the agent-edit marker for the current thread.
     */
    public static void markAgentEditEnd() {
        agentEditActive.remove();
    }

    static boolean isAgentEditActive() {
        return agentEditActive.get();
    }

    /**
     * Before-content snapshots keyed by canonical VFS path.
     */
    private final Map<String, String> snapshots = new ConcurrentHashMap<>();

    /**
     * Content of files deleted during the session, keyed by path.
     */
    private final Map<String, String> deletedFiles = new ConcurrentHashMap<>();

    /**
     * Paths of files created during the session.
     */
    private final Set<String> newFiles = ConcurrentHashMap.newKeySet();

    /**
     * Disposable for session-scoped listeners; null when inactive.
     */
    private Disposable sessionDisposable;

    /**
     * Tracks whether the user has been notified about the currently-pending review state.
     * Reset when review items are resolved. Prevents notification spam on agent retries
     * of gated git operations.
     */
    private volatile boolean reviewNotificationFired;

    /**
     * Completes when the current review batch has been fully resolved (accept or reject)
     * or the session has ended. Lazily (re-)created by {@link #awaitReviewCompletion}
     * when a blocking gate starts; completed by {@link #completeReviewIfEmpty} /
     * {@link #endSession}. Null when no gate is currently in flight.
     */
    private java.util.concurrent.CompletableFuture<Void> reviewCompletionFuture;

    public AgentEditSession(@NotNull Project project) {
        this.project = project;
    }

    public static AgentEditSession getInstance(@NotNull Project project) {
        return project.getService(AgentEditSession.class);
    }

    public boolean isActive() {
        return active;
    }

    public synchronized void ensureStarted() {
        if (active) return;
        if (!com.github.catatafishen.agentbridge.settings.McpServerSettings.getInstance(project).isReviewAgentEdits()) {
            return;
        }
        active = true;

        sessionDisposable = Disposer.newDisposable("AgentEditSession");
        Disposer.register(this, sessionDisposable);

        EditorFactory.getInstance()
            .getEventMulticaster()
            .addDocumentListener(new SessionDocumentListener(), sessionDisposable);

        project.getMessageBus().connect(sessionDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, new SessionVfsListener());

        LOG.info("Agent edit review session started");
        fireReviewStateChanged();
    }

    /**
     * Ends the session if active. Called from git tools that change the working tree
     * (branch switch, reset --hard, rebase, stash pop, merge, pull, cherry-pick, revert).
     * After a worktree change, existing snapshots are invalid since files may have
     * reverted to different content.
     */
    public void invalidateOnWorktreeChange(@NotNull String operation) {
        if (!active) return;
        LOG.info("Invalidating review session due to: " + operation);
        endSession();
    }

    public void captureBeforeContent(@NotNull VirtualFile vf, @NotNull String content) {
        if (!active) return;
        if (content.length() > MAX_SNAPSHOT_BYTES) return;
        if (!isProjectFile(vf)) return;
        String prev = snapshots.putIfAbsent(vf.getPath(), content);
        if (prev == null) {
            LOG.info("Captured before-snapshot for: " + getRelativePath(vf));
            com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
            fireReviewStateChanged();
        }
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

        String after = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) doc::getText);
        if (before.equals(after)) return Collections.emptyList();

        return computeRanges(before, after);
    }

    /**
     * Computes line-level change ranges between two strings.
     * Package-visible for testing.
     */
    @SuppressWarnings("RedundantThrows")
    // Diff.buildChanges throws checked exception in some SDK versions but not others
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
     * Builds a unified list of review items from the session's tracked state.
     * Each file appears exactly once: as ADDED, MODIFIED, or DELETED.
     */
    public @NotNull List<ReviewItem> getReviewItems() {
        if (!active) return Collections.emptyList();
        String basePath = project.getBasePath();
        List<ReviewItem> items = new ArrayList<>();

        for (Map.Entry<String, String> entry : snapshots.entrySet()) {
            String path = entry.getKey();
            // Skip files that were subsequently deleted — they'll appear as DELETED
            if (deletedFiles.containsKey(path)) continue;
            items.add(new ReviewItem(path, relativize(path, basePath),
                ReviewItem.Status.MODIFIED, entry.getValue()));
        }
        for (String path : newFiles) {
            items.add(new ReviewItem(path, relativize(path, basePath),
                ReviewItem.Status.ADDED, null));
        }
        for (Map.Entry<String, String> entry : deletedFiles.entrySet()) {
            String path = entry.getKey();
            // For deleted files, beforeContent is the original snapshot if available,
            // otherwise the content captured at deletion time
            String beforeContent = snapshots.getOrDefault(path, entry.getValue());
            items.add(new ReviewItem(path, relativize(path, basePath),
                ReviewItem.Status.DELETED, beforeContent));
        }

        items.sort((a, b) -> a.relativePath().compareToIgnoreCase(b.relativePath()));
        return items;
    }

    /**
     * Accepts a file's changes — removes it from review tracking.
     * For MODIFIED: clears the snapshot (keeps current content).
     * For ADDED: removes from newFiles set (keeps the file).
     * For DELETED: removes from deletedFiles map (keeps it deleted).
     */
    public void acceptFile(@NotNull String path) {
        if (!active) return;
        snapshots.remove(path);
        newFiles.remove(path);
        deletedFiles.remove(path);

        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf != null) {
            AgentEditHighlighter.getInstance(project).clearForFile(vf);
            com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
        }
        fireReviewStateChanged();
        completeReviewIfEmpty();
    }

    /**
     * Accepts all files — clears all review tracking.
     */
    public void acceptAll() {
        if (!active) return;
        // Collect paths that need highlight clearing before we wipe the maps
        Set<String> allPaths = new java.util.HashSet<>(snapshots.keySet());
        allPaths.addAll(newFiles);
        // deletedFiles have no open editors to clear

        snapshots.clear();
        newFiles.clear();
        deletedFiles.clear();

        AgentEditHighlighter highlighter = AgentEditHighlighter.getInstance(project);
        for (String path : allPaths) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
            if (vf != null) {
                highlighter.clearForFile(vf);
            }
        }
        com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
        fireReviewStateChanged();
        completeReviewIfEmpty();
    }

    /**
     * Rejects a single file — restores it to pre-session state.
     * For MODIFIED: restores snapshot content.
     * For ADDED: deletes the file.
     * For DELETED: recreates the file with its original content.
     *
     * @param path   file path
     * @param reason optional reason (sent as nudge to agent)
     */
    public void rejectFile(@NotNull String path, @Nullable String reason) {
        if (!active) return;

        String basePath = project.getBasePath();
        if (snapshots.containsKey(path) && !deletedFiles.containsKey(path)) {
            rejectModifiedFile(new ReviewItem(path, relativize(path, basePath), ReviewItem.Status.MODIFIED, null));
        } else if (newFiles.contains(path)) {
            rejectAddedFile(new ReviewItem(path, relativize(path, basePath), ReviewItem.Status.ADDED, null));
        } else if (deletedFiles.containsKey(path)) {
            rejectDeletedFile(new ReviewItem(path, relativize(path, basePath), ReviewItem.Status.DELETED, null));
        }

        if (reason != null && !reason.isBlank()) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
            if (vf != null) {
                sendRevertNudge(vf, reason);
            }
        }
        fireReviewStateChanged();
        completeReviewIfEmpty();
    }

    /**
     * Rejects all files — restores all to pre-session state.
     *
     * @param reason optional reason (sent as nudge to agent)
     */
    public void rejectAll(@Nullable String reason) {
        if (!active) return;
        List<ReviewItem> items = getReviewItems();
        for (ReviewItem item : items) {
            // Call individual reject but suppress intermediate topic events
            rejectFileSilent(item);
        }
        if (reason != null && !reason.isBlank()) {
            String nudge = "[User rejected all agent edits]: " + reason
                + "\nPlease try a different approach.";
            PsiBridgeService.getInstance(project).setPendingNudge(nudge);
        }
        fireReviewStateChanged();
        completeReviewIfEmpty();
    }

    /**
     * Blocks until all pending review items have been accepted/rejected, the session ends,
     * or the timeout expires. Called by git tools before destructive operations.
     *
     * <p>On the first call, fires a balloon notification + system notification
     * and expands the Review panel. Subsequent calls only expand the panel.</p>
     *
     * <p><b>Lock yield:</b> this method is called from inside a tool's {@code execute()},
     * which holds two locks: the global write-tool semaphore and the per-tool sync lock
     * (both managed by {@link PsiBridgeService#callTool}). Without yielding both, a second
     * call to the same tool from another mcp-http thread deadlocks: the second thread acquires
     * the semaphore (released here) but then waits for the syncLock, while this thread still
     * holds the syncLock and waits to re-acquire the semaphore. We release both before blocking
     * and re-acquire both afterward (semaphore first, then syncLock — matching callTool's
     * acquisition order) to break the circular dependency.</p>
     *
     * @param operation description of the git operation (for the notification + error)
     * @return null if review completed in time; otherwise a timeout error message
     */
    // S2222: syncLock.lock() in this finally block intentionally re-acquires a lock that was
    // originally acquired by callTool(). Sonar cannot trace cross-method lock ownership.
    // The lock is released by callTool's own finally block, maintaining correct lock ordering.
    @SuppressWarnings("java:S2222")
    public @Nullable String awaitReviewCompletion(@NotNull String operation) {
        if (!active || !hasChanges()) {
            reviewNotificationFired = false;
            return null;
        }

        int fileCount = getReviewItems().size();

        if (!reviewNotificationFired) {
            reviewNotificationFired = true;
            notifyReviewRequired(operation);
        }
        // Always expand the review panel so the user sees what's blocked.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            com.github.catatafishen.agentbridge.ui.review.ReviewPanelController
                .getInstance(project).expandReviewPanel();
        });

        PsiBridgeService psi = PsiBridgeService.getInstance(project);
        java.util.concurrent.Semaphore writeSemaphore = psi.getWriteToolSemaphore();
        // Read the syncLock BEFORE releasing anything — it is a ThreadLocal on this thread.
        java.util.concurrent.locks.ReentrantLock syncLock = psi.getCurrentSyncLock();

        // Release the syncLock first (if any), then the semaphore.
        // Reversed acquisition order breaks the circular-wait condition.
        if (syncLock != null) syncLock.unlock();
        writeSemaphore.release();
        try {
            // Re-check after releasing locks: the EDT can call completeReviewIfEmpty() at
            // any time, including in the window between the checks above and getOrCreate().
            // If it ran with a null future (no changes left), we must not hang on get().
            while (active && hasChanges()) {
                java.util.concurrent.CompletableFuture<Void> future = getOrCreateReviewCompletionFuture();
                // Guard: if the EDT cleared all changes between creating the future and now
                // (and therefore completed or never completed it), avoid waiting.
                if (!active || !hasChanges()) break;
                future.get(REVIEW_WAIT_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
            }
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            return formatReviewTimeoutError(operation, fileCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted while waiting for agent-edit review.";
        } catch (java.util.concurrent.ExecutionException e) {
            return "Error: Review wait failed: " + e.getCause();
        } finally {
            // Re-acquire in the same order as callTool: semaphore first, then syncLock.
            // acquireUninterruptibly: we must re-acquire even if the thread was interrupted,
            // otherwise callTool's finally block would over-release the semaphore.
            writeSemaphore.acquireUninterruptibly();
            if (syncLock != null) syncLock.lock();
        }
    }

    private synchronized java.util.concurrent.CompletableFuture<Void> getOrCreateReviewCompletionFuture() {
        java.util.concurrent.CompletableFuture<Void> f = reviewCompletionFuture;
        if (f == null || f.isDone()) {
            f = new java.util.concurrent.CompletableFuture<>();
            reviewCompletionFuture = f;
        }
        return f;
    }

    /**
     * Package-private pure formatter — extracted so unit tests can verify the wording
     * without needing a {@link Project} or the message bus.
     */
    static @NotNull String formatReviewTimeoutError(@NotNull String operation, int fileCount) {
        return "Error: Timed out after " + REVIEW_WAIT_TIMEOUT_MINUTES
            + " minutes waiting for the user to review " + fileCount
            + (fileCount == 1 ? " agent-edited file" : " agent-edited files")
            + " before '" + operation
            + "' could run. Ask the user to accept/reject the pending edits in the"
            + " Review panel (left of chat), or end the review session, then retry.";
    }

    private static final long REVIEW_WAIT_TIMEOUT_MINUTES = 10;

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

        // Mirror acceptFile()/rejectFile(): clear highlights, refresh banner, and update review state
        // so the file no longer shows up in the Review panel and any blocked git gate can proceed.
        AgentEditHighlighter.getInstance(project).clearForFile(vf);
        com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);

        if (reason != null && !reason.isBlank()) {
            sendRevertNudge(vf, reason);
        }
        fireReviewStateChanged();
        completeReviewIfEmpty();
    }

    public synchronized void endSession() {
        if (!active) return;
        active = false;

        AgentEditHighlighter.getInstance(project).clearAll();

        if (sessionDisposable != null) {
            Disposer.dispose(sessionDisposable);
            sessionDisposable = null;
        }

        snapshots.clear();
        deletedFiles.clear();
        newFiles.clear();
        reviewNotificationFired = false;

        java.util.concurrent.CompletableFuture<Void> f = reviewCompletionFuture;
        if (f != null && !f.isDone()) {
            f.complete(null);
        }
        reviewCompletionFuture = null;

        com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
        fireReviewStateChanged();

        LOG.info("Agent edit review session ended");
    }

    @Override
    public void dispose() {
        endSession();
    }

    private boolean isProjectFile(@NotNull VirtualFile vf) {
        if (!vf.isValid() || vf.isDirectory()) return false;
        return ApplicationManager.getApplication().runReadAction(
            (Computable<Boolean>) () -> ProjectFileIndex.getInstance(project).isInContent(vf));
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

    private static @NotNull String relativize(@NotNull String path, @Nullable String basePath) {
        if (basePath != null && path.startsWith(basePath + "/")) {
            return path.substring(basePath.length() + 1);
        }
        return new java.io.File(path).getName();
    }

    /**
     * Rejects a single review item without firing topic events (used in bulk reject).
     */
    private void rejectFileSilent(@NotNull ReviewItem item) {
        switch (item.status()) {
            case MODIFIED -> rejectModifiedFile(item);
            case ADDED -> rejectAddedFile(item);
            case DELETED -> rejectDeletedFile(item);
        }
    }

    /**
     * Restores a MODIFIED file to its snapshot content and clears editor decorations.
     */
    private void rejectModifiedFile(@NotNull ReviewItem item) {
        String path = item.path();
        String snapshot = snapshots.remove(path);
        if (snapshot == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            WriteCommandAction.runWriteCommandAction(project, "Reject Agent Edit", null,
                () -> doc.setText(snapshot));
            FileDocumentManager.getInstance().saveDocument(doc);
        }
        AgentEditHighlighter.getInstance(project).clearForFile(vf);
        com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
    }

    /**
     * Deletes an ADDED (agent-created) file and clears editor decorations.
     */
    private void rejectAddedFile(@NotNull ReviewItem item) {
        String path = item.path();
        newFiles.remove(path);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf == null) return;
        AgentEditHighlighter.getInstance(project).clearForFile(vf);
        WriteCommandAction.runWriteCommandAction(project, "Delete Agent-Created File", null, () -> {
            try {
                vf.delete(this);
            } catch (Exception e) {
                LOG.warn("Failed to delete agent-created file: " + path, e);
            }
        });
    }

    /**
     * Recreates a DELETED file from its original content (prefers snapshot over deletion-time capture).
     */
    private void rejectDeletedFile(@NotNull ReviewItem item) {
        String path = item.path();
        String content = deletedFiles.remove(path);
        String snapshotContent = snapshots.remove(path);
        String restore = snapshotContent != null ? snapshotContent : content;
        if (restore == null) return;
        WriteCommandAction.runWriteCommandAction(project, "Restore Deleted File", null, () -> {
            try {
                java.io.File ioFile = new java.io.File(path);
                java.io.File parent = ioFile.getParentFile();
                if (parent != null) {
                    VirtualFile parentVf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(parent);
                    if (parentVf != null) {
                        VirtualFile created = parentVf.createChildData(this, ioFile.getName());
                        created.setBinaryContent(restore.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to restore deleted file: " + path, e);
            }
        });
    }

    private void fireReviewStateChanged() {
        project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
    }

    /**
     * Resets the notification flag when the review batch is fully resolved so a future
     * agent edit cycle will re-notify when gating fires again.
     */
    private void completeReviewIfEmpty() {
        if (hasChanges()) return;
        reviewNotificationFired = false;
        java.util.concurrent.CompletableFuture<Void> f = reviewCompletionFuture;
        if (f != null && !f.isDone()) {
            f.complete(null);
        }
    }

    /**
     * Sends notifications (IntelliJ balloon + system + web push) when a git operation
     * is blocked waiting for review completion.
     */
    private void notifyReviewRequired(@NotNull String operation) {
        String title = "Review Required";
        String body = "'" + operation + "' is waiting for you to review agent edits.";

        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindowManager.getInstance(project).notifyByBalloon(
                "AgentBridge", MessageType.WARNING,
                "<b>" + title + "</b><br>" + body
            );
            // Expand the inline Review panel and activate the tool window
            com.github.catatafishen.agentbridge.ui.review.ReviewPanelController
                .getInstance(project).expandReviewPanel();
        });

        SystemNotifications.getInstance().notify("AgentBridge Notifications", title, body);
        AppIcon.getInstance().requestAttention(project, true);

        ChatWebServer webServer = ChatWebServer.getInstance(project);
        if (webServer != null) {
            webServer.pushNotification(title, body);
        }
    }

    private class SessionDocumentListener implements DocumentListener {

        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            if (!active) return;
            // Only capture snapshots during agent-originated tool edits.
            // Non-agent changes (branch switches, IDE reformats, user typing)
            // would pollute the session with incorrect "before" content.
            if (!isAgentEditActive()) return;

            Document doc = event.getDocument();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            if (vf == null || !vf.isValid()) return;

            captureBeforeContent(vf, doc.getText());
        }

        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            if (!active) return;

            Document doc = event.getDocument();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            if (vf == null || !vf.isValid()) return;

            if (snapshots.containsKey(vf.getPath())) {
                AgentEditHighlighter.getInstance(project).refreshHighlights(vf);
            }
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
            // Only capture agent-initiated deletes (same guard as beforeDocumentChange).
            // User-initiated deletes must not be registered as agent edits.
            if (!isAgentEditActive()) return;

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
    }
}
