package com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public final class BreakpointAddExceptionTool extends DebugTool {

    public BreakpointAddExceptionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_add_exception";
    }

    @Override
    public @NotNull String displayName() {
        return "Add Exception Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Add a breakpoint that triggers when a specific exception class is thrown. Works with Java/Kotlin (requires Java plugin).";
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
            Param.required("exception_class", TYPE_STRING, "Fully qualified exception class name (e.g., java.lang.IllegalStateException), or '*' for any exception"),
            Param.optional("enabled", TYPE_BOOLEAN, "Whether the breakpoint is enabled (default: true)")
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String exceptionClass = args.get("exception_class").getAsString();
        boolean enabled = !args.has("enabled") || args.get("enabled").getAsBoolean();

        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();

        // Find an exception breakpoint type (non-line type with "exception" in its ID)
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
            return "No exception breakpoint type found. Available types: " + available;
        }

        final XBreakpointType finalType = exceptionType;
        final XBreakpointProperties<?> props = finalType.createProperties();
        XBreakpoint<?> bp = PlatformApiCompat.writeActionComputeAndWait(
            () -> mgr.addBreakpoint(finalType, props));

        bp.setEnabled(enabled);

        // Set exception class name via reflection (Java exception breakpoint properties hold the class name)
        if (!exceptionClass.equals("*") && props != null) {
            try {
                var method = props.getClass().getMethod("setQualifiedName", String.class);
                method.invoke(props, exceptionClass);
            } catch (NoSuchMethodException ignored) {
                try {
                    var method = props.getClass().getMethod("setExceptionClass", String.class);
                    method.invoke(props, exceptionClass);
                } catch (NoSuchMethodException ignored2) {
                    return "Added exception breakpoint but could not set class name '" + exceptionClass +
                        "' (properties type: " + props.getClass().getSimpleName() +
                        " has no setQualifiedName/setExceptionClass method). Configure it manually in the IDE.";
                }
            }
        }

        return "Added exception breakpoint" + (exceptionClass.equals("*") ? " (any exception)" : " for " + exceptionClass)
            + (enabled ? "" : " [disabled]");
    }
}
