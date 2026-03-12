package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class ReadBuiltInTool extends BuiltInTool {

    public ReadBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "read"; }
    @Override public @NotNull String displayName() { return "Read File (built-in)"; }
    @Override public @NotNull String description() { return "Read file contents from disk (built-in)"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.FILE; }
    @Override public boolean supportsPathSubPermissions() { return true; }
}
