package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Interrupts the currently running cell on a notebook's kernel.
 */
public final class InterruptKernelTool extends NotebookExecutionTool {

    public InterruptKernelTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_interrupt_kernel";
    }

    @Override
    public @NotNull String displayName() {
        return "Interrupt Notebook Kernel";
    }

    @Override
    public @NotNull String description() {
        return "Interrupt the Jupyter kernel to stop the cell that is currently running (equivalent to "
            + "sending KeyboardInterrupt). Use when a cell is stuck or looping. Kernel state and other "
            + "outputs are preserved. Requires the Jupyter plugin.";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Interrupt the kernel for {path}";
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
        String invokeError = executor.invokeAction(vf, NotebookExecutor.ACTION_INTERRUPT, null, 0);
        if (invokeError != null) {
            return invokeError;
        }
        return "Sent interrupt to the kernel for " + relativize(project.getBasePath(), vf.getPath())
            + ". A running cell should stop shortly (it may report a KeyboardInterrupt in its output).";
    }
}
