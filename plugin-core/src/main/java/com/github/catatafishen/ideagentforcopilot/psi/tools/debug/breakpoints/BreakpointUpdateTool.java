package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.breakpoints.SuspendPolicy;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BreakpointUpdateTool extends DebugTool {

    private static final String PARAM_INDEX = "index";
    private static final String PARAM_FILE = "file";
    private static final String PARAM_LINE = "line";
    private static final String PARAM_ENABLED = "enabled";
    private static final String PARAM_CONDITION = "condition";
    private static final String PARAM_LOG_EXPRESSION = "log_expression";
    private static final String PARAM_SUSPEND = "suspend";

    public BreakpointUpdateTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_update";
    }

    @Override
    public @NotNull String displayName() {
        return "Update Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Enable/disable a breakpoint or update its condition and log expression. "
            + "Identify the breakpoint by 'index' (1-based from breakpoint_list) OR by 'file' + 'line'.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_INDEX, TYPE_INTEGER, "1-based breakpoint index from breakpoint_list. Alternative to file+line."},
            {PARAM_FILE, TYPE_STRING, "File path to identify the breakpoint. Use with 'line'. Alternative to index."},
            {PARAM_LINE, TYPE_INTEGER, "Line number (1-based). Use with 'file'. Alternative to index."},
            {PARAM_ENABLED, TYPE_BOOLEAN, "Enable or disable the breakpoint"},
            {PARAM_CONDITION, TYPE_STRING, "New condition expression, or empty string to clear"},
            {PARAM_LOG_EXPRESSION, TYPE_STRING, "New log expression, or empty string to clear"},
            {PARAM_SUSPEND, TYPE_BOOLEAN, "Whether to suspend on hit"},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        XBreakpoint<?> resolved = resolveByIndex(args, all);
        if (resolved == null) resolved = resolveByFileLine(args, mgr);
        if (resolved == null) return buildResolveError(args, all.length);

        final XBreakpoint<?> bp = resolved;
        PlatformApiCompat.writeActionRunAndWait(() -> applyUpdates(bp, args));
        return buildConfirmation(bp);
    }

    @Nullable
    private static XBreakpoint<?> resolveByIndex(@NotNull JsonObject args, @NotNull XBreakpoint<?>[] all) {
        if (!args.has(PARAM_INDEX)) return null;
        int index = args.get(PARAM_INDEX).getAsInt();
        if (index < 1 || index > all.length) return null;
        return all[index - 1];
    }

    @Nullable
    private XBreakpoint<?> resolveByFileLine(@NotNull JsonObject args, @NotNull XBreakpointManager mgr) {
        if (!args.has(PARAM_FILE) || !args.has(PARAM_LINE)) return null;
        VirtualFile vf = resolveVirtualFile(args.get(PARAM_FILE).getAsString());
        if (vf == null) return null;
        int line = args.get(PARAM_LINE).getAsInt() - 1;
        return ApplicationManager.getApplication().runReadAction((Computable<XLineBreakpoint<?>>) () -> {
            for (XBreakpoint<?> bp : mgr.getAllBreakpoints()) {
                if (bp instanceof XLineBreakpoint<?> lbp
                    && lbp.getSourcePosition() != null
                    && vf.equals(lbp.getSourcePosition().getFile())
                    && lbp.getLine() == line) {
                    return lbp;
                }
            }
            return null;
        });
    }

    @NotNull
    private static String buildResolveError(@NotNull JsonObject args, int total) {
        if (args.has(PARAM_INDEX)) {
            return "Breakpoint index " + args.get(PARAM_INDEX).getAsInt()
                + " out of range (1-" + total + "). Run breakpoint_list to see current breakpoints.";
        }
        if (args.has(PARAM_FILE)) {
            return "No breakpoint found at " + args.get(PARAM_FILE).getAsString()
                + (args.has(PARAM_LINE) ? ":" + args.get(PARAM_LINE).getAsInt() : "") + ".";
        }
        return "Provide 'index' or 'file'+'line' to identify the breakpoint.";
    }

    private static void applyUpdates(@NotNull XBreakpoint<?> bp, @NotNull JsonObject args) {
        if (args.has(PARAM_ENABLED)) bp.setEnabled(args.get(PARAM_ENABLED).getAsBoolean());
        if (args.has(PARAM_CONDITION)) {
            String cond = args.get(PARAM_CONDITION).getAsString();
            bp.setConditionExpression(cond.isBlank() ? null : PlatformApiCompat.createXExpression(cond));
        }
        if (args.has(PARAM_SUSPEND)) {
            bp.setSuspendPolicy(args.get(PARAM_SUSPEND).getAsBoolean() ? SuspendPolicy.ALL : SuspendPolicy.NONE);
        }
        if (args.has(PARAM_LOG_EXPRESSION)) {
            String logExpr = args.get(PARAM_LOG_EXPRESSION).getAsString();
            bp.setLogExpressionObject(logExpr.isBlank() ? null : PlatformApiCompat.createXExpression(logExpr));
        }
    }

    @NotNull
    private String buildConfirmation(@NotNull XBreakpoint<?> bp) {
        var sb = new StringBuilder("Updated breakpoint");
        if (bp instanceof XLineBreakpoint<?> lbp && lbp.getSourcePosition() != null) {
            String relPath = relativize(project.getBasePath(), lbp.getSourcePosition().getFile().getPath());
            String location = relPath != null ? relPath : lbp.getSourcePosition().getFile().getName();
            sb.append(" at ").append(location).append(':').append(lbp.getLine() + 1);
        }
        sb.append(": ").append(bp.isEnabled() ? "on" : "OFF");
        appendExprConfirm(sb, "cond", bp.getConditionExpression());
        appendExprConfirm(sb, "log", bp.getLogExpressionObject());
        if (bp.getSuspendPolicy() == SuspendPolicy.NONE) sb.append(", suspend: none");
        return sb.toString();
    }

    private static void appendExprConfirm(@NotNull StringBuilder sb, @NotNull String label,
                                          @Nullable XExpression expr) {
        if (expr != null && !expr.getExpression().isBlank()) {
            sb.append(", ").append(label).append(": ").append(expr.getExpression());
        }
    }
}
