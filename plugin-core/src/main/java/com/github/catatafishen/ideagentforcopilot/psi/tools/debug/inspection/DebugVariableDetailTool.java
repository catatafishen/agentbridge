package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.inspection;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DebugVariableDetailTool extends DebugTool {

    private static final String PARAM_PATH = "path";
    private static final String PARAM_DEPTH = "depth";

    public DebugVariableDetailTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_variable_detail";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Variable Detail";
    }

    @Override
    public @NotNull String description() {
        return """
            Expand a variable or object to see its children/fields to a given depth.
            Use 'path' like 'myObj' or 'myObj.field.nested'.

            Output format: each line shows the full dot-path and value, so you can use any
            child path directly as the 'path' parameter in a follow-up call. Example:
              myObj: {MyClass} MyClass@abc
              myObj.field1: {String} "hello"
              myObj.field2: {int} 42
            """;
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
            {PARAM_PATH, TYPE_STRING, "Variable path to expand (e.g., 'myVar' or 'myObj.field'). Top-level variable name must match exactly."},
            {PARAM_DEPTH, TYPE_INTEGER, "Maximum expansion depth (default: 2, max: 5)"},
        }, PARAM_PATH);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        String path = args.get(PARAM_PATH).getAsString();
        int depth = args.has(PARAM_DEPTH) ? Math.min(args.get(PARAM_DEPTH).getAsInt(), 5) : 2;

        XSuspendContext ctx = session.getSuspendContext();
        if (ctx == null) return "Session has no suspend context.";
        XExecutionStack stack = ctx.getActiveExecutionStack();
        if (stack == null) return "No active execution stack.";

        XStackFrame frame = getTopFrame(stack);
        if (frame == null) return "Could not get current stack frame.";

        String[] parts = path.split("\\.", -1);
        XValue target = findValueInFrame(frame, parts);
        if (target == null) {
            return "Variable '" + parts[0] + "' not found in current frame. Run debug_snapshot to see available variables.";
        }

        var sb = new StringBuilder();
        sb.append(path).append(": ").append(renderValue(target)).append('\n');
        expandChildren(sb, target, depth, 0, path);
        return sb.toString().strip();
    }

    /**
     * Finds a variable in the top frame by following a chain of names (split from the dot-path).
     */
    @Nullable
    private XValue findValueInFrame(@NotNull XStackFrame frame, String[] parts) {
        CompletableFuture<XValueChildrenList> childFuture = new CompletableFuture<>();
        frame.computeChildren(childrenNode(childFuture));
        try {
            XValueChildrenList children = childFuture.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            for (int i = 0; i < children.size(); i++) {
                if (parts[0].equals(children.getName(i))) {
                    XValue found = children.getValue(i);
                    return parts.length == 1 ? found : findChildValue(found, parts, 1);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // fall through
        }
        return null;
    }

    @Nullable
    private XValue findChildValue(@NotNull XValue parent, String[] parts, int partIndex) {
        if (partIndex >= parts.length) return parent;
        CompletableFuture<XValueChildrenList> future = new CompletableFuture<>();
        parent.computeChildren(childrenNode(future));
        try {
            XValueChildrenList children = future.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            for (int i = 0; i < children.size(); i++) {
                if (parts[partIndex].equals(children.getName(i))) {
                    XValue found = children.getValue(i);
                    return partIndex == parts.length - 1 ? found : findChildValue(found, parts, partIndex + 1);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Recursively expands children, prefixing each line with the full dot-path so the agent can
     * use any child's path directly in a follow-up {@code debug_variable_detail} call.
     */
    private void expandChildren(@NotNull StringBuilder sb, @NotNull XValue value, int maxDepth,
                                int currentDepth, @NotNull String currentPath) {
        if (currentDepth >= maxDepth) return;

        CompletableFuture<XValueChildrenList> childFuture = new CompletableFuture<>();
        value.computeChildren(childrenNode(childFuture));

        try {
            XValueChildrenList children = childFuture.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            for (int i = 0; i < children.size(); i++) {
                String childName = children.getName(i);
                XValue childValue = children.getValue(i);
                String childPath = currentPath + "." + childName;
                sb.append(childPath).append(": ").append(renderValue(childValue)).append('\n');
                expandChildren(sb, childValue, maxDepth, currentDepth + 1, childPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // skip children on error
        }
    }
}
