package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command in IntelliJ's integrated terminal.
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
        return "Run a command in IntelliJ's integrated terminal. Returns terminal output. " +
            "Use for interactive commands that need stdin (prompts, REPL). For non-interactive commands with captured output, prefer run_command.";
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
    public @NotNull String permissionTemplate() {
        return "Run in terminal: {command}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(JSON_COMMAND, TYPE_STRING, "The command to run in the terminal"),
            Param.optional(JSON_TAB_NAME, TYPE_STRING, "Name for the terminal tab. If omitted, reuses the most recent agent-created tab or creates a new one"),
            Param.optional(JSON_NEW_TAB, TYPE_BOOLEAN, "If true, always create a new terminal tab instead of reusing an existing one"),
            Param.optional(JSON_SHELL, TYPE_STRING, "Shell to use (e.g., 'bash', 'zsh'). If omitted, uses the default shell")
        );
    }

    @Override
    public @Nullable String detectPermissionAbuse(@Nullable Object toolCall) {
        if (!(toolCall instanceof JsonObject jsonToolCall)) return null;
        String command = extractCommand(jsonToolCall);
        if (command == null) return null;
        return ToolUtils.detectTerminalAbuseType(command);
    }

    private static @Nullable String extractCommand(@NotNull JsonObject toolCall) {
        for (String wrapper : new String[]{"parameters", "arguments", "input"}) {
            if (toolCall.has(wrapper) && toolCall.get(wrapper).isJsonObject()) {
                var nested = toolCall.getAsJsonObject(wrapper);
                if (nested.has(JSON_COMMAND) && nested.get(JSON_COMMAND).isJsonPrimitive()) {
                    return nested.get(JSON_COMMAND).getAsString().toLowerCase().trim();
                }
            }
        }
        return null;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String command = args.get(JSON_COMMAND).getAsString();

        // Runtime guard: reject abuse patterns even if the pre-check was skipped
        String abuseType = ToolUtils.detectTerminalAbuseType(command);
        if (abuseType != null) return ToolUtils.getCommandAbuseMessage(abuseType, "run_in_terminal");

        // Soft reprimand: command runs but output is prefixed with a nudge toward the better tool
        String reprimand = ToolUtils.getTerminalReprimand(command);

        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;
        boolean newTab = args.has(JSON_NEW_TAB) && args.get(JSON_NEW_TAB).getAsBoolean();
        String shell = args.has(JSON_SHELL) ? args.get(JSON_SHELL).getAsString() : null;

        // Flush all editor buffers to disk so terminal commands see current content
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments());

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
                var manager = managerClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);

                var result = getOrCreateTerminalWidget(managerClass, manager, tabName, newTab, shell, command);
                sendTerminalCommand(result.widget(), command);

                resultFuture.complete("Command sent to terminal '" + result.tabName() + "': " + command +
                    "\n\nNote: Use read_terminal_output to read terminal content, or run_command if you need output returned directly.");

            } catch (ClassNotFoundException e) {
                resultFuture.complete("Terminal plugin not available. Use run_command tool instead.");
            } catch (Exception e) {
                LOG.warn("Failed to open terminal", e);
                resultFuture.complete("Failed to open terminal: " + e.getMessage() + ". Use run_command tool instead.");
            }
        });

        try {
            String result = resultFuture.get(10, TimeUnit.SECONDS);
            return reprimand != null ? reprimand + "\n\n" + result : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal opened (response timed out, but command was likely sent).";
        } catch (Exception e) {
            return "Terminal opened (response timed out, but command was likely sent).";
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
