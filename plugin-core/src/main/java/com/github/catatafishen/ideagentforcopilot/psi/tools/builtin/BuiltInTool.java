package com.github.catatafishen.ideagentforcopilot.psi.tools.builtin;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for built-in agent tools (bash, edit, view, etc.).
 * These tools execute inside the Copilot CLI, not the plugin.
 * The plugin only needs their metadata for permission UI and settings.
 */
public abstract class BuiltInTool extends Tool {

    protected BuiltInTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.SHELL;
    }

    @Override
    public boolean isBuiltIn() {
        return true;
    }

    @Override
    public boolean hasExecutionHandler() {
        return false;
    }
}
