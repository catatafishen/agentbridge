package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.TerminalTools;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for terminal tools. Provides access to the shared
 * {@link TerminalTools} for terminal operations.
 */
public abstract class TerminalTool extends Tool {

    protected final TerminalTools terminalTools;

    protected TerminalTool(Project project, TerminalTools terminalTools) {
        super(project);
        this.terminalTools = terminalTools;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.TERMINAL;
    }
}
