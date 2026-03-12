package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RunInTerminalBuiltInTool extends BuiltInTool {

    public RunInTerminalBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "runInTerminal"; }
    @Override public @NotNull String displayName() { return "Run in Terminal (built-in)"; }
    @Override public @NotNull String description() { return "Run a command in the integrated terminal (Copilot CLI built-in)"; }
    @Override public boolean hasDenyControl() { return true; }
    @Override public @Nullable String permissionTemplate() { return "Run in terminal: {command}"; }
}
