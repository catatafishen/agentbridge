package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for code quality tools. Provides access to the shared
 * {@link CodeQualityTools} for inspections, highlights, and formatting.
 */
public abstract class QualityTool extends Tool {

    protected final CodeQualityTools qualityTools;

    protected QualityTool(Project project, CodeQualityTools qualityTools) {
        super(project);
        this.qualityTools = qualityTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.CODE_QUALITY;
    }
}
