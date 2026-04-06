package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory producing all individual file tool instances.
 * Called from {@link com.github.catatafishen.agentbridge.psi.PsiBridgeService}
 * during initialization.
 */
public final class FileToolFactory {

    private FileToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new ReadFileTool(project),
            new WriteFileTool(project),
            new EditTextTool(project),
            new CreateFileTool(project),
            new DeleteFileTool(project),
            new RenameFileTool(project),
            new MoveFileTool(project),
            new UndoTool(project),
            new RedoTool(project),
            new ReloadFromDiskTool(project)
        );
    }
}
