package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Replaces the source text of a single notebook cell.
 */
public final class EditCellTool extends NotebookTool {

    private static final String PARAM_SOURCE = "source";

    public EditCellTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_edit_cell";
    }

    @Override
    public @NotNull String displayName() {
        return "Edit Notebook Cell";
    }

    @Override
    public @NotNull String description() {
        return "Replace the source text of one notebook cell, identified by 'index' (0-based) or "
            + "'cell_id' (omit both to edit the cell your caret is on). Existing outputs are left as-is "
            + "(they become stale until the cell is re-run "
            + "with notebook_run_cell). Does NOT change the cell type — use notebook_change_cell_type "
            + "for that. Writes the nbformat JSON and reloads the notebook editor.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Edit a cell in {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.required(PARAM_SOURCE, TYPE_STRING, "New full source text for the cell"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER, TARGET_INDEX_DESC),
            Param.optional(PARAM_CELL_ID, TYPE_STRING, TARGET_CELL_ID_DESC)
        );
    }

    @Override
    protected @NotNull String run(@NotNull JsonObject args) throws IOException {
        String path = readPath(args);
        VirtualFile vf = resolveNotebookFile(path);
        if (vf == null) {
            return notebookNotFound(path);
        }
        String guard = guardExternalWrite(vf.getPath());
        if (guard != null) {
            return guard;
        }
        String source = args.get(PARAM_SOURCE).getAsString();

        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        int index = resolveTargetIndex(nb, args, vf);
        String cellId = NotebookModel.cellId(nb.cellAt(index));
        nb.setCellSource(index, source);

        String rel = relativize(project.getBasePath(), vf.getPath());
        return writeAndAnnotate(vf, nb,
            "Edited cell [" + index + "]" + (cellId != null ? " (#" + cellId + ")" : "") + " in " + rel);
    }
}
