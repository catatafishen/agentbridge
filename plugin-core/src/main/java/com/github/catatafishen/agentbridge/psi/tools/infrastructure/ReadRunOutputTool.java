package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads output from a recent Run panel tab by name.
 */
public final class ReadRunOutputTool extends InfrastructureTool {

    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String PARAM_OFFSET = "offset";
    private static final String JSON_TAB_NAME = "tab_name";

    public ReadRunOutputTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_run_output";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Run Output";
    }

    @Override
    public @NotNull String description() {
        return "Read output from a recent Run panel tab by name";
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
        return schema(
            Param.optional(JSON_TAB_NAME, TYPE_STRING, "Name of the Run tab to read (default: most recent)"),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Character offset to start from (default: -1 = show last max_chars chars). Use 0 to read from the beginning, or a previous end offset to paginate forward.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : -1;
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        try {
            var findResult = ApplicationManager.getApplication().runReadAction((Computable<Object>) () -> {
                var descriptors = collectRunDescriptors();
                if (descriptors.isEmpty()) return "No Run or Debug panel tabs available.";
                return findTargetRunDescriptor(descriptors, tabName);
            });

            if (findResult instanceof String errorMsg) return errorMsg;

            var target = (com.intellij.execution.ui.RunContentDescriptor) findResult;
            var console = target.getExecutionConsole();
            if (console == null) {
                return "Tab '" + target.getDisplayName() + "' has no console.";
            }

            var textRef = new AtomicReference<String>();
            EdtUtil.invokeAndWait(() ->
                textRef.set(readConsoleTextOnEdt(console)));

            String text = textRef.get();
            if (text == null || text.isEmpty()) {
                var consoleClass = target.getExecutionConsole() != null
                    ? target.getExecutionConsole().getClass().getName() : "null";
                return "Tab '" + target.getDisplayName()
                    + "' has no text content (console type: " + consoleClass
                    + "). The run may still be in progress or the console type is unsupported.";
            }
            return formatRunOutput(target.getDisplayName(), text, maxChars, offset);
        } catch (Exception e) {
            return "Error reading Run output: " + e.getMessage();
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }

    private List<com.intellij.execution.ui.RunContentDescriptor> collectRunDescriptors() {
        var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
        return new ArrayList<>(manager.getAllDescriptors());
    }

    private Object findTargetRunDescriptor(List<com.intellij.execution.ui.RunContentDescriptor> descriptors,
                                           String tabName) {
        if (tabName == null) {
            return descriptors.getLast();
        }

        for (var d : descriptors) {
            if (d.getDisplayName() != null && d.getDisplayName().contains(tabName)) {
                return d;
            }
        }

        StringBuilder available = new StringBuilder("No tab matching '").append(tabName).append("'. Available tabs:\n");
        for (var d : descriptors) {
            available.append("  - ").append(d.getDisplayName()).append("\n");
        }
        return available.toString();
    }
}
