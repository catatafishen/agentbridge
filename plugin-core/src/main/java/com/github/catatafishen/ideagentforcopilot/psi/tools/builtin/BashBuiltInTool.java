package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BashBuiltInTool extends BuiltInTool {

    public BashBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "bash"; }
    @Override public @NotNull String displayName() { return "Bash Shell (built-in)"; }
    @Override public @NotNull String description() { return "Run arbitrary shell commands -- use run_command instead for safer, paginated execution"; }
    @Override public boolean hasDenyControl() { return true; }
    @Override public @Nullable String permissionTemplate() { return "Run: {cmd}"; }
}
