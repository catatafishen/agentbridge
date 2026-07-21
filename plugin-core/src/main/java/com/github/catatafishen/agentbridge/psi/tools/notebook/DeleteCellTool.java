package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Deletes a cell from a notebook.
 */
public final class DeleteCellTool extends NotebookTool {

    public DeleteCellTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_delete_cell";
    }

    @Override
    public @NotNull String displayName() {
        return "Delete Notebook Cell";
    }

    @Override
    public @NotNull String description() {
        return "Delete one cell from a notebook, identified by 'index' (0-based) or 'cell_id'. "
            + "This removes the cell's source and any outputs; it is not undoable through the IDE "
            + "(recover via git if needed). Remaining cells shift up.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Delete a cell from {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER, "0-based cell index (from notebook_list_cells). "
                + "Provide this or 'cell_id'."),
            Param.optional(PARAM_CELL_ID, TYPE_STRING, "Cell id. Provide this or 'index'.")
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
        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        int index = nb.resolveIndex(optionalIndex(args), optionalCellId(args));
        String cellId = NotebookModel.cellId(nb.cellAt(index));
        String type = NotebookModel.cellType(nb.cellAt(index));
        nb.deleteCell(index);

        String rel = relativize(project.getBasePath(), vf.getPath());
        return writeAndAnnotate(vf, nb,
            "Deleted " + type + " cell [" + index + "]" + (cellId != null ? " (#" + cellId + ")" : "")
                + " from " + rel + " — " + nb.cellCount() + " cells remain");
    }
}
