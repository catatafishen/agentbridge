package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for project tools. Provides access to the shared
 * {@link ProjectTools} for project info, builds, and structure operations.
 */
public abstract class ProjectTool extends Tool {

    protected final ProjectTools projectTools;

    protected ProjectTool(Project project, ProjectTools projectTools) {
        super(project);
        this.projectTools = projectTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.PROJECT;
    }
}
