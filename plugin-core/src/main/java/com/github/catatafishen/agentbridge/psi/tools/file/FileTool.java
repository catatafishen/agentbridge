package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
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

        EdtUtil.invokeAndWait(() -> {
            for (String pathStr : paths) {
                try {
                    VirtualFile vf = ToolUtils.resolveVirtualFile(project, pathStr);
                    if (vf != null) {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                        if (psiFile != null) {
                            WriteAction.run(() ->
                                CommandProcessor.getInstance().executeCommand(project, () -> {
                                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                                    new OptimizeImportsProcessor(project, psiFile).run();
                                    new ReformatCodeProcessor(psiFile, false).run();
                                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                                }, "Auto-Format (Deferred)", null)
                            );
                            LOG.info("Deferred auto-format: " + pathStr);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Deferred auto-format failed for " + pathStr + ": " + e.getMessage());
                }
            }
            // Save all documents to disk so git sees the formatted content
            WriteAction.run(() ->
                FileDocumentManager.getInstance().saveAllDocuments());
        });
    }

    // ── Agent label ───────────────────────────────────────────────────────────

    /**
     * Returns a label like "ui-reviewer", "claude-sonnet-4.5", or "Agent" as fallback.
     */
    public static String agentLabel(Project project) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        String agent = settings.getActiveAgentLabel();
        if (agent != null && !agent.isEmpty()) return agent;
        String model = settings.getSelectedModel();
        return (model != null && !model.isEmpty()) ? model : "Agent";
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

                // Don't steal focus when the chat prompt has focus — prevents keystroke leaks
                boolean focus = !PsiBridgeService.isChatToolWindowActive(project);

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
     * Selects the given file in the Project View tree without requesting focus.
     * Throttled to avoid excessive tree navigation during rapid file access.
     */
    private static void selectInProjectView(Project project, VirtualFile vf) {
        long now = System.currentTimeMillis();
        if (now - lastProjectViewSelectMs < PROJECT_VIEW_COOLDOWN_MS) return;
        lastProjectViewSelectMs = now;

        try {
            var twm = ToolWindowManager.getInstance(project);
            var tw = twm.getToolWindow("Project");
            if (tw != null && !tw.isVisible()) {
                tw.show();
            }
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

    protected FileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.FILE;
    }
}
