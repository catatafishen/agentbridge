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
 * Closes an integrated terminal tab created by AgentBridge.
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
        return "Close an AgentBridge-created terminal tab when it is no longer needed. "
            + "This closes the tab but does not stop a running command in it. "
            + "Refuses to close user-created terminal tabs. Use list_terminals to find tab names.";
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
        return "Close terminal: {tab_name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(JSON_TAB_NAME, TYPE_STRING,
                "Name of the AgentBridge-created terminal tab to close")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String tabName = args.get(JSON_TAB_NAME).getAsString();
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> closeTerminal(tabName, resultFuture));

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
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private void closeTerminal(String tabName, CompletableFuture<String> resultFuture) {
        try {
            var content = resolveTerminalContent(tabName);
            if (content == null) {
                resultFuture.complete(
                    "Error: No terminal tab found matching '" + tabName
                        + "'. Use list_terminals to see available tabs.");
                return;
            }

            String displayName = content.getDisplayName();
            AgentTabTracker tracker = AgentTabTracker.getInstance(project);
            if (displayName == null || !tracker.isTrackedTerminalTab(displayName)) {
                String rejectedName = displayName != null ? displayName : tabName;
                resultFuture.complete(
                    "Error: Refusing to close terminal '" + rejectedName
                        + "' because it was not created by AgentBridge.");
                return;
            }

            var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (toolWindow == null || !toolWindow.getContentManager().removeContent(content, true)) {
                resultFuture.complete("Error: Failed to close terminal '" + displayName + "'.");
                return;
            }

            tracker.untrackTerminalTab(displayName);
            resultFuture.complete("Closed AgentBridge terminal '" + displayName + "'.");
        } catch (Exception e) {
            LOG.warn("Failed to close terminal: " + tabName, e);
            resultFuture.complete(
                "Error: Failed to close terminal '" + tabName + "': " + failureDetail(e));
        }
    }
}
