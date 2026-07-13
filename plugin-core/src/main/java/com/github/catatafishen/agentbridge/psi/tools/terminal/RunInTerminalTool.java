package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command in an integrated terminal owned by the current MCP session.
 */
public final class RunInTerminalTool extends TerminalTool {

    private static final String JSON_COMMAND = "command";
    private static final String JSON_NEW_TAB = "new_tab";
    private static final String JSON_SHELL = "shell";

    public RunInTerminalTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "run_in_terminal";
    }

    @Override
    public @NotNull String displayName() {
        return "Run in Terminal";
    }

    @Override
    public @NotNull String description() {
        return """
            Run a command in an IntelliJ integrated terminal owned by this MCP session. \
            Returns a stable terminal_id; pass it on later run, read, write, and close calls for deterministic reuse. \
            With no terminal_id, reuses this session's most recent terminal. Set new_tab only for a truly parallel interactive process. \
            Close the terminal with close_terminal when it is no longer needed. \
            For non-interactive commands with captured output, prefer run_command.

            When the terminal is waiting for input:
            - For non-sensitive input (confirmations, choices, non-secret text): \
            send it directly via write_terminal_input.
            - For sensitive input (passwords, tokens, secrets): NEVER ask for or handle \
            the value yourself. Use prompt_user to notify the user, e.g. \
            "The terminal is waiting for your password. Please type it directly in the terminal." \
            with options like "Done" / "No, abort". The user types the secret themselves.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run in terminal: {command}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(JSON_COMMAND, TYPE_STRING, "The command to run in the terminal"),
            Param.optional(JSON_TERMINAL_ID, TYPE_STRING,
                "Stable ID returned by an earlier run_in_terminal call (preferred for deterministic reuse)"),
            Param.optional(JSON_TAB_NAME, TYPE_STRING,
                "Name for this session's terminal tab. If no terminal_id is supplied, reuses a matching tab or creates one"),
            Param.optional(JSON_NEW_TAB, TYPE_BOOLEAN,
                "If true, create a separate terminal. Cannot be combined with terminal_id; the limit is 3 per MCP session"),
            Param.optional(JSON_SHELL, TYPE_STRING,
                "Shell to use (e.g., 'bash', 'zsh'). If omitted, uses the default shell")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String command = args.get(JSON_COMMAND).getAsString();
        String terminalId = optionalSelector(args, JSON_TERMINAL_ID);
        String tabName = optionalSelector(args, JSON_TAB_NAME);
        boolean newTab = args.has(JSON_NEW_TAB) && args.get(JSON_NEW_TAB).getAsBoolean();
        if (newTab && terminalId != null) {
            return "Error: terminal_id cannot be combined with new_tab=true.";
        }
        String shell = optionalSelector(args, JSON_SHELL);
        String ownerId = currentOwnerId();

        // Flush editor buffers so the command sees the latest content.
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments());

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
                var manager = managerClass.getMethod(GET_INSTANCE_METHOD, Project.class)
                    .invoke(null, project);

                var result = getOrCreateTerminalWidget(
                    ownerId, managerClass, manager, terminalId, tabName, newTab, shell, command);
                sendTerminalCommand(result.widget(), command);

                resultFuture.complete(
                    "Command sent to " + (result.reused() ? "reused" : "new")
                        + " terminal '" + result.tabName() + "'.\n"
                        + "terminal_id: " + result.terminalId() + "\n"
                        + "command: " + command
                        + "\n\nReuse this terminal_id with run_in_terminal, read_terminal_output, "
                        + "write_terminal_input, and close_terminal.");
            } catch (ClassNotFoundException e) {
                resultFuture.complete(
                    "Error: Terminal plugin not available. Use run_command tool instead.");
            } catch (IllegalStateException e) {
                resultFuture.complete(formatCapacityError(e));
            } catch (Exception e) {
                LOG.warn("Failed to open terminal", e);
                resultFuture.complete(
                    "Error: Failed to open terminal: " + e.getMessage()
                        + ". Use run_command tool instead.");
            }
        });

        try {
            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal opened (response timed out, but command was likely sent).";
        } catch (Exception e) {
            return "Terminal opened (response timed out, but command was likely sent).";
        }
    }

    static @NotNull String formatCapacityError(@NotNull IllegalStateException error) {
        return "Error: " + error.getMessage();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
