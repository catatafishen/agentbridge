package com.github.catatafishen.agentbridge.psi.tools.debug.inspection;

import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import org.jetbrains.annotations.NotNull;

public final class DebugSnapshotTool extends DebugTool {

    public DebugSnapshotTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_snapshot";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Snapshot";
    }

    @Override
    public @NotNull String description() {
        return "Get the current debugger state: source context, local variables, and call stack. Requires a paused session.";
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
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
                {"include_source", TYPE_BOOLEAN, "Include source code context around current line (default: true)"},
                {"include_variables", TYPE_BOOLEAN, "Include local variables in current frame (default: true)"},
                {"include_stack", TYPE_BOOLEAN, "Include the call stack (default: true)"},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        boolean includeSource = !args.has("include_source") || args.get("include_source").getAsBoolean();
        boolean includeVars = !args.has("include_variables") || args.get("include_variables").getAsBoolean();
        boolean includeStack = !args.has("include_stack") || args.get("include_stack").getAsBoolean();
        return buildSnapshot(session, includeSource, includeVars, includeStack);
    }
}
