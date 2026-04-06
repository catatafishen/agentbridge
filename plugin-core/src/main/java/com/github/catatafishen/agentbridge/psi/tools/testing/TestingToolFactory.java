package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory producing all individual testing tool instances.
 */
public final class TestingToolFactory {

    private TestingToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new ListTestsTool(project),
            new RunTestsTool(project),
            new GetCoverageTool(project)
        );
    }
}
