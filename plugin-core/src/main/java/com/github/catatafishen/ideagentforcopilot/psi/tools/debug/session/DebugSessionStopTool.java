package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.session;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;

public final class DebugSessionStopTool extends DebugTool {

    public DebugSessionStopTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_session_stop";
    }

    @Override
    public @NotNull String displayName() {
        return "Stop Debug Session";
    }

    @Override
    public @NotNull String description() {
        return "Stop the active debug session, or all debug sessions";
    }

    @Override
    public @NotNull String kind() {
        return "write";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
                {"stop_all", TYPE_BOOLEAN, "Set true to stop all debug sessions (default: stops only the active session)"},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        boolean stopAll = args.has("stop_all") && args.get("stop_all").getAsBoolean();
        XDebuggerManager mgr = XDebuggerManager.getInstance(project);

        if (stopAll) {
            XDebugSession[] sessions = mgr.getDebugSessions();
            if (sessions.length == 0) return "No active debug sessions to stop.";
            for (XDebugSession s : sessions) s.stop();
            return "Stopped " + sessions.length + " debug session(s).";
        }

        XDebugSession session = requireSession();
        String name = session.getSessionName();
        session.stop();
        return "Stopped debug session: " + name;
    }
}
