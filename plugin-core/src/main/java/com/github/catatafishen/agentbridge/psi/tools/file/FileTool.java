package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract base for file tools. Provides shared static utilities for
 * editor highlighting, agent label resolution, and deferred auto-format.
 */
public abstract class FileTool extends Tool {

    private static final Logger LOG = Logger.getInstance(FileTool.class);

    public static final Color HIGHLIGHT_EDIT = new Color(80, 160, 80, 40);
    public static final Color HIGHLIGHT_READ = new Color(80, 120, 200, 35);

    /**
     * File tools all accept a path argument, so they support the inside-project /
     * outside-project sub-permission split. Subclasses that don't take a path
     * (none currently) can override back to {@code false}.
     */
    @Override
    public boolean supportsPathSubPermissions() {
        return true;
    }

    // ── Deferred auto-format (per-project) ────────────────────────────────────

    private static final long AUTO_FORMAT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    /**
     * Bound each processor invocation so a timeout retains only a small failed chunk instead of
     * restarting an arbitrarily large queue from the beginning on every retry.
     */
    @VisibleForTesting
    static final int AUTO_FORMAT_BATCH_SIZE = 10;

    /**
     * Files larger than this threshold skip import optimization during deferred auto-format.
     * Import optimization resolves every symbol reference to determine unused imports, so it
     * remains size-guarded even though the processor now runs off the EDT.
     */
    public static final long MAX_BYTES_FOR_OPTIMIZE_IMPORTS = 50 * 1024L;

    /**
     * Files larger than this threshold skip both import optimization and reformatting.
     */
    public static final long MAX_BYTES_FOR_REFORMAT = 100 * 1024L;

    /**
     * Weak project keys keep per-project queues and locks scoped to the project lifetime.
     * Access to the map itself is synchronized; each state protects its own ordered path set.
     */
    private static final Map<Project, AutoFormatState> AUTO_FORMAT_STATES =
        Collections.synchronizedMap(new WeakHashMap<>());

    public static void queueAutoFormat(Project project, String path) {
        if (project.isDisposed()) return;
        getOrCreateAutoFormatState(project).add(path);
    }

    /**
     * Flushes deferred formatting and returns only after the JetBrains processors and document
     * save have actually completed.
     *
     * <p>The processor pipeline must run on a background thread. Calling this method from the EDT
     * schedules a background flush and returns {@code false}; callers that require a durable
     * working tree (notably Git write tools) must treat that as an incomplete flush and abort.
     */
    public static boolean flushPendingAutoFormat(Project project) {
        if (project.isDisposed()) {
            removeAutoFormatState(project);
            return false;
        }

        AutoFormatState state = getAutoFormatState(project);
        if (state == null || state.isIdle()) return true;

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().executeOnPooledThread(
                () -> flushPendingAutoFormat(project));
            return false;
        }

