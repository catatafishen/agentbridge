package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugStepTool extends DebugTool {

    public DebugStepTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_step";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Step";
    }

    @Override
    public @NotNull String description() {
        return "Step over / into / out of current line, or continue to next breakpoint. Returns a snapshot after pausing.";
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
                {"action", TYPE_STRING, "One of: 'over' (step over), 'into' (step into), 'out' (step out), 'continue' (resume to next breakpoint)"},
        }, "action");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        String action = args.get("action").getAsString().toLowerCase();

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
                // continue waiting for pause
            }
        });

        switch (action) {
            case "over" -> session.stepOver(false);
            case "into" -> session.stepInto();
            case "out" -> session.stepOut();
            case "continue" -> session.resume();
            default -> throw new Exception("Unknown action '" + action + "'. Use: over, into, out, continue");
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) return "Timed out waiting for debugger to pause (30s). Session may be running.";
        if (stopped.get()) return "Debug session stopped.";

        return buildSnapshot(session, true, true, true);
    }
}
