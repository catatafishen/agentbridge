package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class GrepBuiltInTool extends BuiltInTool {

    public GrepBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "grep"; }
    @Override public @NotNull String displayName() { return "Grep Search (built-in)"; }
    @Override public @NotNull String description() { return "Search file contents with regular expressions (built-in)"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.SEARCH; }
}
