package com.github.catatafishen.ideagentforcopilot.psi.tools.editing;

import com.github.catatafishen.ideagentforcopilot.psi.SymbolEditingTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for symbol editing tools. Provides access to the shared
 * {@link SymbolEditingTools} for PSI-aware structural edits.
 */
public abstract class EditingTool extends Tool {

    protected final SymbolEditingTools editingTools;

    protected EditingTool(Project project, SymbolEditingTools editingTools) {
        super(project);
        this.editingTools = editingTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.REFACTOR;
    }
}