        return flushOnBackgroundThread(project, state);
    }

    private static boolean flushOnBackgroundThread(Project project, AutoFormatState state) {
        return flushOnBackgroundThread(project, state, AUTO_FORMAT_TIMEOUT_NANOS);
    }

    @VisibleForTesting
    static boolean flushOnBackgroundThread(Project project, AutoFormatState state,
                                           long timeoutNanos) {
        long deadlineNanos = System.nanoTime() + timeoutNanos;
        try {
            if (!tryAcquireFlushLock(state, deadlineNanos)) {
                LOG.warn("Deferred auto-format timed out waiting for another flush");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Deferred auto-format interrupted while waiting for the flush lock");
            return false;
        }

        try {
            return flushWhileLocked(project, state, deadlineNanos);
        } finally {
            state.flushLock.unlock();
        }
    }

    private static boolean tryAcquireFlushLock(AutoFormatState state, long deadlineNanos)
        throws InterruptedException {
        long lockWaitNanos = deadlineNanos - System.nanoTime();
        return lockWaitNanos > 0
            && state.flushLock.tryLock(lockWaitNanos, TimeUnit.NANOSECONDS);
    }

    static boolean flushWhileLocked(Project project, AutoFormatState state,
                                    long deadlineNanos) {
        return flushWhileLocked(
            project,
            state,
            deadlineNanos,
            (paths, deadline) -> processAutoFormatBatch(project, paths, deadline));
    }

    @VisibleForTesting
    static boolean flushWhileLocked(Project project, AutoFormatState state,
                                    long deadlineNanos,
                                    AutoFormatBatchProcessor processor) {
        List<String> remainingPaths = List.of();
        try {
            while (!project.isDisposed()) {
                if (remainingPaths.isEmpty()) {
                    remainingPaths = state.drain();
                    if (remainingPaths.isEmpty()) return true;
                }
                if (Thread.currentThread().isInterrupted()) {
                    state.requeueFirst(remainingPaths);
                    Thread.currentThread().interrupt();
                    LOG.warn("Deferred auto-format interrupted before processing");
                    return false;
                }

                int batchSize = Math.min(AUTO_FORMAT_BATCH_SIZE, remainingPaths.size());
                List<String> currentBatch =
                    new ArrayList<>(remainingPaths.subList(0, batchSize));
                if (!processor.process(currentBatch, deadlineNanos)) {
                    state.requeueFirst(remainingPaths);
                    return false;
                }
                remainingPaths = batchSize == remainingPaths.size()
                    ? List.of()
                    : new ArrayList<>(remainingPaths.subList(batchSize, remainingPaths.size()));
            }

            state.requeueFirst(remainingPaths);
            return false;
        } catch (ProcessCanceledException e) {
            state.requeueFirst(remainingPaths);
            LOG.warn("Deferred auto-format cancelled before completion");
            return false;
        } catch (RuntimeException e) {
            state.requeueFirst(remainingPaths);
            LOG.warn("Deferred auto-format failed; queued files were retained", e);
            return false;
        }
    }

    private static boolean processAutoFormatBatch(Project project, List<String> paths,
                                                  long deadlineNanos) {
        return runAutoFormatWithDeadline(
            deadlineNanos, indicator -> runAutoFormatBatch(project, paths, indicator));
    }

    @VisibleForTesting
    static boolean runAutoFormatWithDeadline(long deadlineNanos,
                                             AutoFormatBatchRunner runner) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            LOG.warn("Deferred auto-format timed out before processing could start");
            return false;
        }

        ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        ScheduledFuture<?> cancellation = com.intellij.util.concurrency.AppExecutorUtil
            .getAppScheduledExecutorService()
            .schedule(indicator::cancel, remainingNanos, TimeUnit.NANOSECONDS);
        AtomicBoolean completed = new AtomicBoolean(false);
        try {
            ProgressManager.getInstance().runProcess(
                () -> completed.set(runner.run(indicator)),
                indicator);
            if (indicator.isCanceled()) {
                LOG.warn("Deferred auto-format exceeded the 30-second deadline");
                return false;
            }
            return completed.get();
        } finally {
            cancellation.cancel(false);
        }
    }

    private static boolean runAutoFormatBatch(Project project, List<String> paths,
                                              ProgressIndicator indicator) {
        return runAutoFormatBatch(
            project,
            paths,
            indicator,
            (files, optimizeImports) ->
                runLayoutProcessor(project, files, optimizeImports, indicator),
            FileDocumentManager.getInstance());
    }

    @VisibleForTesting
    static boolean runAutoFormatBatch(Project project, List<String> paths,
                                      ProgressIndicator indicator,
                                      LayoutProcessorRunner runner,
                                      FileDocumentManager documentManager) {
        FormatPlan plan = createFormatPlan(project, paths, indicator);
        boolean formatted = runFormatProcessors(
            plan.optimizeAndReformat(),
            plan.reformatOnly(),
            runner);
        if (!formatted) return false;

        indicator.checkCanceled();
        return saveProcessedDocuments(
            documentManager, plan.documents(), paths.size());
    }

    @VisibleForTesting
    static boolean runFormatProcessors(List<PsiFile> optimizeAndReformat,
                                       List<PsiFile> reformatOnly,
                                       LayoutProcessorRunner runner) {
        if (!runner.run(optimizeAndReformat, true)) return false;
        return runner.run(reformatOnly, false);
    }

    @VisibleForTesting
    static boolean saveProcessedDocuments(FileDocumentManager documentManager,
                                          List<Document> documents,
                                          int processedPathCount) {
        documentManager.saveAllDocuments();
        for (Document document : documents) {
            if (documentManager.isDocumentUnsaved(document)) {
                LOG.warn("Deferred auto-format completed, but a processed document remained unsaved");
                return false;
            }
        }

        LOG.info("Deferred auto-format completed for " + processedPathCount + " queued file(s)");
        return true;
    }

    private static FormatPlan createFormatPlan(Project project, List<String> paths,
                                               ProgressIndicator indicator) {
        List<PsiFile> optimizeAndReformat = new ArrayList<>();
        List<PsiFile> reformatOnly = new ArrayList<>();
        List<Document> documents = new ArrayList<>();

        for (String path : paths) {
            indicator.checkCanceled();
            ResolvedFormatFile resolved = resolveFormatFile(project, path);
            if (resolved != null) {
                addToFormatPlan(resolved, optimizeAndReformat, reformatOnly, documents);
            }
        }

        return new FormatPlan(optimizeAndReformat, reformatOnly, documents);
    }

    @Nullable
    private static ResolvedFormatFile resolveFormatFile(Project project, String path) {
        VirtualFile virtualFile = ToolUtils.resolveVirtualFile(project, path);
        if (!isUsableFormatFile(virtualFile)) {
            if (virtualFile != null && virtualFile.isValid() && !virtualFile.isWritable()) {
                LOG.info("Deferred auto-format skipped (read-only): " + path);
            }
            return null;
        }

        long fileBytes = virtualFile.getLength();
        if (fileBytes > MAX_BYTES_FOR_REFORMAT) {
            LOG.info("Deferred auto-format skipped (file too large: "
                + fileBytes + " bytes): " + path);
            return null;
        }

        return ReadAction.nonBlocking(() -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                if (psiFile == null) return null;
                Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
                return new ResolvedFormatFile(psiFile, document, fileBytes);
            })
            .expireWith(project)
            .executeSynchronously();
    }

    @VisibleForTesting
    static boolean isUsableFormatFile(@Nullable VirtualFile virtualFile) {
        return virtualFile != null && virtualFile.isValid() && virtualFile.isWritable();
    }

    @VisibleForTesting
    static void addToFormatPlan(ResolvedFormatFile resolved,
                                List<PsiFile> optimizeAndReformat,
                                List<PsiFile> reformatOnly,
                                List<Document> documents) {
        if (resolved.document() != null) documents.add(resolved.document());
        if (resolved.fileBytes() <= MAX_BYTES_FOR_OPTIMIZE_IMPORTS) {
            optimizeAndReformat.add(resolved.psiFile());
        } else {
            reformatOnly.add(resolved.psiFile());
        }
    }

    private static boolean runLayoutProcessor(Project project, List<PsiFile> files,
                                              boolean optimizeImports,
                                              ProgressIndicator indicator) {
        if (files.isEmpty()) return true;

        PsiFile[] fileArray = files.toArray(new PsiFile[0]);
        AbstractLayoutCodeProcessor processor =
            new ReformatCodeProcessor(project, fileArray, null, false);
        if (optimizeImports) {
            processor = new OptimizeImportsProcessor(processor);
        }
        processor.setProcessAllFilesAsSingleUndoStep(false);
        return processor.processFilesUnderProgress(indicator);
    }

    private static AutoFormatState getOrCreateAutoFormatState(Project project) {
        synchronized (AUTO_FORMAT_STATES) {
            return AUTO_FORMAT_STATES.computeIfAbsent(project, ignored -> new AutoFormatState());
        }
    }

    @Nullable
    private static AutoFormatState getAutoFormatState(Project project) {
        synchronized (AUTO_FORMAT_STATES) {
            return AUTO_FORMAT_STATES.get(project);
        }
    }

    private static void removeAutoFormatState(Project project) {
        synchronized (AUTO_FORMAT_STATES) {
            AUTO_FORMAT_STATES.remove(project);
        }
    }

    @VisibleForTesting
    record ResolvedFormatFile(PsiFile psiFile, @Nullable Document document, long fileBytes) {
    }

    private record FormatPlan(List<PsiFile> optimizeAndReformat,
                              List<PsiFile> reformatOnly,
                              List<Document> documents) {
    }

    @FunctionalInterface
    interface AutoFormatBatchRunner {
        boolean run(ProgressIndicator indicator);
    }

    @FunctionalInterface
    interface AutoFormatBatchProcessor {
        boolean process(List<String> paths, long deadlineNanos);
    }

    @FunctionalInterface
    interface LayoutProcessorRunner {
        boolean run(List<PsiFile> files, boolean optimizeImports);
    }

    @VisibleForTesting
    static final class AutoFormatState {
        private final LinkedHashSet<String> pendingPaths = new LinkedHashSet<>();
        @VisibleForTesting
        final ReentrantLock flushLock = new ReentrantLock();

        void add(String path) {
            synchronized (pendingPaths) {
                pendingPaths.add(path);
            }
        }

        @VisibleForTesting
        boolean isIdle() {
            if (!flushLock.tryLock()) return false;
            try {
                return !hasPending();
            } finally {
                flushLock.unlock();
            }
        }

        private boolean hasPending() {
            synchronized (pendingPaths) {
                return !pendingPaths.isEmpty();
            }
        }

        List<String> drain() {
            synchronized (pendingPaths) {
                if (pendingPaths.isEmpty()) return List.of();
                List<String> paths = new ArrayList<>(pendingPaths);
                pendingPaths.clear();
                return paths;
            }
        }

        void requeueFirst(List<String> paths) {
            if (paths.isEmpty()) return;
            synchronized (pendingPaths) {
                LinkedHashSet<String> combined = new LinkedHashSet<>(paths);
                combined.addAll(pendingPaths);
                pendingPaths.clear();
                pendingPaths.addAll(combined);
            }
        }
    }

    // ── Agent label ───────────────────────────────────────────────────────────

    /**
     * Returns a label like "ui-reviewer", "claude-sonnet-4.5", or "Agent" as fallback.
     */
    public static String agentLabel(Project project) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        return resolveLabel(settings.getActiveAgentLabel(), settings.getSelectedModel());
    }

    /**
     * Pure logic: picks the best label from an (agentLabel, modelName) pair.
     * Returns agentLabel if non-blank, else modelName if non-blank, else "Agent".
     */
    static String resolveLabel(String agentLabel, String modelName) {
        if (agentLabel != null && !agentLabel.isEmpty()) return agentLabel;
        return (modelName != null && !modelName.isEmpty()) ? modelName : "Agent";
    }

    // ── Follow file / editor highlighting ─────────────────────────────────────

    /**
     * Guard against reentrant navigate() calls. IntelliJ's navigate() pumps EDT events
     * while waiting for tab creation, which can dispatch another followFileIfEnabled.
     * Two overlapping tab insertions race inside JBTabsImpl.updateText() causing NPE.
     * Per-project map ensures one navigation at a time per window.
     */
    private static final ConcurrentHashMap<Project, AtomicBoolean> NAVIGATING =
        new ConcurrentHashMap<>();

    private static final long PROJECT_VIEW_COOLDOWN_MS = 5_000;
    private static volatile long lastProjectViewSelectMs;

    /**
     * Minimum interval between file-open navigations. When multiple tool calls
     * arrive in a burst (e.g. 10+ read_file calls within 1 second), each one
     * triggers {@code followFileIfEnabled}, which opens the file in the editor.
     * Opening a file triggers IntelliJ's VCS integration (git log, git blame for
     * annotations), and cascading git operations can block EDT for tens of seconds.
     *
     * <p>This cooldown ensures only the first file-open in a burst window actually
     * navigates; subsequent calls within the window are silently dropped. The
     * {@link #NAVIGATING} guard prevents <i>reentrant</i> calls within a single
     * EDT dispatch, but does not prevent sequential {@code invokeLater} dispatches
     * from opening many files in rapid succession — this cooldown fills that gap.
     *
     * <p><b>Incident reference:</b> 2026-05-09: 10+ simultaneous read_file calls
     * triggered VCS annotations on each file → git log (54s) + git blame (80s)
     * blocked EDT for 72 seconds → permanent JCEF OSR freeze.
     *
     * @see com.github.catatafishen.agentbridge.ui.EdtFreezeRecovery
     */
    private static final long FOLLOW_FILE_COOLDOWN_MS = 2_000;
    private static final AtomicLong lastFollowFileMs = new AtomicLong();

    /**
     * Notifies the {@link AgentEditSession} that a file is about to be modified.
     * Starts the session (if the review setting is enabled), captures a before-snapshot,
     * and sets the agent-edit marker so the session's document listener can distinguish
     * agent edits from unrelated changes (branch switches, IDE reformats, etc.).
     * <p>
     * <b>Callers must</b> invoke {@link #notifyEditComplete()} in a {@code finally} block
     * after the write completes.
     *
     * @param project the current project
     * @param vf      the virtual file about to be modified (may be null for new files)
     * @param doc     the document (used to read current content); may be null
     */
    public static void notifyBeforeEdit(Project project, VirtualFile vf, Document doc) {
        AgentEditSession.markAgentEditStart();
        AgentEditSession session = AgentEditSession.getInstance(project);
        session.ensureStarted();
        if (vf != null && doc != null) {
            session.captureBeforeContent(vf, doc.getText());
        }
    }

    /**
     * Clears the agent-edit marker after a tool's write operation completes.
     * Always call in a {@code finally} block paired with {@link #notifyBeforeEdit}.
     */
    public static void notifyEditComplete() {
        AgentEditSession.markAgentEditEnd();
    }

    /**
     * Notifies the {@link AgentEditSession} that a new file was created.
     *
     * @param project the current project
     * @param path    the path of the newly created file
     */
    public static void notifyFileCreated(Project project, String path) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        session.ensureStarted();
        session.registerNewFile(path);
    }

    /**
     * Notifies the {@link AgentEditSession} that a file is about to be deleted.
     *
     * @param project the current project
     * @param vf      the file about to be deleted
     */
    public static void notifyBeforeDelete(Project project, VirtualFile vf) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        session.ensureStarted();
        if (vf != null && vf.isValid() && !vf.isDirectory()) {
            try {
                byte[] bytes = vf.contentsToByteArray();
                String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                session.captureBeforeContent(vf, content);
                session.registerDeletedFile(vf.getPath(), content);
            } catch (Exception e) {
                LOG.warn("Failed to capture content before delete: " + vf.getPath(), e);
            }
        }
    }

    /**
     * Opens the file in the editor if "Follow Agent Files" is enabled.
     * Scrolls to the middle of [startLine, endLine] and briefly highlights the region.
     */
    public static void followFileIfEnabled(Project project, String pathStr, int startLine, int endLine,
                                           Color highlightColor, String actionLabel) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            LOG.debug("followFileIfEnabled skipped: setting disabled for project " + project.getName());
            return;
        }

        long now = System.currentTimeMillis();
        long prev = lastFollowFileMs.get();
        if (now - prev < FOLLOW_FILE_COOLDOWN_MS
            || !lastFollowFileMs.compareAndSet(prev, now)) {
            LOG.info("followFileIfEnabled throttled: " + pathStr
                + " (cooldown " + FOLLOW_FILE_COOLDOWN_MS + "ms)");
            return;
        }

        EdtUtil.invokeLater(() -> {
            AtomicBoolean nav = NAVIGATING.computeIfAbsent(project, k -> new AtomicBoolean(false));
            if (!nav.compareAndSet(false, true)) {
                LOG.info("followFileIfEnabled skipped: already navigating for project " + project.getName());
                return;
            }
            try {
                VirtualFile vf = ToolUtils.resolveVirtualFile(project, pathStr);
                if (vf == null) {
                    LOG.warn("followFileIfEnabled failed: file not found: " + pathStr);
                    return;
                }

                // Don't steal focus when the user is actively typing in the chat prompt.
                boolean focus = !PsiBridgeService.isUserTypingInChat(project);

                FileEditorManager fem = FileEditorManager.getInstance(project);
                int midLine = (startLine > 0 && endLine > 0)
                    ? (startLine + endLine) / 2
                    : Math.max(startLine, 1);
                if (midLine > 0) {
                    new OpenFileDescriptor(project, vf, midLine - 1, 0).navigate(focus);
                    scrollAndHighlight(fem, vf, startLine, endLine, midLine, highlightColor, actionLabel);
                } else {
                    fem.openFile(vf, focus);
                }

                selectInProjectView(project, vf);
            } finally {
                nav.set(false);
            }
        });
    }

    /**
     * Scrolls the Project View tree to the given file if the Project tool window is already open.
     * Throttled to avoid excessive tree navigation during rapid file access.
     * Skipped entirely when the chat prompt has focus, and also skipped when the Project window
     * is not visible — we never force it open, to avoid hijacking the user's terminal or other panel.
     */
    private static void selectInProjectView(Project project, VirtualFile vf) {
        long now = System.currentTimeMillis();
        if (now - lastProjectViewSelectMs < PROJECT_VIEW_COOLDOWN_MS) return;
        if (PsiBridgeService.isUserTypingInChat(project)) return;
        lastProjectViewSelectMs = now;

        try {
            var twm = ToolWindowManager.getInstance(project);
            var tw = twm.getToolWindow("Project");
            // Only scroll the tree when the Project window is already open — don't force it
            // visible when the user is focused on a terminal or other non-project panel.
            if (tw == null || !tw.isVisible()) return;
            com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, vf, false);
        } catch (Exception e) {
            LOG.debug("Project view select failed", e);
        }
    }

    private static void scrollAndHighlight(FileEditorManager fem, VirtualFile vf,
                                           int startLine, int endLine, int midLine,
                                           Color highlightColor, String actionLabel) {
        for (var fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor textEditor) {
                var editor = textEditor.getEditor();
                Document doc = editor.getDocument();
                int lineCount = doc.getLineCount();
                if (midLine - 1 < lineCount) {
                    int visibleLines = editor.getScrollingModel().getVisibleArea().height
                        / editor.getLineHeight();
                    int rangeLines = endLine - startLine + 1;
                    boolean fitsInViewport = startLine <= 0 || endLine <= 0 || rangeLines <= visibleLines;

                    if (fitsInViewport) {
                        int offset = doc.getLineStartOffset(Math.max(midLine - 1, 0));
                        editor.getCaretModel().moveToOffset(offset);
                        editor.getScrollingModel().scrollTo(
                            editor.offsetToLogicalPosition(offset), ScrollType.CENTER);
                    } else {
                        int topLine = Math.max(startLine - 2, 1);
                        int offset = doc.getLineStartOffset(Math.max(topLine - 1, 0));
                        editor.getCaretModel().moveToOffset(offset);
                        editor.getScrollingModel().scrollTo(
                            editor.offsetToLogicalPosition(offset), ScrollType.CENTER);
                    }

                    flashLineRange(editor, doc, startLine, endLine, highlightColor, actionLabel, textEditor);
                }
                break;
            }
        }
    }

    private static void flashLineRange(com.intellij.openapi.editor.Editor editor, Document doc,
                                       int startLine, int endLine,
                                       Color color, String actionLabel,
                                       TextEditor disposableParent) {
        int lineCount = doc.getLineCount();
        if (startLine <= 0 || endLine <= 0 || startLine > lineCount) return;

        // When a review session is active AND persistent highlights are enabled,
        // AgentEditHighlighter already marks edits persistently — skip the transient flash
        // to avoid double-marking. If persistent highlights are disabled, the flash is the
        // only visual feedback, so it must fire regardless of session state.
        Project project = editor.getProject();
        if (color == HIGHLIGHT_EDIT && project != null
            && AgentEditSession.getInstance(project).isActive()
            && McpServerSettings.getInstance(project).isShowReviewInEditor()) {
            return;
        }

        int hlStart = doc.getLineStartOffset(startLine - 1);
        int hlEnd = doc.getLineEndOffset(Math.min(endLine, lineCount) - 1);
        if (hlEnd <= hlStart) return;

        var attrs = new TextAttributes();
        attrs.setBackgroundColor(color);
        var markup = editor.getMarkupModel();
        var hl = markup.addRangeHighlighter(
            hlStart, hlEnd,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE);

        var inlay = editor.getInlayModel().addBlockElement(
            hlStart, true, true, 0, new AgentActionRenderer(actionLabel, color));

        var alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, disposableParent);
        alarm.addRequest(() -> {
            try {
                markup.removeHighlighter(hl);
                if (inlay != null) inlay.dispose();
            } catch (Exception ignored) {
                // Safe to ignore: highlighter cleanup is non-critical
            }
        }, 2500);
    }

    /**
     * Renders a small label ("Agent is reading" / "Agent is editing") as a block inlay above
     * the highlighted region. Uses the same tint color as the range highlight.
     */
    private static class AgentActionRenderer implements EditorCustomElementRenderer {
        private final String text;
        private final Color bgColor;

        AgentActionRenderer(String text, Color bgColor) {
            this.text = text;
            this.bgColor = new Color(
                bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(),
                Math.min(bgColor.getAlpha() * 3, 255));
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            var editor = inlay.getEditor();
            var metrics = editor.getContentComponent().getFontMetrics(
                editor.getColorsScheme().getFont(EditorFontType.PLAIN));
            return metrics.stringWidth(text) + 16;
        }

        @Override
        public int calcHeightInPixels(@NotNull Inlay inlay) {
            return inlay.getEditor().getLineHeight();
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                          @NotNull Rectangle targetRegion, @NotNull TextAttributes textAttributes) {
            var g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bgColor);
            g2.fillRoundRect(targetRegion.x, targetRegion.y,
                targetRegion.width, targetRegion.height, 6, 6);
            var editor = inlay.getEditor();
            g2.setFont(editor.getColorsScheme().getFont(EditorFontType.PLAIN));
            g2.setColor(editor.getColorsScheme().getDefaultForeground());
            var metrics = g2.getFontMetrics();
            int textY = targetRegion.y + (targetRegion.height + metrics.getAscent() - metrics.getDescent()) / 2;
            g2.drawString(text, targetRegion.x + 8, textY);
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Parses a single line of git porcelain status output into a human-readable annotation.
     * The first char is the index (staging area) status, the second is the work-tree status.
     * Pure function — no IDE or git dependency.
     */
    static String parseGitPorcelainLine(@NotNull String porcelainLine) {
        if (porcelainLine.isEmpty()) return " [git: clean]";
        if (porcelainLine.length() < 2) return " [git: " + porcelainLine.trim() + "]";
        // Parse porcelain format: XY filename
        // X = index state, Y = work-tree state
        char indexState = porcelainLine.charAt(0);
        char workTreeState = porcelainLine.charAt(1);
        if (indexState == '?' && workTreeState == '?') return " [git: untracked]";
        if (indexState == 'A') return " [git: new file, staged]";
        if (indexState != ' ' && workTreeState == ' ') return " [git: staged]";
        if (indexState == ' ' && workTreeState == 'M') return " [git: modified, not staged]";
        if (indexState == 'M' && workTreeState == 'M') return " [git: partially staged]";
        if (workTreeState == 'D') return " [git: deleted]";
        return " [git: " + porcelainLine.substring(0, 2).trim() + "]";
    }

    /**
     * Resolves the git executable to an absolute path by probing well-known locations.
     * Falls back to the bare {@code "git"} command only as a last resort (PATH lookup).
     */
    private static String resolveGitExecutable() {
        for (String candidate : List.of(
            "/usr/bin/git",
            "/usr/local/bin/git",
            "/opt/homebrew/bin/git",
            "/opt/local/bin/git"
        )) {
            if (new java.io.File(candidate).canExecute()) {
                return candidate;
            }
        }
        return "git";
    }

    /**
     * Returns a short git status annotation for a file, e.g. "[git: modified, not staged]".
     * Runs a single git command via ProcessBuilder. Returns empty string on any error
     * or if the file is not in a git repo.
     * <p>
     * Antipattern (DESIGN-PRINCIPLES.md): ProcessBuilder for git commands. Should use
     * ChangeListManager.getInstance(project).getChange(virtualFile) instead. Kept because
     * ChangeListManager requires a VirtualFile lookup and VCS refresh that may not be available
     * immediately after file writes in the MCP tool flow.
     */
    protected static String getGitFileStatus(Project project, String pathStr) {
        String basePath = project.getBasePath();
        if (basePath == null) return "";

        try {
            ProcessBuilder pb = new ProcessBuilder(
                resolveGitExecutable(), "--no-pager", "status", "--porcelain", "--", pathStr);
            pb.directory(new java.io.File(basePath));
            pb.redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return parseGitPorcelainLine(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    protected @Nullable String guardExternalWrite(@NotNull String pathStr) {
        java.nio.file.Path absPath;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(pathStr);
            if (p.isAbsolute()) {
                absPath = p.normalize();
            } else {
                String base = project.getBasePath();
                if (base == null) return null;
                absPath = java.nio.file.Path.of(base).resolve(pathStr).normalize();
            }
        } catch (Exception e) {
            return null;  // invalid path syntax — let subsequent logic handle it
        }

        com.github.catatafishen.agentbridge.psi.tools.project.ExternalDirRegistry registry =
            com.github.catatafishen.agentbridge.psi.tools.project.ExternalDirRegistry.getInstance(project);
        if (registry.isExternalPath(absPath.toString())) {
            return "Error: '" + pathStr + "' is inside an attached external directory. "
                + "External directories are read-only. Detach it first with detach_external_dir if you need to modify it.";
        }
        return null;
    }

    protected FileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.FILE;
    }
}
