package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.session;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DebugSessionListTool extends DebugTool {

    public DebugSessionListTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_session_list";
    }

    @Override
    public @NotNull String displayName() {
        return "List Debug Sessions";
    }

    @Override
    public @NotNull String description() {
        return "List all active debug sessions with their current position and status";
    }

    @Override
    public @NotNull String kind() {
        return "read";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        XDebugSession[] sessions = XDebuggerManager.getInstance(project).getDebugSessions();
        if (sessions.length == 0) return "No active debug sessions.";

        XDebugSession current = XDebuggerManager.getInstance(project).getCurrentSession();
        String basePath = project.getBasePath();
        var sb = new StringBuilder("Active debug sessions (").append(sessions.length).append("):\n\n");
        for (XDebugSession s : sessions) {
            sb.append(s.getSessionName());
            if (s == current) sb.append(" (active)");
            sb.append(" - ").append(sessionStatus(s, basePath)).append('\n');
        }
        sb.append("\nThe session name is the identifier to use with debug tools.");
        sb.append("\nOnly the active (current) session can be targeted by debug tools.");
        sb.append("\nUse 'run_configuration' to start a new debug session.");
        return sb.toString().strip();
    }

    private String sessionStatus(@NotNull XDebugSession s, @Nullable String basePath) {
        if (s.isStopped()) return "STOPPED";
        if (s.getSuspendContext() == null) return "RUNNING";
        XSourcePosition pos = s.getCurrentPosition();
        if (pos == null) return "PAUSED";
        String relPath = relativize(basePath, pos.getFile().getPath());
        String location = relPath != null ? relPath : pos.getFile().getName();
        return "PAUSED at " + location + ':' + (pos.getLine() + 1);
    }
}
