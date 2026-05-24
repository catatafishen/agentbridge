package com.github.catatafishen.agentbridge.psi.tools.debug;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointListTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointManageTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugEvaluateTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugInspectFrameTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugReadConsoleTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugSnapshotTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugVariableDetailTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugRunToLineTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugStepTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionListTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionStartTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionStopTool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory producing all 12 debug tools.
 */
public final class DebugToolFactory {

    private DebugToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            // Breakpoints (work without a debug session)
            new BreakpointListTool(project),
            new BreakpointManageTool(project),
            // Session management
            new DebugSessionListTool(project),
            new DebugSessionStartTool(project),
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
