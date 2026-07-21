package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Runs a single notebook cell on the kernel and returns its output.
 */
public final class RunCellTool extends NotebookExecutionTool {

    private static final int DEFAULT_TIMEOUT_SEC = 60;

    public RunCellTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_run_cell";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Notebook Cell";
    }

    @Override
    public @NotNull String description() {
        return "Run one code cell on the notebook's Jupyter kernel and return its new output "
            + "(stdout/stderr, result, or error traceback). Identify the cell with 'index' (0-based) "
            + "or 'cell_id'; omit both to run the cell your caret is on. Waits for the cell to finish "
            + "(up to 'timeout_sec', default "
            + DEFAULT_TIMEOUT_SEC + "s) by watching its execution_count. Starts the kernel if needed. "
            + "Requires the Jupyter plugin (DataSpell / PyCharm Professional).";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run a cell in {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER, TARGET_INDEX_DESC),
            Param.optional(PARAM_CELL_ID, TYPE_STRING, TARGET_CELL_ID_DESC),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER, "Seconds to wait for the cell to finish (default "
                + DEFAULT_TIMEOUT_SEC + ", max " + MAX_TIMEOUT_SEC + ").")
        );
    }

    @Override
    protected @NotNull String run(@NotNull JsonObject args) throws IOException {
        String path = readPath(args);
        VirtualFile vf = resolveNotebookFile(path);
        if (vf == null) {
            return notebookNotFound(path);
        }
        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        int index = resolveTargetIndex(nb, args, vf);
        JsonObject cell = nb.cellAt(index);
        String type = NotebookModel.cellType(cell);
        if (!NotebookModel.CODE.equals(type)) {
            return "Error: cell [" + index + "] is a " + type + " cell, not code — only code cells run on "
                + "the kernel. Use notebook_change_cell_type to convert it first.";
        }
        Integer before = NotebookModel.executionCount(cell);

        String invokeError = executor.invokeAction(vf, NotebookExecutor.ACTION_RUN_CELL, index, nb.cellCount());
        if (invokeError != null) {
            return invokeError;
        }
        return pollCellCompletion(vf, index, before, timeoutSec(args, DEFAULT_TIMEOUT_SEC));
    }
}
