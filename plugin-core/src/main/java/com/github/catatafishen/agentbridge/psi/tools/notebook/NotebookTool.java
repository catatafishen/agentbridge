package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base for the Jupyter notebook tools. Resolves {@code .ipynb} files and reads/writes their raw
 * nbformat JSON — deliberately bypassing the IntelliJ {@code Document}, which for a notebook is a
 * transformed {@code #%%} script view that omits cell outputs and ids.
 *
 * <p>To stay consistent with an open notebook editor: an unsaved notebook document is flushed to
 * disk before a read (so in-editor edits are not missed), and the file is reloaded after a write
 * (so the editor re-derives its view from the new JSON).
 *
 * <p>Subclasses implement {@link #run(JsonObject)}; this class turns the common notebook exceptions
 * into {@code "Error: "} results so every tool reports failures uniformly.
 */
public abstract class NotebookTool extends FileTool {

    protected static final String NOTEBOOK_EXTENSION = "ipynb";
    protected static final String PARAM_PATH = "path";
    protected static final String PARAM_INDEX = "index";
    protected static final String PARAM_CELL_ID = "cell_id";

    /** Shared param descriptions for single-cell tools — documents the "omit → active cell" default. */
    protected static final String TARGET_INDEX_DESC =
        "0-based cell index (from notebook_list_cells). Omit both 'index' and 'cell_id' to target the "
            + "cell your caret is currently on in the open notebook editor.";
    protected static final String TARGET_CELL_ID_DESC =
        "Cell id (from notebook_list_cells). Omit both 'index' and 'cell_id' to target the cell your "
            + "caret is currently on in the open notebook editor.";

    protected NotebookTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.NOTEBOOK;
    }

    @Override
    public final @NotNull String execute(@NotNull JsonObject args) throws Exception {
        try {
            return run(args);
        } catch (NotebookModel.NotebookException | NotebookJson.NotebookParseException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error: could not access notebook file: " + e.getMessage();
        }
    }

    /**
     * Tool body. May throw {@link IOException} or the notebook exceptions
     * ({@link NotebookModel.NotebookException}, {@link NotebookJson.NotebookParseException}) — all are
     * turned into {@code "Error: "} results by {@link #execute(JsonObject)}.
     */
    protected abstract @NotNull String run(@NotNull JsonObject args) throws IOException;

    // ── file resolution ───────────────────────────────────────────────────────

    /**
     * Resolves the {@code path} argument to an existing {@code .ipynb} {@link VirtualFile}, or
     * returns {@code null}. Must be called off the EDT (it may refresh the VFS).
     */
    protected @Nullable VirtualFile resolveNotebookFile(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        VirtualFile vf = refreshAndFindVirtualFile(path);
        if (vf == null || vf.isDirectory() || !NOTEBOOK_EXTENSION.equalsIgnoreCase(vf.getExtension())) {
            return null;
        }
        return vf;
    }

    protected static @NotNull String notebookNotFound(@Nullable String path) {
        return "Error: notebook not found or not a .ipynb file: " + path
            + ". Pass an absolute or project-relative path to a Jupyter notebook.";
    }

    protected @Nullable String readPath(@NotNull JsonObject args) {
        if (args.has(PARAM_PATH) && !args.get(PARAM_PATH).isJsonNull()) {
            return args.get(PARAM_PATH).getAsString();
        }
        return null;
    }

    // ── read / write raw nbformat JSON ────────────────────────────────────────

    /**
     * Reads the notebook's raw nbformat JSON. Flushes an unsaved notebook document to disk first so
     * unsaved in-editor edits are included. Call off the EDT.
     */
    protected @NotNull String readNotebookText(@NotNull VirtualFile vf) throws IOException {
        flushUnsavedToDisk(vf);
        return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Writes the notebook's raw nbformat JSON and reloads the file so an open notebook editor
     * re-reads it. Call off the EDT.
     */
    protected void writeNotebookText(@NotNull VirtualFile vf, @NotNull String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        AtomicReference<IOException> failure = new AtomicReference<>();
        EdtUtil.invokeAndWait(() -> {
            try {
                WriteAction.run(() -> vf.setBinaryContent(bytes));
            } catch (IOException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        EdtUtil.invokeAndWait(() -> FileDocumentManager.getInstance().reloadFiles(vf));
    }

    /**
     * Serializes the (mutated) model back to the notebook, records the write for the Project View
     * tint, optionally follows the file in the editor, and appends a git status annotation.
     */
    protected @NotNull String writeAndAnnotate(@NotNull VirtualFile vf, @NotNull NotebookModel nb,
                                               @NotNull String message) throws IOException {
        writeNotebookText(vf, nb.toJson());
        FileAccessTracker.recordWrite(project, vf.getPath());
        followFileIfEnabled(project, vf.getPath(), 0, 0, HIGHLIGHT_EDIT,
            agentLabel(project) + " edited notebook");
        return message + getGitFileStatus(project, vf.getPath());
    }

    /**
     * Flushes an unsaved notebook document to disk so a subsequent read sees in-editor edits.
     *
     * <p>The unsaved check runs off the EDT (a background {@link ReadAction}), so for a saved
     * notebook — the common case, as notebooks autosave aggressively — the read never touches the
     * EDT at all and an open modal dialog cannot affect it, matching {@code read_file}.
     *
     * <p>When the document <em>does</em> have unsaved changes and a modal dialog blocks the EDT
     * (e.g. the Settings dialog), the flush cannot run — and skipping it would silently read stale
     * on-disk state, bypassing the user's in-editor edits. So this fails fast with an actionable
     * error instead, pointing the agent at {@code interact_with_modal}.
     */
    private void flushUnsavedToDisk(@NotNull VirtualFile vf) {
        boolean unsaved = ReadAction.compute(() -> {
            FileDocumentManager fdm = FileDocumentManager.getInstance();
            Document doc = fdm.getDocument(vf);
            return doc != null && fdm.isDocumentUnsaved(doc);
        });
        if (!unsaved) {
            return; // nothing to flush — no EDT hop needed
        }
        String modalBlocker = EdtUtil.describeModalBlocker();
        if (!modalBlocker.isEmpty()) {
            throw new NotebookModel.NotebookException(
                "notebook '" + vf.getName() + "' has unsaved in-editor changes that cannot be flushed"
                    + " while a modal dialog blocks the EDT." + modalBlocker
                    + " Use the interact_with_modal tool to respond to the dialog, then retry.");
        }
        try {
            EdtUtil.invokeAndWait(() -> {
                FileDocumentManager fdm = FileDocumentManager.getInstance();
                Document doc = fdm.getDocument(vf);
                if (doc != null && fdm.isDocumentUnsaved(doc)) {
                    WriteAction.run(() -> fdm.saveDocument(doc));
                }
            });
        } catch (RuntimeException e) {
            // A modal appeared mid-flush or the EDT is otherwise unavailable. The unsaved in-editor
            // state cannot be captured, so fail visibly rather than silently read stale disk content.
            throw new NotebookModel.NotebookException(
                "could not flush unsaved notebook changes to disk (" + e.getMessage()
                    + "). Resolve the blocking dialog with interact_with_modal and retry.");
        }
    }

    // ── argument helpers ──────────────────────────────────────────────────────

    protected static @Nullable Integer optionalIndex(@NotNull JsonObject args) {
        return args.has(PARAM_INDEX) && !args.get(PARAM_INDEX).isJsonNull()
            ? args.get(PARAM_INDEX).getAsInt() : null;
    }

    protected static @Nullable String optionalCellId(@NotNull JsonObject args) {
        return args.has(PARAM_CELL_ID) && !args.get(PARAM_CELL_ID).isJsonNull()
            ? args.get(PARAM_CELL_ID).getAsString() : null;
    }

    /**
     * Resolves the target cell for a single-cell tool. When {@code index} or {@code cell_id} is given,
     * behaves like {@link NotebookModel#resolveIndex}. When BOTH are omitted, resolves to the cell the
     * caret is on in the open notebook editor — so an agent can act on "the cell I'm looking at"
     * without naming an index. Call off the EDT.
     */
    protected int resolveTargetIndex(@NotNull NotebookModel nb, @NotNull JsonObject args,
                                     @NotNull VirtualFile vf) {
        Integer index = optionalIndex(args);
        String cellId = optionalCellId(args);
        if (index == null && (cellId == null || cellId.isBlank())) {
            return NotebookExecutor.activeCellIndex(project, vf, nb.cellCount());
        }
        return nb.resolveIndex(index, cellId);
    }
}
