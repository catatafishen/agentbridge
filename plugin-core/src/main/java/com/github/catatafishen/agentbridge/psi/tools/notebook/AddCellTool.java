package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Inserts a new cell into a notebook.
 */
public final class AddCellTool extends NotebookTool {

    private static final String PARAM_CELL_TYPE = "cell_type";
    private static final String PARAM_SOURCE = "source";

    public AddCellTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_add_cell";
    }

    @Override
    public @NotNull String displayName() {
        return "Add Notebook Cell";
    }

    @Override
    public @NotNull String description() {
        return "Insert a new cell into a notebook at a 0-based position (omit 'index' to append at the "
            + "end). 'cell_type' is code (default), markdown, or raw. Code cells are created empty and "
            + "unexecuted — run them with notebook_run_cell. Returns the new cell's index and id.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Add a {cell_type} cell to {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.optional(PARAM_CELL_TYPE, TYPE_STRING, "Cell type: code (default), markdown, or raw"),
            Param.optional(PARAM_SOURCE, TYPE_STRING, "Initial source text for the cell (default empty)"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER, "0-based position to insert at; the new cell "
                + "takes this index and existing cells shift down. Omit to append at the end.")
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
        String cellType = args.has(PARAM_CELL_TYPE) && !args.get(PARAM_CELL_TYPE).isJsonNull()
            ? args.get(PARAM_CELL_TYPE).getAsString() : NotebookModel.CODE;
        String source = args.has(PARAM_SOURCE) && !args.get(PARAM_SOURCE).isJsonNull()
            ? args.get(PARAM_SOURCE).getAsString() : "";

        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        int at = optionalIndex(args) != null ? optionalIndex(args) : nb.cellCount();
        JsonObject added = nb.addCell(at, cellType, source);
        int newIndex = nb.indexOfId(NotebookModel.cellId(added));

        String rel = relativize(project.getBasePath(), vf.getPath());
        return writeAndAnnotate(vf, nb,
            "Added " + cellType + " cell at [" + newIndex + "] (#" + NotebookModel.cellId(added) + ") in " + rel);
    }
}
