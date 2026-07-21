package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Returns the full source and rendered outputs of a single notebook cell.
 */
public final class ReadCellTool extends NotebookTool {

    private static final String PARAM_MAX_OUTPUT_CHARS = "max_output_chars";

    public ReadCellTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_read_cell";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Notebook Cell";
    }

    @Override
    public @NotNull String description() {
        return "Read one notebook cell's full source and its rendered outputs (stdout/stderr streams, "
            + "execute_result and display_data text, and error tracebacks). Rich outputs such as images "
            + "are summarized with their type and size rather than dumped. Identify the cell by 'index' "
            + "(0-based, from notebook_list_cells) or 'cell_id'; omit both to read the cell your caret is "
            + "currently on. Reads the on-disk nbformat JSON.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER, TARGET_INDEX_DESC),
            Param.optional(PARAM_CELL_ID, TYPE_STRING, TARGET_CELL_ID_DESC),
            Param.optional(PARAM_MAX_OUTPUT_CHARS, TYPE_INTEGER,
                "Maximum characters to show per output before truncation (default "
                    + NotebookOutputFormatter.DEFAULT_MAX_OUTPUT_CHARS + ").")
        );
    }

    @Override
    protected @NotNull String run(@NotNull JsonObject args) throws IOException {
        String path = readPath(args);
        var vf = resolveNotebookFile(path);
        if (vf == null) {
            return notebookNotFound(path);
        }
        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        FileAccessTracker.recordRead(project, vf.getPath());

        int index = resolveTargetIndex(nb, args, vf);
        int maxChars = args.has(PARAM_MAX_OUTPUT_CHARS) && !args.get(PARAM_MAX_OUTPUT_CHARS).isJsonNull()
            ? Math.max(0, args.get(PARAM_MAX_OUTPUT_CHARS).getAsInt())
            : NotebookOutputFormatter.DEFAULT_MAX_OUTPUT_CHARS;

        return NotebookOutputFormatter.cellDetail(nb.cellAt(index), index, nb.cellCount(), maxChars);
    }
}
