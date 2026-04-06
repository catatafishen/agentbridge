package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory that creates all infrastructure tool instances.
 */
public final class InfrastructureToolFactory {

    private InfrastructureToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new AskUserTool(project),
            new HttpRequestTool(project),
            new RunCommandTool(project),
            new ReadIdeLogTool(project),
            new GetNotificationsTool(project),
            new ListRunTabsTool(project),
            new ReadRunOutputTool(project),
            new ReadBuildOutputTool(project),
            new InteractWithModalTool(project)
        );
    }
}
