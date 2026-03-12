package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory that creates all built-in agent tool definitions.
 */
public final class BuiltInToolFactory {

    private BuiltInToolFactory() {
    }

    @NotNull
    public static List<Tool> create(@NotNull Project project) {
        return List.of(
                new ViewBuiltInTool(project),
                new ReadBuiltInTool(project),
                new GrepBuiltInTool(project),
                new GlobBuiltInTool(project),
                new ListBuiltInTool(project),
                new BashBuiltInTool(project),
                new EditBuiltInTool(project),
                new WriteBuiltInTool(project),
                new CreateBuiltInTool(project),
                new ExecuteBuiltInTool(project),
                new RunInTerminalBuiltInTool(project)
        );
    }
}
