package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BreakpointRemoveTool extends DebugTool {

    private static final String PARAM_INDEX = "index";
    private static final String PARAM_FILE = "file";
    private static final String PARAM_LINE = "line";
    private static final String PARAM_REMOVE_ALL = "remove_all";

    public BreakpointRemoveTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_remove";
    }

    @Override
    public @NotNull String displayName() {
        return "Remove Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Remove a breakpoint by 'index' (1-based from breakpoint_list) or by 'file'+'line', or remove all breakpoints with 'remove_all: true'. "
            + "Returns an error if no selector is provided.";
    }

    @Override
    public @NotNull String kind() {
        return "write";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_INDEX, TYPE_INTEGER, "1-based breakpoint index from breakpoint_list. Alternative to file+line."},
            {PARAM_FILE, TYPE_STRING, "File path identifying the breakpoint. Use with 'line'. Alternative to index."},
            {PARAM_LINE, TYPE_INTEGER, "Line number (1-based). Use with 'file'. Alternative to index."},
            {PARAM_REMOVE_ALL, TYPE_BOOLEAN, "Set true to remove all breakpoints"},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        if (args.has(PARAM_REMOVE_ALL) && args.get(PARAM_REMOVE_ALL).getAsBoolean()) {
            PlatformApiCompat.writeActionRunAndWait(() -> {
                for (XBreakpoint<?> candidate : all) mgr.removeBreakpoint(candidate);
            });
            return "Removed all " + all.length + " breakpoint(s).";
        }

        XBreakpoint<?> resolved = resolveByIndex(args, all);
        if (resolved == null) resolved = resolveByFileLine(args, mgr);
        if (resolved == null) return buildResolveError(args, all.length);

        final XBreakpoint<?> bp = resolved;
        String desc = describeBreakpoint(bp);
        PlatformApiCompat.writeActionRunAndWait(() -> mgr.removeBreakpoint(bp));
        return "Removed breakpoint " + desc + ".";
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
            for (XBreakpoint<?> candidate : mgr.getAllBreakpoints()) {
                if (candidate instanceof XLineBreakpoint<?> lbp
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
            return "Error: breakpoint index " + args.get(PARAM_INDEX).getAsInt()
                + " out of range (1-" + total + "). Run breakpoint_list to see current breakpoints.";
        }
        if (args.has(PARAM_FILE)) {
            return "Error: no breakpoint found at " + args.get(PARAM_FILE).getAsString()
                + (args.has(PARAM_LINE) ? ":" + args.get(PARAM_LINE).getAsInt() : "") + ".";
        }
        return "Error: specify 'index', 'file'+'line', or 'remove_all: true'. "
            + "Run breakpoint_list to see current breakpoints.";
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
}
