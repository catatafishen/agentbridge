package com.github.catatafishen.ideagentforcopilot.psi.tools.testing;

import com.github.catatafishen.ideagentforcopilot.psi.TestTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for testing tools. Provides access to the shared
 * {@link TestTools} for running and listing tests.
 */
public abstract class TestingTool extends Tool {

    protected final TestTools testTools;

    protected TestingTool(Project project, TestTools testTools) {
        super(project);
        this.testTools = testTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.TESTING;
    }
}
