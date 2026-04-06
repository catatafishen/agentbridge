package com.github.catatafishen.agentbridge.psi.tools.editing;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory producing all individual symbol editing tool instances.
 * Called from {@link com.github.catatafishen.agentbridge.psi.PsiBridgeService}
 * during initialization.
 */
public final class EditingToolFactory {

    private EditingToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new ReplaceSymbolBodyTool(project),
            new InsertBeforeSymbolTool(project),
            new InsertAfterSymbolTool(project)
        );
    }
}
