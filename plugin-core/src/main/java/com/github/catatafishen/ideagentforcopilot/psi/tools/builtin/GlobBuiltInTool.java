package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class GlobBuiltInTool extends BuiltInTool {

    public GlobBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "glob"; }
    @Override public @NotNull String displayName() { return "Glob Find (built-in)"; }
    @Override public @NotNull String description() { return "Find files by name pattern (built-in)"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.SEARCH; }
}
