package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WriteTerminalInputTool extends TerminalTool {

    private static final String PARAM_INPUT = "input";

    public WriteTerminalInputTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "write_terminal_input";
    }

    @Override
    public @NotNull String displayName() {
        return "Write Terminal Input";
    }

    @Override
    public @NotNull String description() {
        return "Send raw text or keystrokes to a running terminal (e.g. answer prompts, send Ctrl-C)";
    }

    

    @Override
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_INPUT, TYPE_STRING, "Text or keystrokes to send. Supports escape sequences: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, {backspace}, \\n, \\t"},
            {"tab_name", TYPE_STRING, "Name of the terminal tab to write to. If omitted, writes to the currently selected tab"}
        }, PARAM_INPUT);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String input = args.get(PARAM_INPUT).getAsString();
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        String resolved = resolveInputEscapes(input);

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
                Object widget = findTerminalWidget(managerClass, tabName);
                if (widget == null) {
                    resultFuture.complete("No terminal found" +
                        (tabName != null ? " matching '" + tabName + "'" : "") +
                        ". Use run_in_terminal to create one first.");
                    return;
                }

                var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
                var getTtyAccessor = widgetInterface.getMethod("getTtyConnectorAccessor");
                var accessor = getTtyAccessor.invoke(widget);
                var getTty = accessor.getClass().getMethod("getTtyConnector");
                var tty = getTty.invoke(accessor);

                if (tty == null) {
                    resultFuture.complete("Terminal has no active process. The command may have finished.");
                    return;
                }

                var ttyInterface = Class.forName(TTY_CONNECTOR_CLASS);
                ttyInterface.getMethod("write", String.class).invoke(tty, resolved);

                String description = describeInput(input, resolved);
                resultFuture.complete("Sent " + description + " to terminal." +
                    "\n\nTip: Use read_terminal_output to see the result.");

            } catch (Exception e) {
                LOG.warn("Failed to write terminal input", e);
                resultFuture.complete("Failed to write to terminal: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Input sent (response timed out).";
        } catch (Exception e) {
            return "Input sent (response timed out).";
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
