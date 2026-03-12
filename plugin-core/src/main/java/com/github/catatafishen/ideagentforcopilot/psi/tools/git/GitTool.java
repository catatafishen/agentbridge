package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.psi.tools.ToolCategory;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for git tools. Provides access to the shared
 * {@link GitToolHandler} for running git commands.
 */
public abstract class GitTool extends Tool {

    protected final GitToolHandler git;

    protected GitTool(Project project, GitToolHandler git) {
        super(project);
        this.git = git;
    }

    @Override
    public @NotNull ToolCategory toolCategory() {
        return ToolCategory.GIT;
    }
}
