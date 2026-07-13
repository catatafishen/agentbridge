package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes input to a terminal owned by the current MCP session.
 */
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
        return "Send raw text or keystrokes to a terminal owned by this MCP session. "
            + "Use terminal_id from run_in_terminal or list_terminals.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_INPUT, TYPE_STRING,
                "Text or keystrokes to send. Supports: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, {backspace}, \\n, \\t"),
            Param.optional(JSON_TERMINAL_ID, TYPE_STRING,
                "Stable ID returned by run_in_terminal (preferred)"),
            Param.optional(JSON_TAB_NAME, TYPE_STRING,
                "Owner-scoped tab name. If both selectors are omitted, writes to this session's most recent terminal")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String input = args.get(PARAM_INPUT).getAsString();
        String terminalId = optionalSelector(args, JSON_TERMINAL_ID);
        String tabName = optionalSelector(args, JSON_TAB_NAME);
        String ownerId = currentOwnerId();
        String resolved = resolveInputEscapes(input);

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                AgentTabTracker.AgentTerminal terminal =
                    resolveOwnedTerminal(ownerId, terminalId, tabName);
                if (terminal == null) {
                    resultFuture.complete(
                        "No terminal owned by this MCP session matches "
                            + selectorDescription(terminalId, tabName)
                            + ". Use run_in_terminal to create one first.");
                    return;
                }

                var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
                Object widget = findTerminalWidgetByContent(managerClass, terminal.content());
                if (widget == null) {
                    resultFuture.complete(
                        "No terminal widget found for terminal_id '"
                            + terminal.terminalId() + "'.");
                    return;
                }

                var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
                var getTtyAccessor = widgetInterface.getMethod("getTtyConnectorAccessor");
                var accessor = getTtyAccessor.invoke(widget);
                var getTty = accessor.getClass().getMethod("getTtyConnector");
                var tty = getTty.invoke(accessor);

                if (tty == null) {
                    resultFuture.complete(
                        "Terminal [terminal_id=" + terminal.terminalId()
                            + "] has no active process. The command may have finished.");
                    return;
                }

                var ttyInterface = Class.forName(TTY_CONNECTOR_CLASS);
                ttyInterface.getMethod("write", String.class).invoke(tty, resolved);

                resultFuture.complete(
                    "Sent " + describeInput(input, resolved)
                        + " to terminal '" + terminal.displayName() + "'.\n"
                        + "terminal_id: " + terminal.terminalId()
                        + "\n\nUse read_terminal_output with this terminal_id to see the result.");
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
