package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Lists every cell of a Jupyter notebook as a compact index — one line per cell.
 */
public final class ListCellsTool extends NotebookTool {

    public ListCellsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_list_cells";
    }

    @Override
    public @NotNull String displayName() {
        return "List Notebook Cells";
    }

    @Override
    public @NotNull String description() {
        return "List every cell of a Jupyter notebook (.ipynb) as a compact index: one line per cell "
            + "with its 0-based index, id, type (code/markdown/raw), execution count, an output tag "
            + "(out:text / out:image / ERROR:<name>), and a first-line source preview. "
            + "Use this first to orient in a notebook, then notebook_read_cell for a cell's full "
            + "source and outputs. Reads the on-disk nbformat JSON — the source of truth for outputs.";
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
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook")
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

        StringBuilder sb = new StringBuilder();
        sb.append("Notebook: ").append(relativize(project.getBasePath(), vf.getPath()))
            .append(" — ").append(nb.cellCount()).append(nb.cellCount() == 1 ? " cell" : " cells");
        String kernel = nb.kernelName();
        if (kernel != null) {
            sb.append(" (kernel: ").append(kernel).append(')');
        }
        sb.append('\n');
        if (nb.cellCount() == 0) {
            sb.append("(empty notebook)");
            return sb.toString();
        }
        sb.append("Format: [index] type #id  exec=<count|·>  <output>  | source preview\n");
        for (int i = 0; i < nb.cellCount(); i++) {
            sb.append(NotebookOutputFormatter.listLine(nb.cellAt(i), i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
