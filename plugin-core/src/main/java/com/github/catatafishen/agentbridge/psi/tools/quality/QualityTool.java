package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.ui.renderers.InspectionResultRenderer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base for code quality tools. Provides shared constants and
 * utility methods used by multiple quality tool implementations.
 */
public abstract class QualityTool extends Tool {

    protected static final String ERROR_IDE_INITIALIZING =
        "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
    protected static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    protected static final String FORMAT_LINES_SUFFIX = " lines)";
    protected static final String PARAM_LIMIT = "limit";
    protected static final String PARAM_INSPECTION_ID = "inspection_id";
    protected static final String PARAM_SCOPE = "scope";
    protected static final String PARAM_OFFSET = "offset";

    protected QualityTool(Project project) {
        super(project);
    }

    /**
     * Resolves the 0-based caret column from a symbol name, an explicit column, or defaults to 0.
     * Shared by {@link ApplyActionTool} and {@link GetActionOptionsTool}.
     */
    static int resolveColumn(Document doc, int targetLine,
                             @Nullable String symbol, @Nullable Integer targetCol) {
        if (symbol != null && !symbol.isBlank()) {
            int lineStart = doc.getLineStartOffset(targetLine - 1);
            int lineEnd = doc.getLineEndOffset(targetLine - 1);
            String lineText = doc.getText(new com.intellij.openapi.util.TextRange(lineStart, lineEnd));
            int idx = lineText.indexOf(symbol);
            if (idx >= 0) return idx;
        }
        return targetCol != null ? Math.max(0, targetCol - 1) : 0;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.CODE_QUALITY;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return InspectionResultRenderer.INSTANCE;
    }

    // ── Shared utilities ─────────────────────────────────────

    /**
     * Extracts the display names of all quick-fix actions registered on a highlight.
     * Returns an empty list when no fixes are available (e.g., global inspections, lazy-not-yet-computed).
     *
     * <p>Uses {@code findRegisteredQuickFix} (the non-deprecated iteration API). Returning {@code null}
     * from the predicate on every element causes it to iterate the full list as a side-effect.</p>
     *
     * <p>Wrapped in {@link ProgressManager#runProcess} with an {@link EmptyProgressIndicator} to
     * satisfy {@code runBlockingCancellable} requirements inside TypeScript language-service quick
     * fixes, which must be called from a thread that has a ProgressIndicator or coroutine Job in
     * its context (see {@code TypeScriptLanguageServiceFix.getText}).</p>
     */
    protected static List<String> collectQuickFixNames(HighlightInfo h) {
        List<String> names = new ArrayList<>();
        ProgressManager.getInstance().runProcess(
            () -> h.findRegisteredQuickFix((descriptor, range) -> {
                String text = descriptor.getAction().getText();
                if (!text.isBlank()) names.add(text);
                return null; // return null to continue iterating all registered fixes
            }),
            new EmptyProgressIndicator()
        );
        return names;
    }

    /**
     * Returns the names of all intention actions available at the current caret position
     * in {@code editor}. Filters by {@code isAvailable()} in a read action.
     * Silently skips any action whose {@code isAvailable()} throws.
     *
     * <p>Must be called on the EDT (caret position must already be set by the caller).</p>
     */
    protected List<String> collectIntentionNames(Editor editor, PsiFile psiFile) {
        List<IntentionAction> registered = IntentionManager.getInstance().getAvailableIntentions();
        List<String> names = new ArrayList<>();
        ApplicationManager.getApplication().runReadAction(() -> {
            for (IntentionAction action : registered) {
                try {
                    if (action.isAvailable(project, editor, psiFile)) {
                        String text = action.getText();
                        if (!text.isBlank()) names.add(text);
                    }
                } catch (Exception ignored) {
                    // Poorly implemented intentions may throw — skip them silently
                }
            }
        });
        return names;
    }

