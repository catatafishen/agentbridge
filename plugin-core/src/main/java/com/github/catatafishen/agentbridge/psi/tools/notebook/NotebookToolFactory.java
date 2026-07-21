package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for the Jupyter notebook tools.
 *
 * <p>Read and edit tools operate on the {@code .ipynb} nbformat JSON and are always available.
 * Execution and kernel tools drive the IDE's Jupyter run actions and register only when the
 * {@code intellij.jupyter} plugin is present (DataSpell, PyCharm Professional, IntelliJ IDEA
 * Ultimate with the Python plugin) — they degrade gracefully in other IDEs.
 */
public final class NotebookToolFactory {

    private NotebookToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        List<Tool> tools = new ArrayList<>(List.of(
            new ListCellsTool(project),
            new ReadCellTool(project),
            new EditCellTool(project),
            new AddCellTool(project),
            new DeleteCellTool(project),
            new MoveCellTool(project),
            new ChangeCellTypeTool(project)
        ));
        if (NotebookExecutor.jupyterAvailable()) {
            tools.add(new RunCellTool(project));
            tools.add(new RunAllCellsTool(project));
            tools.add(new RestartKernelTool(project));
            tools.add(new InterruptKernelTool(project));
            tools.add(new KernelStatusTool(project));
        }
        return tools;
    }
}
