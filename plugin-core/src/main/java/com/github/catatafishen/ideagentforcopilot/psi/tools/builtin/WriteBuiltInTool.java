package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WriteBuiltInTool extends BuiltInTool {

    public WriteBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "write"; }
    @Override public @NotNull String displayName() { return "Write File (built-in)"; }
    @Override public @NotNull String description() { return "Create or overwrite a file (OpenCode built-in) -- use intellij_write_file for IDE-aware writing"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.FILE; }
    @Override public boolean hasDenyControl() { return true; }
    @Override public boolean supportsPathSubPermissions() { return true; }
    @Override public @Nullable String permissionTemplate() { return "Write {path}"; }
}
