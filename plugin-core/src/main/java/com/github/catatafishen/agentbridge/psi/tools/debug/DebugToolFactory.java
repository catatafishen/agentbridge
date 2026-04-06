package com.github.catatafishen.agentbridge.psi.tools.debug;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.*;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.*;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.*;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory producing all 14 debug tools.
 */
public final class DebugToolFactory {

    private DebugToolFactory() {}

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
                // Breakpoints (work without a debug session)
                new BreakpointListTool(project),
                new BreakpointAddTool(project),
                new BreakpointAddExceptionTool(project),
                new BreakpointUpdateTool(project),
                new BreakpointRemoveTool(project),
                // Session management
                new DebugSessionListTool(project),
                new DebugSessionStopTool(project),
                // Navigation (requires paused session)
                new DebugStepTool(project),
                new DebugRunToLineTool(project),
                // Inspection (requires paused session)
                new DebugSnapshotTool(project),
                new DebugVariableDetailTool(project),
                new DebugInspectFrameTool(project),
                new DebugEvaluateTool(project),
                new DebugReadConsoleTool(project)
        );
    }
}
