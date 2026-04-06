package com.github.catatafishen.agentbridge.psi.tools.debug.navigation;

import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugRunToLineTool extends DebugTool {

    public DebugRunToLineTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_run_to_line";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Run to Line";
    }

    @Override
    public @NotNull String description() {
        return "Run to a specific file:line without setting a permanent breakpoint. Returns a snapshot after pausing.";
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
        return schema(new Object[][]{
                {"file", TYPE_STRING, "File path (absolute or project-relative)"},
                {"line", TYPE_INTEGER, "Target line number (1-based)"},
        }, "file", "line");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        String path = args.get("file").getAsString();
        int lineZeroBased = args.get("line").getAsInt() - 1;

        VirtualFile file = refreshAndFindVirtualFile(path);
        if (file == null) return "File not found: " + path;

        XSourcePosition pos = XDebuggerUtil.getInstance().createPosition(file, lineZeroBased);
        if (pos == null) return "Cannot create source position at " + file.getName() + ':' + (lineZeroBased + 1);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean(false);
        session.addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionPaused() {
                latch.countDown();
            }

            @Override
            public void sessionStopped() {
                stopped.set(true);
                latch.countDown();
            }

            @Override
            public void sessionResumed() {
                // continue waiting
            }
        });

        session.runToPosition(pos, false);

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        if (!completed)
            return "Timed out waiting for program to reach " + file.getName() + ':' + (lineZeroBased + 1) + " (60s).";
        if (stopped.get()) return "Debug session stopped before reaching target line.";

        return buildSnapshot(session, true, true, true);
    }
}
