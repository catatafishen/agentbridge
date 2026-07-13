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
 * Reads output from a terminal owned by the current MCP session.
 */
public final class ReadTerminalOutputTool extends TerminalTool {

    private static final String PARAM_MAX_LINES = "max_lines";

    public ReadTerminalOutputTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_terminal_output";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Terminal Output";
    }

    @Override
    public @NotNull String description() {
        return "Read recent output from a terminal owned by this MCP session. "
            + "Use terminal_id from run_in_terminal or list_terminals; tab_name remains "
            + "available as an owner-scoped compatibility selector.";
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
            Param.optional(JSON_TERMINAL_ID, TYPE_STRING,
                "Stable ID returned by run_in_terminal (preferred)"),
            Param.optional(JSON_TAB_NAME, TYPE_STRING,
                "Owner-scoped terminal tab name. If both selectors are omitted, reads this session's most recent terminal"),
            Param.optional(PARAM_MAX_LINES, TYPE_INTEGER,
                "Maximum lines from the end of the buffer (default: 50). Use 0 for the full buffer")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String terminalId = optionalSelector(args, JSON_TERMINAL_ID);
        String tabName = optionalSelector(args, JSON_TAB_NAME);
        String ownerId = currentOwnerId();
        int maxLines =
            args.has(PARAM_MAX_LINES) ? args.get(PARAM_MAX_LINES).getAsInt() : DEFAULT_MAX_LINES;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                AgentTabTracker.AgentTerminal terminal =
                    resolveOwnedTerminal(ownerId, terminalId, tabName);
                if (terminal == null) {
                    resultFuture.complete(
                        "No terminal owned by this MCP session matches "
                            + selectorDescription(terminalId, tabName)
                            + ". Use run_in_terminal or list_terminals.");
                    return;
                }
                readTerminalText(resultFuture, terminal, maxLines);
            } catch (Exception e) {
                LOG.warn("Failed to read terminal", e);
                resultFuture.complete("Failed to read terminal: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal read timed out.";
        } catch (Exception e) {
            return "Terminal read timed out.";
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
