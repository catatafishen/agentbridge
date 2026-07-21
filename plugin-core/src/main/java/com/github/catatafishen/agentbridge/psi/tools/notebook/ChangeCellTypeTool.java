package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Converts a cell between code, markdown, and raw.
 */
public final class ChangeCellTypeTool extends NotebookTool {

    private static final String PARAM_CELL_TYPE = "cell_type";

    public ChangeCellTypeTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_change_cell_type";
    }

    @Override
    public @NotNull String displayName() {
        return "Change Notebook Cell Type";
    }

    @Override
    public @NotNull String description() {
        return "Change a cell's type to code, markdown, or raw, keeping its source. Converting a code "
            + "cell to markdown/raw drops its outputs and execution count (those are not valid on "
            + "non-code cells); converting to code adds an empty outputs list. Identify the cell with "
            + "'index' (0-based) or 'cell_id'.";
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
        return "Change a cell to {cell_type} in {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.required(PARAM_CELL_TYPE, TYPE_STRING, "New cell type: code, markdown, or raw"),
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
        String newType = args.get(PARAM_CELL_TYPE).getAsString();

        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        int index = nb.resolveIndex(optionalIndex(args), optionalCellId(args));
        String cellId = NotebookModel.cellId(nb.cellAt(index));
        String oldType = NotebookModel.cellType(nb.cellAt(index));
        nb.changeCellType(index, newType);

        String rel = relativize(project.getBasePath(), vf.getPath());
        return writeAndAnnotate(vf, nb,
            "Changed cell [" + index + "]" + (cellId != null ? " (#" + cellId + ")" : "")
                + " from " + oldType + " to " + newType + " in " + rel);
    }
}
