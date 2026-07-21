package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
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

    private void flushUnsavedToDisk(@NotNull VirtualFile vf) {
        EdtUtil.invokeAndWait(() -> {
            FileDocumentManager fdm = FileDocumentManager.getInstance();
            Document doc = fdm.getDocument(vf);
            if (doc != null && fdm.isDocumentUnsaved(doc)) {
                WriteAction.run(() -> fdm.saveDocument(doc));
            }
        });
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
}
