package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reports the inferred kernel/run state of a notebook.
 *
 * <p>The IDE exposes no public API for a kernel's live busy/idle/dead state, so this reports what
 * can be derived from the notebook file and editor: whether the Jupyter plugin is present, whether
 * the notebook is open, and how many code cells have run (with the highest execution count).
 */
public final class KernelStatusTool extends NotebookTool {

    public KernelStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_kernel_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Notebook Kernel Status";
    }

    @Override
    public @NotNull String description() {
        return "Report what is known about a notebook's kernel and execution progress: whether Jupyter "
            + "support is available, whether the notebook is open in the IDE, the kernel name, how many "
            + "code cells have run, and the highest execution_count. Note: the live busy/idle/dead state "
            + "of the kernel is not exposed by public IDE APIs, so this is inferred from the notebook file.";
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
        VirtualFile vf = resolveNotebookFile(path);
        if (vf == null) {
            return notebookNotFound(path);
        }
        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));

        int codeCells = 0;
        int executed = 0;
        Integer maxExec = null;
        for (int i = 0; i < nb.cellCount(); i++) {
            JsonObject cell = nb.cellAt(i);
            if (!NotebookModel.CODE.equals(NotebookModel.cellType(cell))) {
                continue;
            }
            codeCells++;
            Integer exec = NotebookModel.executionCount(cell);
            if (exec != null) {
                executed++;
                if (maxExec == null || exec > maxExec) {
                    maxExec = exec;
                }
            }
        }

        String kernel = nb.kernelName();
        StringBuilder sb = new StringBuilder();
        sb.append("Kernel status for ").append(relativize(project.getBasePath(), vf.getPath())).append(":\n");
        sb.append("- Jupyter support: ").append(NotebookExecutor.jupyterAvailable() ? "available" : "not available")
            .append('\n');
        sb.append("- Notebook open in IDE: ").append(isFileOpen(vf) ? "yes" : "no").append('\n');
        sb.append("- Kernel: ").append(kernel != null ? kernel : "<unspecified>").append('\n');
        sb.append("- Code cells: ").append(codeCells).append(" (executed: ").append(executed);
        sb.append(", highest execution_count: ").append(maxExec != null ? maxExec : "none").append(")\n");
        sb.append("Note: live kernel busy/idle/dead state is not exposed by public IDE APIs — "
            + "this is inferred from the notebook file.");
        return sb.toString();
    }

    private boolean isFileOpen(@NotNull VirtualFile vf) {
        AtomicBoolean open = new AtomicBoolean(false);
        EdtUtil.invokeAndWait(() -> open.set(FileEditorManager.getInstance(project).isFileOpen(vf)));
        return open.get();
    }
}
