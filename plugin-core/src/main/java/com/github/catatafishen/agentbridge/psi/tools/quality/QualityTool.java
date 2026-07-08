package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.ui.renderers.InspectionResultRenderer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
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

    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(QualityTool.class);

    protected static final String ERROR_IDE_INITIALIZING =
        "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
    protected static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    protected static final String FORMAT_LINES_SUFFIX = " lines)";
    protected static final String PARAM_MAX_RESULTS = "max_results";
    protected static final String PARAM_INSPECTION_ID = "inspection_id";
    protected static final String PARAM_SCOPE = "scope";
    protected static final String PARAM_OFFSET = "offset";

    /**
     * Pairs an open {@link Editor} with context needed for lifecycle management.
     * Returned by {@link #openEditorForTool}; pass to {@link #releaseToolEditor} when done.
     */
    protected record ToolEditor(@NotNull Editor editor, @NotNull VirtualFile file, boolean wasAlreadyOpen) {
    }

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
     * in {@code editor}.
     * Skips any action whose {@code isAvailable()} throws a non-cancellation exception, logging at WARN level.
     * {@link ProcessCanceledException} is <em>not</em> swallowed — it is rethrown so the platform
     * can cancel the EDT operation cleanly. CLion Nova C++ intentions may throw this when their
     * analysis engine is not yet ready.
     *
     * <p>Must be called on the EDT (caret position must already be set by the caller).
     * <b>Do NOT wrap the call body in {@code runReadAction()}</b>: this method runs on the EDT,
     * which already holds write-intent access. Wrapping in a plain ReadAction downgrades to
     * read-only context, which prevents intentions (e.g., Kotlin K2) from internally acquiring
     * {@code WriteIntentReadAction}, causing an {@code IllegalStateException} that crashes the EDT.</p>
     */
    protected List<String> collectIntentionNames(Editor editor, PsiFile psiFile) {
        List<IntentionAction> registered = IntentionManager.getInstance().getAvailableIntentions();
        List<String> names = new ArrayList<>();
        for (IntentionAction action : registered) {
            try {
                if (action.isAvailable(project, editor, psiFile)) {
                    String text = action.getText();
                    if (!text.isBlank()) names.add(text);
                }
            } catch (ProcessCanceledException e) {
                // Platform cancellation — must propagate so IntelliJ can cancel the EDT operation cleanly.
                // CLion Nova C++ intentions may throw this when their analysis engine is not yet ready.
                throw e;
            } catch (Exception e) {
                LOG.warn("Intention " + action.getClass().getSimpleName()
                    + " threw during isAvailable() — skipping", e);
            }
        }
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
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception ignored) {
                // Skip poorly implemented intentions
            }
        }
        return null;
    }

    /**
     * Resolves a VirtualFile by path, falling back to
     * {@link ToolUtils#findFileInProjectContent} when {@link #resolveVirtualFile} returns null.
     * Use this instead of the plain {@code resolveVirtualFile()} in all quality tools so that
     * temp:/// paths (in-memory test fixtures) and files not yet synced to LocalFileSystem are
     * handled consistently — and so that listing, applying, and inspecting actions all use the
     * same resolver.
     */
    protected @Nullable VirtualFile resolveVirtualFileWithFallback(String pathStr) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) vf = ToolUtils.findFileInProjectContent(project, pathStr);
        if (vf == null) {
            // Last resort: in-memory temp:/// VFS used by test fixtures (heavy test projects)
            String normalized = pathStr.replace('\\', '/');
            vf = VirtualFileManager.getInstance().findFileByUrl("temp://" + normalized);
        }
        return vf;
    }

    /**
     * Opens {@code vf} silently in a text editor and returns a {@link ToolEditor} that
     * records whether the file was already open. <em>Must be called on the EDT.</em>
     *
     * <p>Always call {@link #releaseToolEditor} when done so that files opened purely for
     * tool use are closed when the "Follow Agent Files" setting is disabled.
     */
    @Nullable
    protected ToolEditor openEditorForTool(@NotNull VirtualFile vf) {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        for (var fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor te) return new ToolEditor(te.getEditor(), vf, true);
        }
        var opened = fem.openFile(vf, false);
        for (var fe : opened) {
            if (fe instanceof TextEditor te) return new ToolEditor(te.getEditor(), vf, false);
        }
        return null;
    }

    /**
     * Closes the file associated with {@code te} if it was opened by {@link #openEditorForTool}
     * and the "Follow Agent Files" setting is disabled. No-op if {@code te} is {@code null} or
     * if the file was already open before the tool ran.
     *
     * <p><b>Why this matters:</b> Action tools ({@code apply_action}, {@code get_available_actions},
     * {@code get_action_options}) require a live {@link Editor} to position a caret and evaluate
     * intention availability — there is no headless alternative in the IntelliJ platform. Without
     * this release step, every tool call on a closed file permanently adds a new editor tab,
     * ignoring the "Follow Agent Files" preference.
     *
     * <p>Must be called on the EDT (same thread that called {@link #openEditorForTool}).
     */
    protected void releaseToolEditor(@Nullable ToolEditor te) {
        if (te == null || te.wasAlreadyOpen()) return;
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            FileEditorManager.getInstance(project).closeFile(te.file());
        }
    }

    /**
     * Returns {@code null} if this tool may open {@code vf} in the editor, or an error string
     * explaining why it cannot. Opening is blocked when Follow Agent Files is off AND temporary
     * file opens are disabled — the file must already be open for the tool to function.
     */
    @Nullable
    protected String checkEditorAccess(@NotNull VirtualFile vf) {
        if (FileEditorManager.getInstance(project).isFileOpen(vf)) return null;
        if (ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return null;
        if (ToolLayerSettings.getInstance(project).getAllowTransientFileOpens()) return null;
        return "Error: This tool needs to temporarily open the file in the IDE editor to collect " +
            "code analysis data, but this is disabled when Follow Agent Files is off. " +
            "Re-enable 'Open files temporarily for code quality data' in AgentBridge → UI/UX " +
            "settings, or enable Follow Agent Files.";
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
     * However, they still need a command scope for undo tracking — without it, any document
     * modification triggers "Must not change document outside command" assertions.
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
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project, () -> action.invoke(project, editor, psiFile), actionName, null);
        }
    }

    protected record FilePair(VirtualFile vf, PsiFile psiFile) {
    }

    /**
     * Result type returned by {@link #loadFileContext}.
     * Either an error string ({@link Err}) or the resolved VF/document/PSI triple ({@link Ok}).
     */
    protected sealed interface FileContextResult permits FileContextResult.Ok, FileContextResult.Err {
        record Ok(@NotNull VirtualFile vf, @NotNull Document doc, @NotNull PsiFile psiFile)
            implements FileContextResult {
        }

        record Err(@NotNull String error) implements FileContextResult {
        }
    }

    /**
     * Validates {@code pathStr} and {@code targetLine}, resolving the VirtualFile, Document, and
     * PsiFile in one step. Returns {@link FileContextResult.Err} with a ready-to-return error
     * string on any failure, or {@link FileContextResult.Ok} with the resolved triple on success.
     *
     * <p>Extracted from {@link ApplyActionTool} and {@link GetActionOptionsTool} to eliminate
     * duplicated guard sequences and reduce cognitive complexity.
     */
    protected FileContextResult loadFileContext(String pathStr, int targetLine) {
        VirtualFile vf = resolveVirtualFileWithFallback(pathStr);
        if (vf == null)
            return new FileContextResult.Err(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return new FileContextResult.Err("Error: Cannot get document for: " + pathStr);

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return new FileContextResult.Err(
                "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + FORMAT_LINES_SUFFIX);
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null)
            return new FileContextResult.Err(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr);

        return new FileContextResult.Ok(vf, doc, psiFile);
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
            VirtualFile vf = resolveVirtualFileWithFallback(pathStr);
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
