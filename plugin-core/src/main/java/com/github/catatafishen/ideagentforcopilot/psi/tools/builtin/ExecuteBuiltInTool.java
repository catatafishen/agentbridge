package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ExecuteBuiltInTool extends BuiltInTool {

    public ExecuteBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "execute"; }
    @Override public @NotNull String displayName() { return "Execute (built-in)"; }
    @Override public @NotNull String description() { return "Execute a shell command (built-in)"; }
    @Override public boolean hasDenyControl() { return true; }
    @Override public @Nullable String permissionTemplate() { return "Execute: {command}"; }
}
