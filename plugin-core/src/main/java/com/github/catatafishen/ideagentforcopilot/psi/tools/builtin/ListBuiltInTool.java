package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class ListBuiltInTool extends BuiltInTool {

    public ListBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "list"; }
    @Override public @NotNull String displayName() { return "List Files (built-in)"; }
    @Override public @NotNull String description() { return "List files and directories (OpenCode built-in)"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.SEARCH; }
    @Override public boolean supportsPathSubPermissions() { return true; }
}
