package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Moves a cell to a new position within a notebook.
 */
public final class MoveCellTool extends NotebookTool {

    private static final String PARAM_TO_INDEX = "to_index";

    public MoveCellTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_move_cell";
    }

    @Override
    public @NotNull String displayName() {
        return "Move Notebook Cell";
    }

    @Override
    public @NotNull String description() {
        return "Move a cell to a new position. Identify the cell with 'index' (0-based) or 'cell_id', "
            + "and give the destination 'to_index' (0-based) where the cell should end up.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Move a cell in {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.required(PARAM_TO_INDEX, TYPE_INTEGER, "0-based destination index the cell should end up at"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER, "0-based index of the cell to move. "
                + "Provide this or 'cell_id'."),
            Param.optional(PARAM_CELL_ID, TYPE_STRING, "Id of the cell to move. Provide this or 'index'.")
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
        int from = nb.resolveIndex(optionalIndex(args), optionalCellId(args));
        int to = args.get(PARAM_TO_INDEX).getAsInt();
        String cellId = NotebookModel.cellId(nb.cellAt(from));
        nb.moveCell(from, to);

        String rel = relativize(project.getBasePath(), vf.getPath());
        return writeAndAnnotate(vf, nb,
            "Moved cell" + (cellId != null ? " #" + cellId : "") + " from [" + from + "] to [" + to + "] in " + rel);
    }
}
