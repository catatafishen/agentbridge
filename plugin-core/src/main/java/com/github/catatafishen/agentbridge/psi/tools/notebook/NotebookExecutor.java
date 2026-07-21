package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bridges notebook execution to the IntelliJ Jupyter plugin using only public platform APIs.
 *
 * <p>There is no public API to run a cell and await its result — the kernel engine is closed-source.
 * So this triggers execution by invoking the confirmed action ids through {@code ActionManager} with
 * the caret positioned in the target cell, exactly as the IDE's own gutter "Run" button does. The
 * caller then polls the {@code .ipynb} JSON for the cell's {@code execution_count} to detect
 * completion — a version-independent signal.
 *
 * <p>Gated on the {@code intellij.jupyter} plugin; execution tools register only when it is present.
 */
public final class NotebookExecutor {

    private static final Logger LOG = Logger.getInstance(NotebookExecutor.class);

    /** Pro-only Jupyter plugin; provides the notebook editor, kernel, and run actions. */
    public static final String JUPYTER_PLUGIN_ID = "intellij.jupyter";

    public static final String ACTION_RUN_CELL = "NotebookRunCellAction";
    public static final String ACTION_RUN_ALL = "NotebookRunAllAction";
    public static final String ACTION_INTERRUPT = "JupyterInterruptKernelAction";

    private static final String PLACE = "AgentBridgeNotebook";
    private static final long INVOKE_TIMEOUT_SEC = 20;

    private final Project project;

    public NotebookExecutor(Project project) {
        this.project = project;
    }

    public static boolean jupyterAvailable() {
        return PlatformApiCompat.isPluginInstalled(JUPYTER_PLUGIN_ID);
    }

    /**
     * Invokes a notebook action, optionally positioning the caret in cell {@code cellIndex} first.
     * Blocks until the action has been dispatched (not until the kernel finishes). Returns
     * {@code null} on success, or an actionable error string.
     *
     * @param cellIndex 0-based cell to run, or {@code null} for whole-notebook/kernel actions
     * @param cellCount total cell count, used to sanity-check the editor's cell boundaries
     */
    public @Nullable String invokeAction(VirtualFile vf, String actionId,
                                         @Nullable Integer cellIndex, int cellCount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                future.complete(invokeOnEdt(vf, actionId, cellIndex, cellCount));
            } catch (Throwable t) { // NOSONAR — surface any EDT failure as a tool error, never crash the dispatcher
                LOG.warn("Notebook action '" + actionId + "' failed on EDT", t);
                future.complete("Error: invoking '" + actionId + "' failed: " + rootMessage(t));
            }
        });
        try {
            return future.get(INVOKE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while invoking '" + actionId + "'";
        } catch (Exception e) {
            return "Error: timed out invoking '" + actionId + "' (the notebook editor may be busy)";
        }
    }

    /**
     * Resolves the restart-kernel action id, whose exact name is not confirmed in open source.
     * Tries the likely names, then scans Jupyter/Notebook action ids for one mentioning
     * "restart" and "kernel". Returns {@code null} if none is found.
     */
    public @Nullable String resolveRestartActionId() {
        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            ActionManager am = ActionManager.getInstance();
            for (String candidate : new String[]{"JupyterRestartKernelAction", "RestartKernelAction",
                "NotebookRestartKernelAction"}) {
                if (am.getAction(candidate) != null) {
                    future.complete(candidate);
                    return;
                }
            }
            for (String prefix : new String[]{"Jupyter", "Notebook"}) {
                for (String id : am.getActionIdList(prefix)) {
                    String low = id.toLowerCase(Locale.ROOT);
                    if (low.contains("restart") && low.contains("kernel")) {
                        future.complete(id);
                        return;
                    }
                }
            }
            future.complete(null);
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── EDT work ──────────────────────────────────────────────────────────────

    private @Nullable String invokeOnEdt(VirtualFile vf, String actionId,
                                         @Nullable Integer cellIndex, int cellCount) {
        FileEditorManager.getInstance(project).openFile(vf, false);
        Editor editor = findEditor(vf);
        if (editor == null) {
            return "Error: could not obtain a text editor for '" + vf.getName() + "'. "
                + "Open the notebook in the IDE and ensure the Jupyter plugin is active.";
        }
        if (cellIndex != null) {
            String positionError = positionCaret(editor, cellIndex, cellCount);
            if (positionError != null) {
                return positionError;
            }
        }
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) {
            return "Error: action '" + actionId + "' is not available in this IDE "
                + "(the Jupyter plugin may be missing or a different version).";
        }
        DataContext ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.HOST_EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, vf)
            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, editor.getContentComponent())
            .build();
        ActionUtil.invokeAction(action, ctx, PLACE, null, null);
        return null;
    }

    private @Nullable String positionCaret(Editor editor, int cellIndex, int cellCount) {
        int[] markers = cellMarkerOffsets(editor.getDocument().getCharsSequence());
        if (markers.length != cellCount) {
            return "Error: could not reliably map cell " + cellIndex + " to the notebook editor "
                + "(found " + markers.length + " cell markers for " + cellCount + " cells). "
                + "A cell's source may contain a line starting with '#%%'. Run the cell from the IDE, "
                + "or use notebook_run_all.";
        }
        if (cellIndex < 0 || cellIndex >= markers.length) {
            return "Error: cell index " + cellIndex + " is out of range for the notebook editor.";
        }
        editor.getCaretModel().moveToOffset(markers[cellIndex]);
        return null;
    }

    private @Nullable Editor findEditor(VirtualFile vf) {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        FileDocumentManager fdm = FileDocumentManager.getInstance();

        Editor selected = fem.getSelectedTextEditor();
        if (selected != null && vf.equals(fdm.getFile(selected.getDocument()))) {
            return selected;
        }
        for (FileEditor fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor textEditor) {
                return textEditor.getEditor();
            }
        }
        Document doc = fdm.getDocument(vf);
        if (doc != null) {
            Editor[] editors = EditorFactory.getInstance().getEditors(doc, project);
            if (editors.length > 0) {
                return editors[0];
            }
        }
        return null;
    }

    // ── pure helper (unit-tested) ─────────────────────────────────────────────

    /**
     * Returns the document offsets where each notebook cell begins in the IDE's {@code #%%} script
     * view — every cell (code / markdown / raw) is introduced by a line starting with {@code #%%}.
     * The Nth offset is the start of the Nth cell, matching the nbformat cell order.
     */
    static int[] cellMarkerOffsets(CharSequence text) {
        List<Integer> offsets = new ArrayList<>();
        int length = text.length();
        int lineStart = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || text.charAt(i) == '\n') {
                if (isCellMarker(text, lineStart, i)) {
                    offsets.add(lineStart);
                }
                lineStart = i + 1;
            }
        }
        int[] result = new int[offsets.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = offsets.get(i);
        }
        return result;
    }

    private static boolean isCellMarker(CharSequence text, int start, int end) {
        return end - start >= 3
            && text.charAt(start) == '#'
            && text.charAt(start + 1) == '%'
            && text.charAt(start + 2) == '%';
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
