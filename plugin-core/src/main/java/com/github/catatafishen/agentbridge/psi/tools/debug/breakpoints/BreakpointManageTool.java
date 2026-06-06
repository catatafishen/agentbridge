package com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.breakpoints.SuspendPolicy;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Unified breakpoint management tool. Replaces the separate breakpoint_add,
 * breakpoint_remove, breakpoint_update, and breakpoint_add_exception tools with a single
 * action-dispatched interface to keep the exposed tool count low.
 */
public final class BreakpointManageTool extends DebugTool {

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_FILE = "file";
    private static final String PARAM_LINE = "line";
    private static final String PARAM_INDEX = "index";
    private static final String PARAM_CONDITION = "condition";
    private static final String PARAM_LOG_EXPRESSION = "log_expression";
    private static final String PARAM_ENABLED = "enabled";
    private static final String PARAM_SUSPEND = "suspend";
    private static final String PARAM_REMOVE_ALL = "remove_all";
    private static final String PARAM_EXCEPTION_CLASS = "exception_class";

    public BreakpointManageTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_manage";
    }

    @Override
    public @NotNull String displayName() {
        return "Manage Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return """
            Add, remove, or update breakpoints. Use 'action' to select the operation:

            action=add   — Add a line breakpoint. Requires 'file' and 'line'. Optional: \
            'condition', 'log_expression', 'enabled' (default true), 'suspend' (default true). \
            Set suspend=false with log_expression for a tracepoint.

            action=remove — Remove a breakpoint. Identify by 'index' (from breakpoint_list) \
            OR by 'file'+'line'. Set 'remove_all: true' to clear all breakpoints at once.

            action=update — Enable/disable or change condition/log on an existing breakpoint. \
            Identify by 'index' OR by 'file'+'line'. Pass 'condition' or 'log_expression' as \
            empty string to clear them.

            action=add_exception — Add a breakpoint that fires when an exception class is \
            thrown. Requires 'exception_class' (fully qualified, or '*' for any exception). \
            Works with Java/Kotlin (requires Java plugin). Optional: 'enabled' (default true).

            Use breakpoint_list to see current breakpoints and their index numbers.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_ACTION, TYPE_STRING,
                "Operation: 'add', 'remove', 'update', or 'add_exception'"),
            // add / update shared params
            Param.optional(PARAM_FILE, TYPE_STRING,
                "File path (absolute or project-relative). Required for add; used as identifier for remove/update."),
            Param.optional(PARAM_LINE, TYPE_INTEGER,
                "Line number (1-based). Required for add; used as identifier for remove/update."),
            // remove / update identifier alternative
            Param.optional(PARAM_INDEX, TYPE_INTEGER,
                "1-based breakpoint index from breakpoint_list. Alternative identifier for remove/update."),
            // add / update properties
            Param.optional(PARAM_CONDITION, TYPE_STRING,
                "Condition expression (add: sets it; update: sets or clears with empty string)"),
            Param.optional(PARAM_LOG_EXPRESSION, TYPE_STRING,
                "Log expression (add: sets it; update: sets or clears with empty string)"),
            Param.optional(PARAM_ENABLED, TYPE_BOOLEAN,
                "Whether the breakpoint is enabled (default: true)"),
            Param.optional(PARAM_SUSPEND, TYPE_BOOLEAN,
                "Whether to suspend execution on hit (default: true). Set false for a tracepoint."),
            // remove-specific
            Param.optional(PARAM_REMOVE_ALL, TYPE_BOOLEAN,
                "Set true to remove all breakpoints (action=remove only)"),
            // add_exception-specific
            Param.optional(PARAM_EXCEPTION_CLASS, TYPE_STRING,
                "Fully qualified exception class name, or '*' for any exception (action=add_exception only)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String action = args.get(PARAM_ACTION).getAsString();
        return switch (action) {
            case "add" -> executeAdd(args);
            case "remove" -> executeRemove(args);
            case "update" -> executeUpdate(args);
            case "add_exception" -> executeAddException(args);
            default -> "Error: Unknown action '" + action + "'. Use 'add', 'remove', 'update', or 'add_exception'.";
        };
    }

    // ── add ──────────────────────────────────────────────────────────────────

    @NotNull
    private String executeAdd(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_FILE) || !args.has(PARAM_LINE)) {
            return "Error: action=add requires 'file' and 'line'.";
        }
        String path = args.get(PARAM_FILE).getAsString();
        int lineZeroBased = args.get(PARAM_LINE).getAsInt() - 1;
        String condition = args.has(PARAM_CONDITION) ? args.get(PARAM_CONDITION).getAsString() : null;
        String logExpr = args.has(PARAM_LOG_EXPRESSION) ? args.get(PARAM_LOG_EXPRESSION).getAsString() : null;
        boolean enabled = !args.has(PARAM_ENABLED) || args.get(PARAM_ENABLED).getAsBoolean();
        boolean suspend = !args.has(PARAM_SUSPEND) || args.get(PARAM_SUSPEND).getAsBoolean();

        VirtualFile file = refreshAndFindVirtualFile(path);
        if (file == null) return "Error: File not found: " + path;

        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XLineBreakpoint<?> existing = findLineBreakpoint(mgr, file, lineZeroBased);
        if (existing != null) {
            return "Breakpoint already exists at " + file.getName() + ':' + (lineZeroBased + 1)
                + ". Use action=update to modify it.";
        }

        PlatformApiCompat.writeActionRunAndWait(() ->
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, lineZeroBased, false));

        XLineBreakpoint<?> bp = findLineBreakpoint(mgr, file, lineZeroBased);
        if (bp == null) return "Failed to add breakpoint — the file or line may not support breakpoints.";

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

        int index = findBreakpointIndex(mgr, bp);
        String relPath = relativize(project.getBasePath(), file.getPath());
        String location = relPath != null ? relPath : file.getName();
        return formatAddResult(index, location, lineZeroBased + 1, condition, logExpr, enabled, suspend);
    }

    static String formatAddResult(int index, String location, int lineOneBased,
                                  String condition, String logExpr,
                                  boolean enabled, boolean suspend) {
        var sb = new StringBuilder("Added breakpoint");
        if (index > 0) sb.append(" index ").append(index);
        sb.append(" at ").append(location).append(':').append(lineOneBased);
        if (condition != null && !condition.isBlank()) sb.append(" [condition: ").append(condition).append(']');
        if (logExpr != null && !logExpr.isBlank()) sb.append(" [log: ").append(logExpr).append(']');
        if (!enabled) sb.append(" [disabled]");
        if (!suspend) sb.append(" [non-suspending]");
        return sb.toString();
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @NotNull
    private String executeRemove(@NotNull JsonObject args) throws Exception {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        if (args.has(PARAM_REMOVE_ALL) && args.get(PARAM_REMOVE_ALL).getAsBoolean()) {
            PlatformApiCompat.writeActionRunAndWait(() -> {
                for (XBreakpoint<?> bp : all) mgr.removeBreakpoint(bp);
            });
            return "Removed all " + all.length + " breakpoint(s).";
        }

        XBreakpoint<?> resolved = resolveByIndex(args, all);
        if (resolved == null) resolved = resolveByFileLine(args, mgr);
        if (resolved == null) return buildResolveError(args, all.length, "remove");

        String desc = describeBreakpoint(resolved);
        final XBreakpoint<?> toRemove = resolved;
        PlatformApiCompat.writeActionRunAndWait(() -> mgr.removeBreakpoint(toRemove));
        return "Removed breakpoint " + desc + ".";
    }

    // ── update ────────────────────────────────────────────────────────────────

    @NotNull
    private String executeUpdate(@NotNull JsonObject args) throws Exception {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        XBreakpoint<?> resolved = resolveByIndex(args, all);
        if (resolved == null) resolved = resolveByFileLine(args, mgr);
        if (resolved == null) return buildResolveError(args, all.length, "update");

        final XBreakpoint<?> bp = resolved;
        PlatformApiCompat.writeActionRunAndWait(() -> applyUpdates(bp, args));

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

    // ── add_exception ─────────────────────────────────────────────────────────

    // XBreakpointManager.addBreakpoint() requires a raw XBreakpointType parameter because
    // the platform API uses a wildcard type (XBreakpointType<?, ?>) and Java's type system
    // cannot express the correlation between the type token and the properties object at the
    // call site. The cast is unavoidable — there is no generic-safe API for this platform call.
    @SuppressWarnings({"unchecked", "rawtypes"})
    @NotNull
    private String executeAddException(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_EXCEPTION_CLASS)) {
            return "Error: action=add_exception requires 'exception_class'.";
        }
        String exceptionClass = args.get(PARAM_EXCEPTION_CLASS).getAsString();
        boolean enabled = !args.has(PARAM_ENABLED) || args.get(PARAM_ENABLED).getAsBoolean();

        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        List<XBreakpointType<?, ?>> types = PlatformApiCompat.listXBreakpointTypes();

        XBreakpointType exceptionType = null;
        for (XBreakpointType<?, ?> type : types) {
            if (type.getId().toLowerCase().contains("exception") && !(type instanceof XLineBreakpointType)) {
                exceptionType = type;
                break;
            }
        }
        if (exceptionType == null) {
            String available = types.stream().map(XBreakpointType::getTitle).collect(Collectors.joining(", "));
            return "Error: No exception breakpoint type found. Available types: " + available;
        }

        final XBreakpointType finalType = exceptionType;
        final XBreakpointProperties<?> props = finalType.createProperties();
        XBreakpoint<?> bp = PlatformApiCompat.writeActionComputeAndWait(
            () -> mgr.addBreakpoint(finalType, props));
        bp.setEnabled(enabled);

        if (!exceptionClass.equals("*") && props != null) {
            try {
                var method = props.getClass().getMethod("setQualifiedName", String.class);
                method.invoke(props, exceptionClass);
            } catch (NoSuchMethodException ignored) {
                try {
                    var method = props.getClass().getMethod("setExceptionClass", String.class);
                    method.invoke(props, exceptionClass);
                } catch (NoSuchMethodException ignored2) {
                    return "Added exception breakpoint but could not set class name '" + exceptionClass
                        + "' (properties type: " + props.getClass().getSimpleName()
                        + " has no setQualifiedName/setExceptionClass method). Configure it manually in the IDE.";
                }
            }
        }
        return "Added exception breakpoint"
            + (exceptionClass.equals("*") ? " (any exception)" : " for " + exceptionClass)
            + (enabled ? "" : " [disabled]");
    }

    // ── shared helpers ────────────────────────────────────────────────────────

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

    @Nullable
    private static XLineBreakpoint<?> findLineBreakpoint(
        XBreakpointManager mgr, VirtualFile file, int line) {
        return ApplicationManager.getApplication()
            .runReadAction((Computable<XLineBreakpoint<?>>) () -> {
                for (XBreakpoint<?> bp : mgr.getAllBreakpoints()) {
                    if (bp instanceof XLineBreakpoint<?> lbp
                        && lbp.getLine() == line
                        && fileMatchesBreakpoint(file, lbp)) {
                        return lbp;
                    }
                }
                return null;
            });
    }

    private static boolean fileMatchesBreakpoint(VirtualFile file, XLineBreakpoint<?> lbp) {
        if (lbp.getSourcePosition() != null) {
            return file.equals(lbp.getSourcePosition().getFile());
        }
        // Fall back to raw file URL for C/C++ breakpoints in CLion: source position may be
        // null immediately after toggleLineBreakpoint() because the debug index hasn't resolved
        // the symbol yet, but getFileUrl() / getLine() are always available.
        return file.getUrl().equals(lbp.getFileUrl());
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
    private static String buildResolveError(@NotNull JsonObject args, int total, @NotNull String action) {
        if (args.has(PARAM_INDEX)) {
            return "Error: Breakpoint index " + args.get(PARAM_INDEX).getAsInt()
                + " out of range (1-" + total + "). Run breakpoint_list to see current breakpoints.";
        }
        if (args.has(PARAM_FILE)) {
            return "Error: No breakpoint found at " + args.get(PARAM_FILE).getAsString()
                + (args.has(PARAM_LINE) ? ":" + args.get(PARAM_LINE).getAsInt() : "") + ".";
        }
        return "Error: Specify 'index' or 'file'+'line' to identify the breakpoint for action=" + action + ".";
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
    private String describeBreakpoint(@NotNull XBreakpoint<?> bp) {
        if (bp instanceof XLineBreakpoint<?> lbp && lbp.getSourcePosition() != null) {
            String relPath = relativize(project.getBasePath(), lbp.getSourcePosition().getFile().getPath());
            String location = relPath != null ? relPath : lbp.getSourcePosition().getFile().getName();
            return "at " + location + ':' + (lbp.getLine() + 1);
        }
        return "(" + bp.getType().getTitle() + ")";
    }

    private static void appendExprConfirm(@NotNull StringBuilder sb, @NotNull String label,
                                          @Nullable XExpression expr) {
        if (expr != null && !expr.getExpression().isBlank()) {
            sb.append(", ").append(label).append(": ").append(expr.getExpression());
        }
    }
}
