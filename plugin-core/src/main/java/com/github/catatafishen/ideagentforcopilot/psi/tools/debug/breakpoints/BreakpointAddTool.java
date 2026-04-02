package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.breakpoints.SuspendPolicy;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BreakpointAddTool extends DebugTool {

    private static final String PARAM_CONDITION = "condition";
    private static final String PARAM_LOG_EXPRESSION = "log_expression";
    private static final String PARAM_ENABLED = "enabled";
    private static final String PARAM_SUSPEND = "suspend";

    public BreakpointAddTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_add";
    }

    @Override
    public @NotNull String displayName() {
        return "Add Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Add a line breakpoint with optional condition, log expression, and suspend control";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "File path (absolute or project-relative)"},
            {"line", TYPE_INTEGER, "Line number (1-based)"},
            {PARAM_CONDITION, TYPE_STRING, "Optional condition expression (breakpoint only fires when true)"},
            {PARAM_LOG_EXPRESSION, TYPE_STRING, "Optional log expression (non-suspending log breakpoint)"},
            {PARAM_ENABLED, TYPE_BOOLEAN, "Whether the breakpoint is enabled (default: true)"},
            {PARAM_SUSPEND, TYPE_BOOLEAN, "Whether to suspend execution on hit (default: true). Set false with log_expression for a tracepoint."},
        }, "file", "line");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String path = args.get("file").getAsString();
        int lineZeroBased = args.get("line").getAsInt() - 1;
        String condition = args.has(PARAM_CONDITION) ? args.get(PARAM_CONDITION).getAsString() : null;
        String logExpr = args.has(PARAM_LOG_EXPRESSION) ? args.get(PARAM_LOG_EXPRESSION).getAsString() : null;
        boolean enabled = !args.has(PARAM_ENABLED) || args.get(PARAM_ENABLED).getAsBoolean();
        boolean suspend = !args.has(PARAM_SUSPEND) || args.get(PARAM_SUSPEND).getAsBoolean();

        VirtualFile file = refreshAndFindVirtualFile(path);
        if (file == null) return "File not found: " + path;

        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();

        XLineBreakpoint<?> existing = findLineBreakpoint(mgr, file, lineZeroBased);
        if (existing != null) {
            return "Breakpoint already exists at " + file.getName() + ':' + (lineZeroBased + 1) + ". Use breakpoint_update to modify it.";
        }

        PlatformApiCompat.writeActionRunAndWait(() ->
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, lineZeroBased, false));

        XLineBreakpoint<?> bp = findLineBreakpoint(mgr, file, lineZeroBased);
        if (bp == null) return "Failed to add breakpoint - the file or line may not support breakpoints.";

        applyProperties(bp, enabled, suspend, condition, logExpr);

        int index = findBreakpointIndex(mgr, bp);
        String relPath = relativize(project.getBasePath(), file.getPath());
        String location = relPath != null ? relPath : file.getName();
        return buildResult(index, location, lineZeroBased + 1, condition, logExpr, enabled, suspend);
    }

    private void applyProperties(@NotNull XLineBreakpoint<?> bp, boolean enabled, boolean suspend,
                                 @Nullable String condition, @Nullable String logExpr) throws Exception {
        PlatformApiCompat.writeActionRunAndWait(() -> {
            bp.setEnabled(enabled);
            if (condition != null && !condition.isBlank()) {
                bp.setConditionExpression(PlatformApiCompat.createXExpression(condition));
            }
            if (logExpr != null && !logExpr.isBlank()) {
                bp.setLogExpressionObject(PlatformApiCompat.createXExpression(logExpr));
            }
            bp.setSuspendPolicy(suspend ? SuspendPolicy.ALL : SuspendPolicy.NONE);
        });
    }

    private int findBreakpointIndex(@NotNull XBreakpointManager mgr, @NotNull XBreakpoint<?> bp) {
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);
        for (int i = 0; i < all.length; i++) {
            if (all[i] == bp) return i + 1;
        }
        return -1;
    }

    @NotNull
    private static String buildResult(int index, @NotNull String location, int line,
                                      @Nullable String condition, @Nullable String logExpr,
                                      boolean enabled, boolean suspend) {
        var sb = new StringBuilder("Added breakpoint");
        if (index > 0) sb.append(" index ").append(index);
        sb.append(" at ").append(location).append(':').append(line);
        if (condition != null && !condition.isBlank()) sb.append(" [condition: ").append(condition).append(']');
        if (logExpr != null && !logExpr.isBlank()) sb.append(" [log: ").append(logExpr).append(']');
        if (!enabled) sb.append(" [disabled]");
        if (!suspend) sb.append(" [non-suspending]");
        return sb.toString();
    }

    @Nullable
    private static XLineBreakpoint<?> findLineBreakpoint(
        XBreakpointManager mgr, VirtualFile file, int line) {
        return ApplicationManager.getApplication()
            .runReadAction((Computable<XLineBreakpoint<?>>) () -> {
                for (XBreakpoint<?> bp : mgr.getAllBreakpoints()) {
                    if (bp instanceof XLineBreakpoint<?> lbp
                        && lbp.getSourcePosition() != null
                        && file.equals(lbp.getSourcePosition().getFile())
                        && lbp.getLine() == line) {
                        return lbp;
                    }
                }
                return null;
            });
    }
}