    /**
     * Finds the first intention action whose {@link IntentionAction#getText()} equals {@code name},
     * checking availability at the current caret position. Returns {@code null} if not found.
     *
     * <p>Must be called on the EDT (caret position must already be set by the caller).</p>
     */
    @Nullable
    protected IntentionAction findIntentionByName(String name, Editor editor, PsiFile psiFile) {
        List<IntentionAction> registered = IntentionManager.getInstance().getAvailableIntentions();
        for (IntentionAction action : registered) {
            try {
                if (name.equals(action.getText()) && action.isAvailable(project, editor, psiFile)) {
                    return action;
                }
            } catch (Exception ignored) {
                // Skip poorly implemented intentions
            }
        }
        return null;
    }

    /**
     * Returns an existing text editor for {@code vf}, opening it silently if necessary.
     * Must be called on the EDT.
     */
    @Nullable
    protected Editor getOrOpenEditor(VirtualFile vf) {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        for (var fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor te) return te.getEditor();
        }
        var opened = fem.openFile(vf, false);
        for (var fe : opened) {
            if (fe instanceof TextEditor te) return te.getEditor();
        }
        return null;
    }

    /**
     * Returns all daemon highlights whose range overlaps {@code targetLine} (1-based).
     * Safe to call from a read action or from the EDT.
     */
    protected List<HighlightInfo> highlightsOnLine(Document doc, int targetLine) {
        int lineStart = doc.getLineStartOffset(targetLine - 1);
        int lineEnd = doc.getLineEndOffset(targetLine - 1);
        List<HighlightInfo> result = new ArrayList<>();
        DaemonCodeAnalyzerEx.processHighlights(doc, project, null, 0, doc.getTextLength(), h -> {
            if (h.getStartOffset() <= lineEnd && h.getEndOffset() >= lineStart) {
                result.add(h);
            }
            return true;
        });
        return result;
    }

    /**
     * Invokes the action respecting its {@link IntentionAction#startInWriteAction()} contract.
     * Actions that return {@code false} (e.g. refactoring-based fixes) manage their own
     * write lock internally and must NOT be wrapped in a {@link com.intellij.openapi.command.WriteCommandAction},
     * because they start progress/read-actions internally which would deadlock inside a write action.
     *
     * <p><b>Why extracted:</b> Both {@code ApplyActionTool} and {@code GetActionOptionsTool}
     * invoke intentions and must use the same invocation contract.
     */
    protected void invokeRespectingWriteAction(String actionName, IntentionAction action,
                                               Editor editor, PsiFile psiFile) {
        if (action.startInWriteAction()) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                project, actionName, null, () -> action.invoke(project, editor, psiFile));
        } else {
            action.invoke(project, editor, psiFile);
        }
    }

    protected record FilePair(VirtualFile vf, PsiFile psiFile) {
    }

    protected FilePair resolveFilePair(String pathStr, CompletableFuture<String> future) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) {
            future.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            future.complete(ToolUtils.ERROR_CANNOT_PARSE + pathStr);
            return null;
        }
        return new FilePair(vf, psiFile);
    }

    /**
     * Collects files for highlight/compilation-error analysis.
     * If pathStr is given, resolves that single file; otherwise iterates all source content.
     */
    protected Collection<VirtualFile> collectFilesForHighlightAnalysis(
        String pathStr, boolean includeUnindexed, ProjectFileIndex fileIndex,
        CompletableFuture<String> resultFuture) {
        Collection<VirtualFile> files = new ArrayList<>();
        if (pathStr != null && !pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete("Error: File not found: " + pathStr);
                return Collections.emptyList();
            }
            if (includeUnindexed || fileIndex.isInSourceContent(vf)) {
                files.add(vf);
            }
        } else {
            fileIndex.iterateContent(file -> {
                if (!file.isDirectory() && fileIndex.isInSourceContent(file)) {
                    files.add(file);
                }
                return true;
            });
        }
        return files;
    }
}
