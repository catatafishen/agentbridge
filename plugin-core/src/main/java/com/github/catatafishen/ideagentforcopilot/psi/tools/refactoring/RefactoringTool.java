package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.RefactoringTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for refactoring tools. Provides access to the shared
 * {@link RefactoringTools} for rename, extract, inline, and other refactorings.
 */
public abstract class RefactoringTool extends Tool {

    protected final RefactoringTools refactoringTools;

    protected RefactoringTool(Project project, RefactoringTools refactoringTools) {
        super(project);
        this.refactoringTools = refactoringTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.REFACTOR;
    }
}
