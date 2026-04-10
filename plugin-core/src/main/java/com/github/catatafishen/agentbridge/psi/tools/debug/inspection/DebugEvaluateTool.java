package com.github.catatafishen.agentbridge.psi.tools.debug.inspection;

import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class DebugEvaluateTool extends DebugTool {

    public DebugEvaluateTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_evaluate";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Evaluate";
    }

    @Override
    public @NotNull String description() {
        return "Evaluate an expression in the current debug context (can have side effects). Requires a paused session.";
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
            Param.required("expression", TYPE_STRING, "Expression to evaluate in the current debug context"),
            Param.optional("frame_index", TYPE_INTEGER, "Stack frame to evaluate in (default: 0 = current frame)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        String expression = args.get("expression").getAsString();
        int frameIndex = args.has("frame_index") ? args.get("frame_index").getAsInt() : 0;

        XSuspendContext ctx = session.getSuspendContext();
        XExecutionStack stack = ctx.getActiveExecutionStack();
        if (stack == null) return "No active execution stack.";

        XStackFrame frame;
        if (frameIndex == 0) {
            frame = getTopFrame(stack);
        } else {
            List<XStackFrame> frames = getAllFrames(stack);
            if (frameIndex < 0 || frameIndex >= frames.size()) {
                return "Frame index " + frameIndex + " out of range (0–" + (frames.size() - 1) + ").";
            }
            frame = frames.get(frameIndex);
        }
        if (frame == null) return "Could not get stack frame #" + frameIndex + ".";

        XDebuggerEvaluator evaluator = frame.getEvaluator();
        if (evaluator == null) return "No evaluator available for the current language/frame.";

        XSourcePosition pos = frame.getSourcePosition();
        CompletableFuture<String> future = new CompletableFuture<>();
        evaluator.evaluate(expression, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue result) {
                future.complete(renderValue(result));
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                future.complete("Error: " + errorMessage);
            }
        }, pos);

        return expression + " = " + future.get(ASYNC_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
    }
}
