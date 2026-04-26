package com.github.catatafishen.agentbridge.psi.review;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.services.ChatWebServer;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
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
import com.intellij.openapi.util.io.OSAgnosticPathUtil;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Always-on tracker for agent-originated file edits. The session itself never turns off;
 * it persists across IDE restarts via {@link PersistentStateComponent}. What does change
 * is the per-row {@link ApprovalState}: rows are PENDING by default and APPROVED either
 * automatically (when {@link McpServerSettings#isAutoApproveAgentEdits()} is on) or via
 * explicit user action. Approved rows stay visible until pruned (DEL key, "Clean
 * Approved" toolbar action, post-commit prune, worktree-changing git operation, or
 * optional auto-clean on a new prompt).
 *
 * <p><b>Gating:</b> {@link #awaitReviewCompletion} blocks only on PENDING rows. While a
 * gate is active, {@link #revertFile} can short-circuit it via the
 * {@link RevertGateAction#SEND_NOW} option, returning the merged revert nudges as the
 * gated tool's error response so the agent can re-plan.
 *
 * <p><b>Re-edit of an approved file:</b> when a file with an APPROVED row is edited
 * again by the agent, the snapshot is rebased to the just-approved content (the diff in
 * the panel shows only the <i>new</i> changes, not the cumulative ones since the session
 * began). When auto-approve is OFF, the row also flips back to PENDING; with auto-approve
 * ON it stays APPROVED.
 *
 * <p>Storage is workspace-only (not committed to VCS).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AgentEditReview",
    storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class AgentEditSession implements Disposable, PersistentStateComponent<AgentEditSession.PersistedState> {

    private static final Logger LOG = Logger.getInstance(AgentEditSession.class);

    /**
     * Skip snapshotting files larger than 5 MB to avoid memory bloat.
     */
    private static final long MAX_SNAPSHOT_BYTES = 5L * 1024 * 1024;

    /**
     * Project-wide cap on the total size of all snapshots + deletedFiles content. When
     * exceeded, the oldest APPROVED rows are evicted first; if still over cap nothing
     * else is dropped (the new edit is accepted but the user is not blocked).
     */
    private static final long MAX_TOTAL_SNAPSHOT_BYTES = 50L * 1024 * 1024;

    private static final long REVIEW_WAIT_TIMEOUT_MINUTES = 10;

    /**
     * UserData key for tracking old path during rename/move events.
     */
    private static final Key<String> OLD_PATH_KEY = Key.create("AgentEditSession.oldPath");

    /**
     * Thread-local marker set during agent-originated tool edits.
     * The {@link SessionDocumentListener} uses this to distinguish agent edits
     * from unrelated document changes (branch switches, IDE reformats, user typing).
     */
    private static final ThreadLocal<Boolean> agentEditActive = ThreadLocal.withInitial(() -> false);

    public static void markAgentEditStart() {
        agentEditActive.set(true);
    }

    public static void markAgentEditEnd() {
        agentEditActive.remove();
    }

    static boolean isAgentEditActive() {
        return agentEditActive.get();
    }

    private final Project project;

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
     * User approval state per path.
     */
    private final Map<String, ApprovalState> approvals = new ConcurrentHashMap<>();
    /**
     * Epoch millis of the most recent agent edit per path.
     */
    private final Map<String, Long> lastEditedAt = new ConcurrentHashMap<>();
    /**
     * Inserted line count vs. the snapshot baseline.
     */
    private final Map<String, Integer> linesAdded = new ConcurrentHashMap<>();
    /**
     * Deleted line count vs. the snapshot baseline.
     */
    private final Map<String, Integer> linesRemoved = new ConcurrentHashMap<>();

    private Disposable sessionDisposable;
    private volatile boolean started;

    private volatile boolean reviewNotificationFired;
    private java.util.concurrent.CompletableFuture<Void> reviewCompletionFuture;

    /**
     * Buffer of revert nudges accumulated during the current gate. Flushed on either
     * {@link RevertGateAction#SEND_NOW} (returned as the tool error) or on natural gate
     * resolution (forwarded into {@link PsiBridgeService#setPendingNudge}).
     */
    private final List<String> gateRevertBuffer = Collections.synchronizedList(new ArrayList<>());

    /**
     * Set when the current gate is short-circuited via "Send to agent now". Cleared
     * after the gated tool consumes it and reports the error to the agent.
     */
    private volatile String pendingGateCancelMessage;

    public AgentEditSession(@NotNull Project project) {
        this.project = project;
        ensureStarted();
    }

    public static AgentEditSession getInstance(@NotNull Project project) {
        return project.getService(AgentEditSession.class);
    }

    /**
     * Always-on session bootstrap: installs the document and VFS listeners exactly once
     * per project lifetime. Kept public for back-compat with the older opt-in API — most
     * callers can drop the call entirely.
     */
    public synchronized void ensureStarted() {
        if (started || project.isDisposed()) return;
        started = true;

        sessionDisposable = Disposer.newDisposable("AgentEditSession");
        Disposer.register(this, sessionDisposable);

        EditorFactory.getInstance()
            .getEventMulticaster()
            .addDocumentListener(new SessionDocumentListener(), sessionDisposable);

        project.getMessageBus().connect(sessionDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, new SessionVfsListener());

        LOG.info("Agent edit review session started (always-on)");
    }

    /**
     * Always-on session is always "active". Kept for API back-compat (old call sites
     * that used to short-circuit on inactive sessions).
     */
    public boolean isActive() {
        return started;
    }

    /**
     * Wipes all tracked state and ends any in-flight gate. Called on worktree changes
     * (branch switch, reset --hard, rebase, stash pop, merge, pull, cherry-pick, revert)
     * — existing snapshots would otherwise be stale.
     */
    public void invalidateOnWorktreeChange(@NotNull String operation) {
        if (snapshots.isEmpty() && deletedFiles.isEmpty() && newFiles.isEmpty()) return;
        LOG.info("Invalidating review session due to: " + operation);
        wipeAllTrackedState();
        completeGate();
    }

    // ── Capture / register ──────────────────────────────────────────────────

    public void captureBeforeContent(@NotNull VirtualFile vf, @NotNull String content) {
        if (!isProjectFile(vf)) return;
        captureBeforeContent(vf.getPath(), content);
        com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
    }

    /**
     * Captures the before-content of a file using just its path and content.
     * Used when a VirtualFile is not available (e.g., files that don't exist yet on VFS).
     */
    public void captureBeforeContent(@NotNull String path, @NotNull String content) {
        if (content.length() > MAX_SNAPSHOT_BYTES) return;
        String absPath = ensureAbsolutePath(path);
        if (!isProjectPath(absPath)) return;
        if (newFiles.contains(absPath)) {
            lastEditedAt.put(absPath, System.currentTimeMillis());
            applyDefaultApproval(absPath);
            recomputeLineCounts(absPath);
            fireReviewStateChanged();
            return;
        }
        snapshots.putIfAbsent(absPath, content);
        lastEditedAt.put(absPath, System.currentTimeMillis());
        applyDefaultApproval(absPath);
        recomputeLineCounts(absPath);
        enforceTotalSnapshotCap();
        fireReviewStateChanged();
    }

    public void registerNewFile(@NotNull String path) {
        String absPath = ensureAbsolutePath(path);
        if (!isProjectPath(absPath)) return;
        String deletedContent = deletedFiles.remove(absPath);
        if (deletedContent != null) {
            snapshots.putIfAbsent(absPath, deletedContent);
        } else {
            newFiles.add(absPath);
            snapshots.remove(absPath);
        }
        lastEditedAt.put(absPath, System.currentTimeMillis());
        applyDefaultApproval(absPath);
        recomputeLineCounts(absPath);
        fireReviewStateChanged();
    }

    public void registerDeletedFile(@NotNull String path, @NotNull String content) {
        if (content.length() > MAX_SNAPSHOT_BYTES) return;
        String absPath = ensureAbsolutePath(path);
        if (!isProjectPath(absPath)) return;
        if (newFiles.remove(absPath)) {
            clearTrackedPath(absPath);
            fireReviewStateChanged();
            return;
        }
        deletedFiles.put(absPath, content);
        lastEditedAt.put(absPath, System.currentTimeMillis());
        applyDefaultApproval(absPath);
        recomputeLineCounts(absPath);
        enforceTotalSnapshotCap();
        fireReviewStateChanged();
    }

    private void applyDefaultApproval(@NotNull String path) {
        ApprovalState defaultState = isAutoApproveOn() ? ApprovalState.APPROVED : ApprovalState.PENDING;
        approvals.put(path, defaultState);
    }

    private boolean isAutoApproveOn() {
        return McpServerSettings.getInstance(project).isAutoApproveAgentEdits();
    }

    // ── Range / snapshot accessors (unchanged surface) ──────────────────────

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

    @SuppressWarnings("RedundantThrows")
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

    public @Nullable String getSnapshot(@NotNull VirtualFile vf) {
        return snapshots.get(vf.getPath());
    }

    public @NotNull Set<String> getModifiedFilePaths() {
        return Collections.unmodifiableSet(snapshots.keySet());
    }

    public @NotNull Map<String, String> getDeletedFiles() {
        return Collections.unmodifiableMap(deletedFiles);
    }

    public @NotNull Set<String> getNewFilePaths() {
        return Collections.unmodifiableSet(newFiles);
    }

    // ── Aggregate state ─────────────────────────────────────────────────────

    /**
     * Any tracked items at all (pending or approved). Used by UI emptiness checks.
     */
    public boolean hasChanges() {
        return !snapshots.isEmpty() || !deletedFiles.isEmpty() || !newFiles.isEmpty();
    }

    /**
     * Any PENDING items remaining. Gate-blocking check.
     */
    public boolean hasPendingChanges() {
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            if (e.getValue() == ApprovalState.PENDING && pathIsTracked(e.getKey())) return true;
        }
        return false;
    }

    /**
     * True while {@link #awaitReviewCompletion} is blocking on a future.
     */
    public boolean isGateActive() {
        java.util.concurrent.CompletableFuture<Void> f = reviewCompletionFuture;
        return f != null && !f.isDone();
    }

    private boolean pathIsTracked(@NotNull String path) {
        return snapshots.containsKey(path) || newFiles.contains(path) || deletedFiles.containsKey(path);
    }

    public @NotNull List<ReviewItem> getReviewItems() {
        normalizeTrackedState();
        return buildReviewItems(
            snapshots,
            newFiles,
            deletedFiles,
            approvals,
            lastEditedAt,
            linesAdded,
            linesRemoved,
            project.getBasePath()
        );
    }

    static @NotNull List<ReviewItem> buildReviewItems(
        @NotNull Map<String, String> snapshots,
        @NotNull Set<String> newFiles,
        @NotNull Map<String, String> deletedFiles,
        @NotNull Map<String, ApprovalState> approvals,
        @NotNull Map<String, Long> lastEditedAt,
        @NotNull Map<String, Integer> linesAdded,
        @NotNull Map<String, Integer> linesRemoved,
        @Nullable String basePath
    ) {
        List<ReviewItem> items = new ArrayList<>();

        for (Map.Entry<String, String> entry : snapshots.entrySet()) {
            String path = entry.getKey();
            if (newFiles.contains(path) || deletedFiles.containsKey(path)) continue;
            items.add(buildItem(path, basePath, ReviewItem.Status.MODIFIED, entry.getValue(),
                approvals, lastEditedAt, linesAdded, linesRemoved));
        }
        for (String path : newFiles) {
            if (deletedFiles.containsKey(path)) continue;
            items.add(buildItem(path, basePath, ReviewItem.Status.ADDED, null,
                approvals, lastEditedAt, linesAdded, linesRemoved));
        }
        for (Map.Entry<String, String> entry : deletedFiles.entrySet()) {
            String path = entry.getKey();
            if (newFiles.contains(path)) continue;
            String beforeContent = snapshots.getOrDefault(path, entry.getValue());
            items.add(buildItem(path, basePath, ReviewItem.Status.DELETED, beforeContent,
                approvals, lastEditedAt, linesAdded, linesRemoved));
        }

        items.sort((a, b) -> a.relativePath().compareToIgnoreCase(b.relativePath()));
        return items;
    }

    private static @NotNull ReviewItem buildItem(@NotNull String path, @Nullable String basePath,
                                                 @NotNull ReviewItem.Status status,
                                                 @Nullable String beforeContent,
                                                 @NotNull Map<String, ApprovalState> approvals,
                                                 @NotNull Map<String, Long> lastEditedAt,
                                                 @NotNull Map<String, Integer> linesAdded,
                                                 @NotNull Map<String, Integer> linesRemoved) {
        ApprovalState state = approvals.getOrDefault(path, ApprovalState.PENDING);
        long ts = lastEditedAt.getOrDefault(path, 0L);
        int added = linesAdded.getOrDefault(path, 0);
        int removed = linesRemoved.getOrDefault(path, 0);
        return new ReviewItem(path, relativize(path, basePath), status, beforeContent,
            state, ts, added, removed);
    }

    // ── Approval mutations ──────────────────────────────────────────────────

    /**
     * Flips a single row to APPROVED. Keeps the row visible.
     */
    public void acceptFile(@NotNull String path) {
        if (!pathIsTracked(path)) return;
        approvals.put(path, ApprovalState.APPROVED);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf != null) {
            com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
        }
        fireReviewStateChanged();
        completeGateIfNoPending();
    }

    /**
     * Flips a single row back to PENDING.
     */
    public void unapproveFile(@NotNull String path) {
        if (!pathIsTracked(path)) return;
        if (approvals.get(path) != ApprovalState.APPROVED) return;
        approvals.put(path, ApprovalState.PENDING);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf != null) {
            com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
        }
        fireReviewStateChanged();
    }

    /**
     * Flips every PENDING row to APPROVED. Used by the "Auto-Approve ON" sweep.
     */
    public void acceptAll() {
        boolean changed = false;
        for (String path : collectAllPaths()) {
            if (approvals.put(path, ApprovalState.APPROVED) != ApprovalState.APPROVED) {
                changed = true;
            }
        }
        if (changed) {
            com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
            fireReviewStateChanged();
            completeGateIfNoPending();
        }
    }

    /**
     * Removes a single row from tracking and clears its highlights. Caller is responsible
     * for ensuring the row is APPROVED — pending rows should be reverted, not removed.
     */
    public void removeApproved(@NotNull String path) {
        if (approvals.get(path) != ApprovalState.APPROVED) return;
        clearTrackedPath(path);
        fireReviewStateChanged();
    }

    /**
     * Removes every APPROVED row.
     */
    public void removeAllApproved() {
        List<String> approved = new ArrayList<>();
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            if (e.getValue() == ApprovalState.APPROVED) approved.add(e.getKey());
        }
        if (approved.isEmpty()) return;
        for (String path : approved) clearTrackedPath(path);
        com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
        fireReviewStateChanged();
    }

    /**
     * Post-commit prune: removes APPROVED rows whose paths were committed. Pending rows
     * are intentionally left in place — they didn't make it into the commit anyway.
     * <p>
     * <b>Path normalisation</b>: {@code git show --name-only} returns <em>relative</em> paths
     * (e.g. {@code plugin-core/src/…/Foo.java}), but all internal maps key on
     * <em>absolute</em> paths. Each path is resolved against {@code project.getBasePath()}
     * before the map lookup so the prune actually fires.
     */
    public void removeApprovedForCommit(@NotNull Collection<String> committedPaths) {
        if (committedPaths.isEmpty()) return;
        String basePath = project.getBasePath();
        boolean changed = false;
        for (String path : committedPaths) {
            String absPath = toAbsolutePath(path, basePath);
            if (approvals.get(absPath) == ApprovalState.APPROVED && pathIsTracked(absPath)) {
                clearTrackedPath(absPath);
                changed = true;
            }
        }
        if (changed) {
            com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
            fireReviewStateChanged();
        }
    }

    /**
     * Resolves {@code path} to an absolute path. If {@code path} is already absolute it is
     * returned as-is; otherwise it is joined to {@code basePath}.
     */
    static @NotNull String toAbsolutePath(@NotNull String path, @Nullable String basePath) {
        if (basePath == null || OSAgnosticPathUtil.isAbsolute(path)) return path;
        return basePath + "/" + path;
    }

    /**
     * Instance-method convenience: resolves a possibly-relative path against the project base.
     * All map keys must be absolute; callers at the session boundary use this to normalize
     * incoming paths before storing them.
     */
    private @NotNull String ensureAbsolutePath(@NotNull String path) {
        return toAbsolutePath(path, project.getBasePath());
    }

    private void clearTrackedPath(@NotNull String path) {
        snapshots.remove(path);
        deletedFiles.remove(path);
        newFiles.remove(path);
        approvals.remove(path);
        lastEditedAt.remove(path);
        linesAdded.remove(path);
        linesRemoved.remove(path);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf != null) {
            AgentEditHighlighter.getInstance(project).clearForFile(vf);
            com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(vf);
        }
    }

    /**
     * Called from settings on Auto-Approve toggle ON. Sweeps all current PENDING rows
     * to APPROVED and fires a single state change.
     */
    public void onAutoApproveTurnedOn() {
        acceptAll();
    }

    // ── Revert (always sends a structured nudge) ────────────────────────────

    /**
     * What to do with the in-flight gate when reverting during a blocked git op.
     */
    public enum RevertGateAction {
        /**
         * Queue the nudge into pendingNudge; keep the gate blocking so the user can
         * reject more files. Subsequent reverts during the same gate default here.
         */
        CONTINUE_REVIEWING,
        /**
         * Short-circuit the gate immediately — the gated tool returns the merged
         * revert nudges as its error response so the agent can re-plan.
         */
        SEND_NOW,
        /**
         * Plain revert with no gate special-casing (used when no gate is active).
         */
        DEFAULT
    }

    /**
     * Reverts a tracked file and emits a structured nudge to the agent.
     *
     * <p>The nudge always has the form:
     * <pre>
     * [User reverted &lt;rel-path&gt;:&lt;line-ranges&gt;] Reason: &lt;reason&gt;
     * &lt;unified diff body&gt;
     * Please try a different approach for this file.
     * </pre>
     *
     * @param path       absolute VFS path of the tracked file
     * @param reason     user-supplied reason; may be blank
     * @param gateAction what to do with any in-flight gate; pass {@link
     *                   RevertGateAction#DEFAULT} when called outside a gate
     */
    public void revertFile(@NotNull String path, @Nullable String reason,
                           @NotNull RevertGateAction gateAction) {
        if (!pathIsTracked(path)) return;

        ReviewItem item = findItem(path);
        if (item == null) return;

        String nudge = buildRevertNudge(item, reason);
        applyRevert(item);

        boolean gateActive = isGateActive();
        switch (gateAction) {
            case SEND_NOW -> {
                synchronized (gateRevertBuffer) {
                    gateRevertBuffer.add(nudge);
                    pendingGateCancelMessage = String.join("\n\n", gateRevertBuffer);
                    gateRevertBuffer.clear();
                }
                completeGate();
            }
            case CONTINUE_REVIEWING -> {
                synchronized (gateRevertBuffer) {
                    gateRevertBuffer.add(nudge);
                }
            }
            case DEFAULT -> {
                if (gateActive) {
                    synchronized (gateRevertBuffer) {
                        gateRevertBuffer.add(nudge);
                    }
                } else {
                    PsiBridgeService.getInstance(project).setPendingNudge(nudge);
                }
            }
        }

        clearTrackedPath(path);
        fireReviewStateChanged();
        completeGateIfNoPending();
    }

    /**
     * Convenience overload for callers that have a {@link VirtualFile}.
     */
    public void revertFile(@NotNull VirtualFile vf, @Nullable String reason) {
        revertFile(vf.getPath(), reason, RevertGateAction.DEFAULT);
    }

    private @Nullable ReviewItem findItem(@NotNull String path) {
        for (ReviewItem item : getReviewItems()) {
            if (item.path().equals(path)) return item;
        }
        return null;
    }

    private void applyRevert(@NotNull ReviewItem item) {
        switch (item.status()) {
            case MODIFIED -> restoreModifiedFile(item);
            case ADDED -> deleteAddedFile(item);
            case DELETED -> restoreDeletedFile(item);
        }
    }

    private void restoreModifiedFile(@NotNull ReviewItem item) {
        String snapshot = snapshots.get(item.path());
        if (snapshot == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            WriteCommandAction.runWriteCommandAction(project, "Revert Agent Edit", null,
                () -> doc.setText(snapshot));
            FileDocumentManager.getInstance().saveDocument(doc);
        }
    }

    private void deleteAddedFile(@NotNull ReviewItem item) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf == null) return;
        WriteCommandAction.runWriteCommandAction(project, "Delete Agent-Created File", null, () -> {
            try {
                vf.delete(this);
            } catch (Exception e) {
                LOG.warn("Failed to delete agent-created file: " + item.path(), e);
            }
        });
    }

    private void restoreDeletedFile(@NotNull ReviewItem item) {
        String content = item.beforeContent();
        if (content == null) return;
        WriteCommandAction.runWriteCommandAction(project, "Restore Deleted File", null, () -> {
            try {
                java.io.File ioFile = new java.io.File(item.path());
                java.io.File parent = ioFile.getParentFile();
                if (parent != null) {
                    VirtualFile parentVf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(parent);
                    if (parentVf != null) {
                        VirtualFile created = parentVf.createChildData(this, ioFile.getName());
                        created.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to restore deleted file: " + item.path(), e);
            }
        });
    }

    /**
     * Builds the structured revert nudge: {@code [User reverted file:ranges] Reason:...}
     * followed by a unified-diff body, followed by a re-plan nudge.
     */
    private @NotNull String buildRevertNudge(@NotNull ReviewItem item, @Nullable String reason) {
        String header;
        String body;
        switch (item.status()) {
            case MODIFIED -> {
                String before = snapshots.get(item.path());
                String after = readCurrentContent(item.path());
                List<ChangeRange> ranges = (before != null && after != null)
                    ? computeRanges(before, after) : Collections.emptyList();
                header = "[User reverted " + item.relativePath() + formatRanges(ranges) + "]";
                body = (before != null && after != null) ? buildUnifiedDiff(before, after) : "";
            }
            case ADDED -> {
                header = "[User reverted creation of " + item.relativePath() + "]";
                body = "";
            }
            case DELETED -> {
                header = "[User reverted deletion of " + item.relativePath() + "]";
                body = "";
            }
            default -> {
                header = "[User reverted " + item.relativePath() + "]";
                body = "";
            }
        }

        StringBuilder sb = new StringBuilder(header);
        if (reason != null && !reason.isBlank()) {
            sb.append(" Reason: ").append(reason.trim());
        }
        if (!body.isEmpty()) {
            sb.append('\n').append(body);
        }
        sb.append("\nPlease try a different approach for this file.");
        return sb.toString();
    }

    private static @NotNull String formatRanges(@NotNull List<ChangeRange> ranges) {
        if (ranges.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(":");
        boolean first = true;
        for (ChangeRange r : ranges) {
            if (!first) sb.append(',');
            first = false;
            int start = r.startLine() + 1;
            int end = Math.max(start, r.endLine());
            if (start == end) sb.append(start);
            else sb.append(start).append('-').append(end);
        }
        return sb.toString();
    }

    private @Nullable String readCurrentContent(@NotNull String path) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf == null) return null;
        // getDocument() requires a read action, not just getText().
        String text = ReadAction.compute(() -> {
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            return doc != null ? doc.getText() : null;
        });
        if (text != null) return text;
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Failed to read current content for review line counts: " + path, e);
            return null;
        }
    }

    static int countLines(@Nullable String content) {
        if (content == null || content.isEmpty()) return 0;
        return (int) content.lines().count();
    }

    private static @NotNull String buildUnifiedDiff(@NotNull String before, @NotNull String after) {
        String[] beforeLines = Diff.splitLines(before);
        String[] afterLines = Diff.splitLines(after);
        try {
            Diff.Change change = Diff.buildChanges(beforeLines, afterLines);
            StringBuilder sb = new StringBuilder();
            sb.append("```diff\n");
            while (change != null) {
                sb.append("@@ -").append(change.line0 + 1).append(',').append(change.deleted)
                    .append(" +").append(change.line1 + 1).append(',').append(change.inserted)
                    .append(" @@\n");
                for (int i = 0; i < change.deleted && (change.line0 + i) < beforeLines.length; i++) {
                    sb.append('-').append(beforeLines[change.line0 + i]);
                    if (!sb.toString().endsWith("\n")) sb.append('\n');
                }
                for (int i = 0; i < change.inserted && (change.line1 + i) < afterLines.length; i++) {
                    sb.append('+').append(afterLines[change.line1 + i]);
                    if (!sb.toString().endsWith("\n")) sb.append('\n');
                }
                change = change.link;
            }
            sb.append("```");
            return sb.toString();
        } catch (FilesTooBigForDiffException e) {
            return "```\n(diff too large to render)\n```";
        }
    }

    // ── Gating ──────────────────────────────────────────────────────────────

    @SuppressWarnings("java:S2222")
    public @Nullable String awaitReviewCompletion(@NotNull String operation) {
        if (!hasPendingChanges()) {
            reviewNotificationFired = false;
            return null;
        }

        int fileCount = countPending();

        if (!reviewNotificationFired) {
            reviewNotificationFired = true;
            notifyReviewRequired(operation);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            com.github.catatafishen.agentbridge.ui.review.ReviewPanelController
                .getInstance(project).expandReviewPanel();
        });

        PsiBridgeService psi = PsiBridgeService.getInstance(project);
        java.util.concurrent.Semaphore writeSemaphore = psi.getWriteToolSemaphore();
        java.util.concurrent.locks.ReentrantLock syncLock = psi.getCurrentSyncLock();

        if (syncLock != null) syncLock.unlock();
        writeSemaphore.release();
        try {
            while (hasPendingChanges() && pendingGateCancelMessage == null) {
                java.util.concurrent.CompletableFuture<Void> future = getOrCreateReviewCompletionFuture();
                if (!hasPendingChanges() || pendingGateCancelMessage != null) break;
                future.get(REVIEW_WAIT_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
            }
            String cancelMessage = pendingGateCancelMessage;
            if (cancelMessage != null) {
                pendingGateCancelMessage = null;
                return "Error: " + operation + " cancelled by user revert.\n" + cancelMessage;
            }
            flushBufferedRevertsToPendingNudge();
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            return formatReviewTimeoutError(operation, fileCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted while waiting for agent-edit review.";
        } catch (java.util.concurrent.ExecutionException e) {
            return "Error: Review wait failed: " + e.getCause();
        } finally {
            writeSemaphore.acquireUninterruptibly();
            if (syncLock != null) syncLock.lock();
        }
    }

    /**
     * Like {@link #awaitReviewCompletion} but scoped to a specific set of file paths.
     * Only PENDING files whose absolute path is in {@code scopedPaths} will block.
     * Files tracked by the review session but not in {@code scopedPaths} are ignored.
     * <p>
     * Use this for operations that only affect specific files (e.g. git commit),
     * as opposed to worktree-wide operations (branch switch, reset) which should
     * use the unscoped {@link #awaitReviewCompletion}.
     *
     * @param operation   human-readable name for error messages
     * @param scopedPaths absolute paths of files to check
     * @return error string if blocked or timed out, {@code null} if clear
     */
    public @Nullable String awaitReviewForPaths(
        @NotNull String operation,
        @NotNull Collection<String> scopedPaths) {
        if (!hasPendingIn(scopedPaths)) return null;

        int fileCount = countPendingIn(scopedPaths);

        if (!reviewNotificationFired) {
            reviewNotificationFired = true;
            notifyReviewRequired(operation);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            com.github.catatafishen.agentbridge.ui.review.ReviewPanelController
                .getInstance(project).expandReviewPanel();
        });

        PsiBridgeService psi = PsiBridgeService.getInstance(project);
        java.util.concurrent.Semaphore writeSemaphore = psi.getWriteToolSemaphore();
        java.util.concurrent.locks.ReentrantLock syncLock = psi.getCurrentSyncLock();

        if (syncLock != null) syncLock.unlock();
        writeSemaphore.release();
        try {
            while (hasPendingIn(scopedPaths) && pendingGateCancelMessage == null) {
                java.util.concurrent.CompletableFuture<Void> future = getOrCreateReviewCompletionFuture();
                if (!hasPendingIn(scopedPaths) || pendingGateCancelMessage != null) break;
                future.get(REVIEW_WAIT_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
            }
            String cancelMessage = pendingGateCancelMessage;
            if (cancelMessage != null) {
                pendingGateCancelMessage = null;
                return "Error: " + operation + " cancelled by user revert.\n" + cancelMessage;
            }
            flushBufferedRevertsToPendingNudge();
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            return formatReviewTimeoutError(operation, fileCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted while waiting for agent-edit review.";
        } catch (java.util.concurrent.ExecutionException e) {
            return "Error: Review wait failed: " + e.getCause();
        } finally {
            writeSemaphore.acquireUninterruptibly();
            if (syncLock != null) syncLock.lock();
        }
    }

    private int countPending() {
        int n = 0;
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            if (e.getValue() == ApprovalState.PENDING && pathIsTracked(e.getKey())) n++;
        }
        return n;
    }

    /**
     * Whether any PENDING tracked path lies inside {@code scopedPaths}. Used by
     * {@link #awaitReviewForPaths} for scope-limited gating.
     * <p>Package-private so regression tests can assert the contract that
     * git-amend must rely on (an empty {@code scopedPaths} never reports pending,
     * even when the unscoped session has pending changes).
     */
    boolean hasPendingIn(@NotNull Collection<String> scopedPaths) {
        Set<String> scope = scopedPaths instanceof Set ? (Set<String>) scopedPaths : new HashSet<>(scopedPaths);
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            if (e.getValue() == ApprovalState.PENDING && pathIsTracked(e.getKey()) && scope.contains(e.getKey())) {
                return true;
            }
        }
        return false;
    }

    private int countPendingIn(@NotNull Collection<String> scopedPaths) {
        Set<String> scope = scopedPaths instanceof Set ? (Set<String>) scopedPaths : new HashSet<>(scopedPaths);
        int n = 0;
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            if (e.getValue() == ApprovalState.PENDING && pathIsTracked(e.getKey()) && scope.contains(e.getKey())) {
                n++;
            }
        }
        return n;
    }

    private synchronized java.util.concurrent.CompletableFuture<Void> getOrCreateReviewCompletionFuture() {
        java.util.concurrent.CompletableFuture<Void> f = reviewCompletionFuture;
        if (f == null || f.isDone()) {
            f = new java.util.concurrent.CompletableFuture<>();
            reviewCompletionFuture = f;
        }
        return f;
    }

    private void completeGate() {
        java.util.concurrent.CompletableFuture<Void> f = reviewCompletionFuture;
        if (f != null && !f.isDone()) {
            f.complete(null);
        }
    }

    private void completeGateIfNoPending() {
        if (hasPendingChanges()) return;
        reviewNotificationFired = false;
        completeGate();
    }

    private void flushBufferedRevertsToPendingNudge() {
        String merged;
        synchronized (gateRevertBuffer) {
            if (gateRevertBuffer.isEmpty()) return;
            merged = String.join("\n\n", gateRevertBuffer);
            gateRevertBuffer.clear();
        }
        PsiBridgeService.getInstance(project).setPendingNudge(merged);
    }

    static @NotNull String formatReviewTimeoutError(@NotNull String operation, int fileCount) {
        return "Error: " + fileCount + (fileCount == 1 ? " file has" : " files have")
            + " not been approved or rejected by the user. '"
            + operation + "' cannot proceed until all pending agent edits are reviewed."
            + " The user must accept or revert the pending edits in the"
            + " Review panel (left of chat), then retry.";
    }

    public synchronized void endSession() {
        if (!started) return;
        started = false;

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
        wipeAllTrackedState();
        completeGate();
        if (sessionDisposable != null) {
            Disposer.dispose(sessionDisposable);
            sessionDisposable = null;
        }
    }

    private void wipeAllTrackedState() {
        // Guard project-service calls: AgentEditHighlighter may have never been instantiated,
        // so calling getInstance() during disposal triggers lazy creation against a disposed project.
        if (!project.isDisposed()) {
            AgentEditHighlighter.getInstance(project).clearAll();
        }
        snapshots.clear();
        deletedFiles.clear();
        newFiles.clear();
        approvals.clear();
        lastEditedAt.clear();
        linesAdded.clear();
        linesRemoved.clear();
        synchronized (gateRevertBuffer) {
            gateRevertBuffer.clear();
        }
        pendingGateCancelMessage = null;
        reviewNotificationFired = false;
        if (!project.isDisposed()) {
            com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications();
        }
        fireReviewStateChanged();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isProjectFile(@NotNull VirtualFile vf) {
        if (!vf.isValid() || vf.isDirectory()) return false;
        return ApplicationManager.getApplication().runReadAction(
            (Computable<Boolean>) () -> ProjectFileIndex.getInstance(project).isInContent(vf));
    }

    private boolean isProjectPath(@NotNull String path) {
        String basePath = project.getBasePath();
        if (basePath == null) return false;
        String normalizedBase = basePath.replace('\\', '/');
        String normalizedPath = path.replace('\\', '/');
        if (!normalizedPath.equals(normalizedBase) && !normalizedPath.startsWith(normalizedBase + "/")) {
            return false;
        }
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        return vf == null || isProjectFile(vf);
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

    private @NotNull Set<String> collectAllPaths() {
        Set<String> paths = new HashSet<>();
        paths.addAll(snapshots.keySet());
        paths.addAll(newFiles);
        paths.addAll(deletedFiles.keySet());
        return paths;
    }

    private void normalizeTrackedState() {
        Set<String> createdThenDeleted = new HashSet<>(newFiles);
        createdThenDeleted.retainAll(deletedFiles.keySet());
        for (String path : createdThenDeleted) {
            snapshots.remove(path);
            deletedFiles.remove(path);
            newFiles.remove(path);
            approvals.remove(path);
            lastEditedAt.remove(path);
            linesAdded.remove(path);
            linesRemoved.remove(path);
        }
        for (String path : new ArrayList<>(newFiles)) {
            snapshots.remove(path);
            recomputeLineCounts(path);
        }
        for (Map.Entry<String, String> entry : deletedFiles.entrySet()) {
            linesAdded.put(entry.getKey(), 0);
            linesRemoved.put(entry.getKey(), countLines(entry.getValue()));
        }
        Set<String> trackedPaths = collectAllPaths();
        approvals.keySet().retainAll(trackedPaths);
        lastEditedAt.keySet().retainAll(trackedPaths);
        linesAdded.keySet().retainAll(trackedPaths);
        linesRemoved.keySet().retainAll(trackedPaths);
    }

    private void recomputeLineCounts(@NotNull String path) {
        if (newFiles.contains(path)) {
            linesAdded.put(path, countLines(readCurrentContent(path)));
            linesRemoved.put(path, 0);
            return;
        }
        String deletedContent = deletedFiles.get(path);
        if (deletedContent != null) {
            linesAdded.put(path, 0);
            linesRemoved.put(path, countLines(deletedContent));
            return;
        }
        String before = snapshots.get(path);
        if (before == null) return;
        String after = readCurrentContent(path);
        if (after == null) return;
        int added = 0;
        int removed = 0;
        for (ChangeRange r : computeRanges(before, after)) {
            added += r.insertedCount();
            removed += r.deletedCount();
        }
        linesAdded.put(path, added);
        linesRemoved.put(path, removed);
    }

    /**
     * Drops oldest APPROVED rows until total snapshot bytes are under the project-wide
     * cap. Pending rows are never auto-evicted — the user controls them.
     */
    private void enforceTotalSnapshotCap() {
        long total = totalSnapshotBytes();
        if (total <= MAX_TOTAL_SNAPSHOT_BYTES) return;

        List<Map.Entry<String, Long>> approved = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastEditedAt.entrySet()) {
            if (approvals.get(e.getKey()) == ApprovalState.APPROVED) approved.add(e);
        }
        approved.sort(Map.Entry.comparingByValue());
        for (Map.Entry<String, Long> e : approved) {
            if (totalSnapshotBytes() <= MAX_TOTAL_SNAPSHOT_BYTES) return;
            clearTrackedPath(e.getKey());
        }
    }

    private long totalSnapshotBytes() {
        long total = 0;
        for (String s : snapshots.values()) total += s.length();
        for (String s : deletedFiles.values()) total += s.length();
        return total;
    }

    private void fireReviewStateChanged() {
        if (project.isDisposed()) return;
        project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
    }

    private void notifyReviewRequired(@NotNull String operation) {
        String title = "Review Required";
        String body = "'" + operation + "' is waiting for you to review agent edits.";

        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindowManager.getInstance(project).notifyByBalloon(
                "AgentBridge", MessageType.WARNING,
                "<b>" + title + "</b><br>" + body
            );
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

    // ── Listeners ───────────────────────────────────────────────────────────

    private class SessionDocumentListener implements DocumentListener {

        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            if (!isAgentEditActive()) return;

            Document doc = event.getDocument();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            if (vf == null || !vf.isValid()) return;

            String path = vf.getPath();
            // Re-edit of an APPROVED row: keep the existing tracking baseline (snapshot for
            // modified files, whole-file line count for new files) so the row stays stable.
            // Only flip the approval state back to PENDING so the user reviews the new change
            // set again (unless auto-approve is on).
            if (approvals.get(path) == ApprovalState.APPROVED && pathIsTracked(path)) {
                if (!isAutoApproveOn()) {
                    approvals.put(path, ApprovalState.PENDING);
                }
                lastEditedAt.put(path, System.currentTimeMillis());
                fireReviewStateChanged();
                return;
            }

            captureBeforeContent(vf, doc.getText());
        }

        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            Document doc = event.getDocument();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            if (vf == null || !vf.isValid()) return;

            boolean modifiedFile = snapshots.containsKey(vf.getPath());
            boolean addedFile = newFiles.contains(vf.getPath());
            if (modifiedFile) {
                AgentEditHighlighter.getInstance(project).refreshHighlights(vf);
            }
            if (isAgentEditActive() && (modifiedFile || addedFile)) {
                recomputeLineCounts(vf.getPath());
                lastEditedAt.put(vf.getPath(), System.currentTimeMillis());
                fireReviewStateChanged();
            }
        }
    }

    private class SessionVfsListener implements BulkFileListener {

        @Override
        public void before(@NotNull List<? extends VFileEvent> events) {
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
            for (VFileEvent event : events) {
                if (event instanceof VFileCreateEvent) {
                    VirtualFile vf = event.getFile();
                    if (vf != null && !vf.isDirectory() && isProjectFile(vf) && isAgentEditActive()) {
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

        private void tagOldPath(@NotNull VirtualFile vf) {
            if (vf.isDirectory()) return;
            String path = vf.getPath();
            if (snapshots.containsKey(path) || newFiles.contains(path)) {
                vf.putUserData(OLD_PATH_KEY, path);
            }
        }

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
            ApprovalState st = approvals.remove(oldPath);
            if (st != null) approvals.put(vf.getPath(), st);
            Long ts = lastEditedAt.remove(oldPath);
            if (ts != null) lastEditedAt.put(vf.getPath(), ts);
            Integer la = linesAdded.remove(oldPath);
            if (la != null) linesAdded.put(vf.getPath(), la);
            Integer lr = linesRemoved.remove(oldPath);
            if (lr != null) linesRemoved.put(vf.getPath(), lr);
        }
    }

    // ── PersistentStateComponent ────────────────────────────────────────────

    @Override
    public @Nullable PersistedState getState() {
        PersistedState state = new PersistedState();
        state.snapshots = new LinkedHashMap<>(snapshots);
        state.deletedFiles = new LinkedHashMap<>(deletedFiles);
        state.newFiles = new ArrayList<>(newFiles);
        Map<String, String> approvalsAsString = new LinkedHashMap<>();
        for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
            approvalsAsString.put(e.getKey(), e.getValue().name());
        }
        state.approvals = approvalsAsString;
        Map<String, String> tsAsString = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : lastEditedAt.entrySet()) {
            tsAsString.put(e.getKey(), Long.toString(e.getValue()));
        }
        state.lastEditedAt = tsAsString;
        Map<String, String> addedAsString = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : linesAdded.entrySet()) {
            addedAsString.put(e.getKey(), Integer.toString(e.getValue()));
        }
        state.linesAdded = addedAsString;
        Map<String, String> removedAsString = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : linesRemoved.entrySet()) {
            removedAsString.put(e.getKey(), Integer.toString(e.getValue()));
        }
        state.linesRemoved = removedAsString;
        return state;
    }

    @Override
    public void loadState(@NotNull PersistedState state) {
        snapshots.clear();
        if (state.snapshots != null) snapshots.putAll(state.snapshots);
        deletedFiles.clear();
        if (state.deletedFiles != null) deletedFiles.putAll(state.deletedFiles);
        newFiles.clear();
        if (state.newFiles != null) newFiles.addAll(state.newFiles);

        approvals.clear();
        if (state.approvals != null) {
            for (Map.Entry<String, String> e : state.approvals.entrySet()) {
                try {
                    approvals.put(e.getKey(), ApprovalState.valueOf(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    approvals.put(e.getKey(), ApprovalState.PENDING);
                }
            }
        }
        lastEditedAt.clear();
        if (state.lastEditedAt != null) {
            for (Map.Entry<String, String> e : state.lastEditedAt.entrySet()) {
                try {
                    lastEditedAt.put(e.getKey(), Long.parseLong(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // skip malformed entries
                }
            }
        }
        linesAdded.clear();
        if (state.linesAdded != null) {
            for (Map.Entry<String, String> e : state.linesAdded.entrySet()) {
                try {
                    linesAdded.put(e.getKey(), Integer.parseInt(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // skip malformed entries
                }
            }
        }
        linesRemoved.clear();
        if (state.linesRemoved != null) {
            for (Map.Entry<String, String> e : state.linesRemoved.entrySet()) {
                try {
                    linesRemoved.put(e.getKey(), Integer.parseInt(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // skip malformed entries
                }
            }
        }

        // If auto-approve was turned on while PENDING entries existed (or between
        // IDE restarts), upgrade them now so they don't block future git operations.
        if (isAutoApproveOn()) {
            for (Map.Entry<String, ApprovalState> e : approvals.entrySet()) {
                if (e.getValue() == ApprovalState.PENDING) {
                    e.setValue(ApprovalState.APPROVED);
                }
            }
        }
        normalizeTrackedState();
    }

    /**
     * Serialized review state. All maps use {@code String} values so IntelliJ's
     * {@code XmlSerializer} can round-trip them without custom converters.
     */
    public static final class PersistedState {
        public Map<String, String> snapshots = new HashMap<>();
        public Map<String, String> deletedFiles = new HashMap<>();
        public List<String> newFiles = new ArrayList<>();
        public Map<String, String> approvals = new HashMap<>();
        public Map<String, String> lastEditedAt = new HashMap<>();
        public Map<String, String> linesAdded = new HashMap<>();
        public Map<String, String> linesRemoved = new HashMap<>();
    }
}
