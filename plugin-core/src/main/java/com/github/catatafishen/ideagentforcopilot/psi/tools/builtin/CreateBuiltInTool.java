package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CreateBuiltInTool extends BuiltInTool {

    public CreateBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "create"; }
    @Override public @NotNull String displayName() { return "Create File (built-in)"; }
    @Override public @NotNull String description() { return "Create a new file (Copilot CLI built-in) -- use create_file for IDE-aware creation"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.FILE; }
    @Override public boolean hasDenyControl() { return true; }
    @Override public boolean supportsPathSubPermissions() { return true; }
    @Override public @Nullable String permissionTemplate() { return "Create {path}"; }
}
