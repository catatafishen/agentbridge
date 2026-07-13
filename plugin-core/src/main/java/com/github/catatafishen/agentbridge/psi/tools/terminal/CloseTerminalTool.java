package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Closes an integrated terminal owned by the current MCP session.
 */
public final class CloseTerminalTool extends TerminalTool {

    public CloseTerminalTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "close_terminal";
    }

    @Override
    public @NotNull String displayName() {
        return "Close Terminal";
    }

    @Override
    public @NotNull String description() {
        return "Close a terminal owned by this MCP session when it is no longer needed. "
            + "Use terminal_id (preferred) or the owner-scoped tab_name fallback. "
            + "Other agents' terminals and user-created terminal tabs cannot be closed.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Close owned terminal: {terminal_id} {tab_name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(JSON_TERMINAL_ID, TYPE_STRING,
                "Stable ID returned by run_in_terminal (preferred)"),
            Param.optional(JSON_TAB_NAME, TYPE_STRING,
                "Owner-scoped terminal tab name (compatibility fallback)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String terminalId = optionalSelector(args, JSON_TERMINAL_ID);
        String tabName = optionalSelector(args, JSON_TAB_NAME);
        if (terminalId == null && tabName == null) {
            return "Error: Provide terminal_id from run_in_terminal/list_terminals "
                + "or an owner-scoped tab_name.";
        }

        String ownerId = currentOwnerId();
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() ->
            closeTerminal(ownerId, terminalId, tabName, resultFuture));
        return awaitCloseResult(resultFuture, 10, TimeUnit.SECONDS);
    }

    static @NotNull String awaitCloseResult(
        @NotNull CompletableFuture<String> resultFuture,
        long timeout,
        @NotNull TimeUnit unit
    ) {
        try {
            return resultFuture.get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Terminal close interrupted.";
        } catch (TimeoutException e) {
            return "Error: Terminal close timed out.";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warn("Terminal close failed", cause);
            return "Error: Failed to close terminal: " + failureDetail(cause);
        } catch (Exception e) {
            LOG.warn("Terminal close failed", e);
            return "Error: Failed to close terminal: " + failureDetail(e);
        }
    }

    private static @NotNull String failureDetail(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    private void closeTerminal(
        String ownerId,
        String terminalId,
        String tabName,
        CompletableFuture<String> resultFuture
    ) {
        try {
            AgentTabTracker.AgentTerminal terminal =
                resolveOwnedTerminal(ownerId, terminalId, tabName);
            if (terminal == null) {
                resultFuture.complete(
                    "Error: No terminal owned by this MCP session matches "
                        + selectorDescription(terminalId, tabName)
                        + ". Use list_terminals to see this session's terminals.");
                return;
            }

            var toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (toolWindow == null
                || !toolWindow.getContentManager().removeContent(terminal.content(), true)) {
                resultFuture.complete(
                    "Error: Failed to close terminal '" + terminal.displayName()
                        + "' [terminal_id=" + terminal.terminalId() + "].");
                return;
            }

            AgentTabTracker.getInstance(project)
                .untrackTerminal(ownerId, terminal.terminalId());
            resultFuture.complete(
                "Closed AgentBridge terminal '" + terminal.displayName()
                    + "' [terminal_id=" + terminal.terminalId() + "].");
        } catch (Exception e) {
            LOG.warn("Failed to close terminal " + selectorDescription(terminalId, tabName), e);
            resultFuture.complete(
                "Error: Failed to close terminal: " + failureDetail(e));
        }
    }
}
