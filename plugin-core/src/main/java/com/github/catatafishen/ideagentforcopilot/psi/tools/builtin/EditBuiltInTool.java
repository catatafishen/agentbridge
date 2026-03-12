package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EditBuiltInTool extends BuiltInTool {

    public EditBuiltInTool(Project project) {
        super(project);
    }

    @Override public @NotNull String id() { return "edit"; }
    @Override public @NotNull String displayName() { return "Edit File (built-in)"; }
    @Override public @NotNull String description() { return "Edit a file in place (built-in) -- use edit_text or replace_symbol_body for IDE-aware editing"; }
    @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.FILE; }
    @Override public boolean hasDenyControl() { return true; }
    @Override public boolean supportsPathSubPermissions() { return true; }
    @Override public @Nullable String permissionTemplate() { return "Edit {path}"; }
}
