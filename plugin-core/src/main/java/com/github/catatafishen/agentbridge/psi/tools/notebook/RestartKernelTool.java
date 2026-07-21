package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Restarts the Jupyter kernel backing a notebook.
 */
public final class RestartKernelTool extends NotebookExecutionTool {

    public RestartKernelTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_restart_kernel";
    }

    @Override
    public @NotNull String displayName() {
        return "Restart Notebook Kernel";
    }

    @Override
    public @NotNull String description() {
        return "Restart the Jupyter kernel for a notebook, clearing all in-memory variables and imports. "
            + "Existing cell outputs stay in the file until cells are re-run; the next run starts "
            + "execution_count at 1. If the IDE shows a 'Restart kernel?' confirmation, confirm it there. "
            + "Requires the Jupyter plugin.";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Restart the kernel for {path}";
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
        String actionId = executor.resolveRestartActionId();
        if (actionId == null) {
            return "Error: no restart-kernel action was found in this IDE build. Restart the kernel from "
                + "the IDE (Run/Kernel menu), or use notebook_interrupt_kernel to stop a running cell.";
        }
        String invokeError = executor.invokeAction(vf, actionId, null, 0);
        if (invokeError != null) {
            return invokeError;
        }
        return "Requested kernel restart for " + relativize(project.getBasePath(), vf.getPath())
            + " (action: " + actionId + "). Re-run cells to repopulate kernel state.";
    }
}
