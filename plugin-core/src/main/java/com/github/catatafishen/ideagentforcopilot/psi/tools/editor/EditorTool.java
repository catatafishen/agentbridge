package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for editor tools. Provides access to the shared
 * {@link EditorTools} for editor operations.
 */
public abstract class EditorTool extends Tool {

    protected final EditorTools editorTools;

    protected EditorTool(Project project, EditorTools editorTools) {
        super(project);
        this.editorTools = editorTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.EDITOR;
    }
}
