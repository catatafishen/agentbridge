package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base for file tools. Provides shared static utilities for
 * editor highlighting, agent label resolution, and deferred auto-format.
 */
public abstract class FileTool extends Tool {

    private static final Logger LOG = Logger.getInstance(FileTool.class);

    public static final Color HIGHLIGHT_EDIT = new Color(80, 160, 80, 40);
    public static final Color HIGHLIGHT_READ = new Color(80, 120, 200, 35);

    // ── Deferred auto-format (per-project) ────────────────────────────────────

    /**
     * Maximum number of files to process in a single auto-format flush.
     * Prevents EDT saturation when many files are queued: each file's format
     * runs inside a WriteCommandAction (blocking EDT). Overflow is requeued.
     */
    private static final int MAX_AUTO_FORMAT_FILES = 10;

    private static final ConcurrentHashMap<Project, Set<String>> PENDING_AUTO_FORMAT =
        new ConcurrentHashMap<>();

    public static void queueAutoFormat(Project project, String path) {
        PENDING_AUTO_FORMAT.computeIfAbsent(project,
            k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(path);
    }

    public static void flushPendingAutoFormat(Project project) {
        Set<String> pathSet = PENDING_AUTO_FORMAT.remove(project);
        if (pathSet == null || pathSet.isEmpty()) return;

        List<String> paths = new ArrayList<>(pathSet);

        // Safety cap: if too many files are queued, process only the first batch and requeue
        // the rest. This prevents EDT saturation from blocking the editor for 30+ seconds.
        if (paths.size() > MAX_AUTO_FORMAT_FILES) {
            LOG.warn("Auto-format batch capped at " + MAX_AUTO_FORMAT_FILES + " of " + paths.size()
                + " files; requeueing overflow for the next turn");
            List<String> overflow = paths.subList(MAX_AUTO_FORMAT_FILES, paths.size());
            PENDING_AUTO_FORMAT.merge(project,
                Collections.synchronizedSet(new LinkedHashSet<>(overflow)),
                (existing, added) -> {
                    existing.addAll(added);
                    return existing;
                });
            paths = new ArrayList<>(paths.subList(0, MAX_AUTO_FORMAT_FILES));
        }

        if (com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread()) {
            for (String pathStr : paths) {
                formatSingleFile(project, pathStr);
            }
            saveAllDocuments();
            return;
        }

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        scheduleNextFormat(project, paths, 0, latch, cancelled);

        try {
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                // Stop the EDT chain — without this, remaining invokeLater tasks would keep running
                // after we return, blocking the EDT and causing subsequent invokeAndWait calls to time out.
                cancelled.set(true);
                LOG.warn("flushPendingAutoFormat timed out after 30 seconds");
            }
        } catch (InterruptedException e) {
            cancelled.set(true);
            Thread.currentThread().interrupt();
            LOG.warn("flushPendingAutoFormat interrupted");
        }
    }

    /**
     * Chains auto-format operations sequentially via {@code invokeLater}, one file per EDT event.
     * <p>
     * Unlike bulk-dispatching all files at once, chaining allows the EDT to process paint and
     * input events between each file — keeping the editor responsive during multi-file formatting.
     * The {@code cancelled} flag is checked before each step so that a background-thread timeout
     * in {@link #flushPendingAutoFormat} immediately stops further scheduling.
     */
    private static void scheduleNextFormat(Project project, List<String> paths, int index,
                                           java.util.concurrent.CountDownLatch latch,
                                           AtomicBoolean cancelled) {
        if (cancelled.get() || project.isDisposed()) {
            latch.countDown(); // no-op if background thread already timed out
            return;
        }
        if (index >= paths.size()) {
            saveAllDocuments();
            latch.countDown();
            return;
        }
        EdtUtil.invokeLater(() -> {
            if (!cancelled.get() && !project.isDisposed()) {
                formatSingleFile(project, paths.get(index));
            }
            scheduleNextFormat(project, paths, index + 1, latch, cancelled);
        });
    }

    /**
     * Formats and optimizes imports for a single file inside a write action.
     * Shared by both the EDT and non-EDT paths of {@link #flushPendingAutoFormat}.
     */
    private static void formatSingleFile(Project project, String pathStr) {
        try {
            VirtualFile vf = ToolUtils.resolveVirtualFile(project, pathStr);
            if (vf == null) return;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return;
            // AbstractLayoutCodeProcessor.runProcessFile() calls ensureFilesWritable() internally.
            // For non-project files this may show an "unlock file?" dialog — which is illegal
            // inside a write action (causes "AWT events not allowed inside write action").
            // Pre-resolve write access here, outside the write action, so the dialog (if any)
            // is shown before we acquire the write lock.
            ReadonlyStatusHandler.OperationStatus writeStatus =
                ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(Collections.singletonList(vf));
            if (writeStatus.hasReadonlyFiles()) {
                LOG.info("Deferred auto-format skipped (read-only): " + pathStr);
                return;
            }
            Document doc = psiFile.getViewProvider().getDocument();
            WriteCommandAction.runWriteCommandAction(project, "Auto-Format (Deferred)", null, () -> {
                if (doc != null)
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                new OptimizeImportsProcessor(project, psiFile).run();
                new ReformatCodeProcessor(psiFile, false).run();
                if (doc != null)
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
            });
            LOG.info("Deferred auto-format: " + pathStr);
        } catch (Exception e) {
            LOG.warn("Deferred auto-format failed for " + pathStr + ": " + e.getMessage());
        }
    }

    private static void saveAllDocuments() {
        try {
            WriteAction.run(() -> FileDocumentManager.getInstance().saveAllDocuments());
        } catch (Exception e) {
            LOG.warn("Failed to save documents after auto-format", e);
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

        // When a review session is active, edit highlights are managed persistently
        // by AgentEditHighlighter; skip the 2.5s transient flash to avoid double-marking.
        Project project = editor.getProject();
        if (color == HIGHLIGHT_EDIT && project != null
            && AgentEditSession.getInstance(project).isActive()) {
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
     * Returns a short git status annotation for a file, e.g. "[git: modified, not staged]".
     * Runs a single git command via ProcessBuilder. Returns empty string on any error
     * or if the file is not in a git repo.
     */
    protected static String getGitFileStatus(Project project, String pathStr) {
        String basePath = project.getBasePath();
        if (basePath == null) return "";

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--no-pager", "status", "--porcelain", "--", pathStr);
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

    protected FileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.FILE;
    }
}
