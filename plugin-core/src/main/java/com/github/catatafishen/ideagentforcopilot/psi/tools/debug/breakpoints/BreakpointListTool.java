package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.breakpoints.SuspendPolicy;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BreakpointListTool extends DebugTool {

    public BreakpointListTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_list";
    }

    @Override
    public @NotNull String displayName() {
        return "List Breakpoints";
    }

    @Override
    public @NotNull String description() {
        return """
            List all breakpoints with their index, location, enabled status, and conditions.

            Output format per breakpoint:
              index: N  location: relative/path/File.java:42  status: enabled
              condition: x > 5  (if set)
              log: message     (if set)

            Use the index with breakpoint_update or breakpoint_remove.
            Alternatively pass file + line to breakpoint_update/remove to avoid index fragility.
            """;
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();

        XBreakpoint<?>[] breakpoints = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        if (breakpoints.length == 0) return "No breakpoints set.";

        String basePath = project.getBasePath();
        var sb = new StringBuilder();
        sb.append("Breakpoints (").append(breakpoints.length).append("):\n\n");
        for (int i = 0; i < breakpoints.length; i++) {
            XBreakpoint<?> bp = breakpoints[i];
            sb.append("index: ").append(i + 1);
            sb.append("  location: ").append(breakpointLocation(bp, basePath));
            sb.append("  status: ").append(bp.isEnabled() ? "enabled" : "DISABLED");

            SuspendPolicy suspend = bp.getSuspendPolicy();
            if (suspend == SuspendPolicy.NONE) sb.append("  suspend: none");
            else if (suspend == SuspendPolicy.THREAD) sb.append("  suspend: thread");

            appendExpr(sb, "condition", bp.getConditionExpression());
            appendExpr(sb, "log", bp.getLogExpressionObject());
            sb.append('\n');
        }
        sb.append("\nPass index to breakpoint_update or breakpoint_remove, or use file+line as an alternative selector.");
        return sb.toString().strip();
    }

    @NotNull
    private String breakpointLocation(@NotNull XBreakpoint<?> bp, @Nullable String basePath) {
        if (bp instanceof XLineBreakpoint<?> lbp && lbp.getSourcePosition() != null) {
            String fullPath = lbp.getSourcePosition().getFile().getPath();
            String relPath = relativize(basePath, fullPath);
            String location = relPath != null ? relPath : lbp.getSourcePosition().getFile().getName();
            return location + ':' + (lbp.getLine() + 1);
        }
        return bp.getType().getTitle();
    }

    private static void appendExpr(@NotNull StringBuilder sb, @NotNull String label,
                                   @Nullable XExpression expr) {
        if (expr != null && !expr.getExpression().isBlank()) {
            sb.append("\n  ").append(label).append(": ").append(expr.getExpression());
        }
    }
}
