package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.inspection;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class DebugInspectFrameTool extends DebugTool {

    public DebugInspectFrameTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_inspect_frame";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Inspect Frame";
    }

    @Override
    public @NotNull String description() {
        return "Inspect variables in a specific call stack frame (0 = top/current, 1 = caller, etc.)";
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
                {"frame_index", TYPE_INTEGER, "Stack frame index (0 = current/top, 1 = caller, etc.)"},
        }, "frame_index");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        int frameIndex = args.get("frame_index").getAsInt();

        XSuspendContext ctx = session.getSuspendContext();
        XExecutionStack stack = ctx.getActiveExecutionStack();
        if (stack == null) return "No active execution stack.";

        List<XStackFrame> frames = getAllFrames(stack);
        if (frameIndex < 0 || frameIndex >= frames.size()) {
            return "Frame index " + frameIndex + " out of range (0–" + (frames.size() - 1) + "). Use debug_snapshot to see the call stack.";
        }

        XStackFrame frame = frames.get(frameIndex);
        XSourcePosition pos = frame.getSourcePosition();
        var sb = new StringBuilder();
        sb.append("## Frame #").append(frameIndex);
        if (pos != null) sb.append(" — ").append(pos.getFile().getName()).append(':').append(pos.getLine() + 1);
        sb.append('\n');
        if (pos != null) sb.append(buildSourceContext(pos)).append('\n');
        sb.append("## Variables\n");
        sb.append(computeFrameVariables(frame));
        return sb.toString().strip();
    }
}
