package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a command in an integrated terminal owned by the current MCP session.
 */
public final class RunInTerminalTool extends TerminalTool {

    private static final String JSON_COMMAND = "command";
    private static final String JSON_NEW_TAB = "new_tab";
    private static final String JSON_SHELL = "shell";
    private static final String PARAM_WAIT_SECONDS = "wait_seconds";
    private static final String JSON_MAX_LINES = "max_lines";

    /**
     * Appended to the result of a call without {@code wait_seconds}: steers the caller to wait in one call next time.
     */
    private static final String WAIT_HINT = """


        Next time pass wait_seconds=<max> to run_in_terminal: it waits for the \
        command to finish and returns the exit code and output in this one call, so no sleep and no separate \
        read_terminal_output is needed.""";

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
            For non-interactive commands with captured output, prefer run_command. \
            To run a command and get its result, pass wait_seconds=<max>: this single call waits for the command to \
            finish and returns the exit code and output, so do NOT follow it with a sleep and a separate \
            read_terminal_output. Without wait_seconds the call returns immediately and you would have to poll \
            (read_terminal_output with wait_seconds and wait_for also blocks instead of sleeping).

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
                "Shell to use (e.g., 'bash', 'zsh'). If omitted, uses the default shell"),
            Param.optional(PARAM_WAIT_SECONDS, TYPE_INTEGER,
                "PREFERRED for commands that finish on their own: wait up to this many seconds (max "
                    + McpRequestDeadline.MAX_TIMEOUT_SECONDS + ") for the command to finish and return its exit "
                    + "code and output in this same call. A unique completion marker is appended to the command "
                    + "for you (bash/zsh/sh, fish, PowerShell and cmd are supported). If it times out, the result "
                    + "tells you how to keep waiting. Omit only for interactive or never-ending processes"),
            Param.optional(JSON_MAX_LINES, TYPE_INTEGER,
                "With wait_seconds: maximum lines of output to return from the end of the buffer "
                    + "(default: 50). Use 0 for the full buffer")
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

        TerminalWaitArgs.Parsed parsed = TerminalWaitArgs.parse(args, false);
        if (parsed.error() != null) return parsed.error();
        Wait wait = parsed.requested() ? prepareWait(parsed.seconds(), shell) : null;
        String sendText = withCompletionMarker(command, wait);

        // Flush editor buffers so the command sees the latest content.
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments());

        CompletableFuture<Sent> resultFuture = new CompletableFuture<>();
        TerminalRequest request = new TerminalRequest(
            ownerId, terminalId, tabName, newTab, shell, command, sendText, wait != null);
        EdtUtil.invokeLater(() -> resultFuture.complete(sendOnEdt(request)));

        Sent sent = awaitSent(resultFuture);
        if (sent == null) return "Terminal opened (response timed out, but command was likely sent).";
        if (sent.widget() == null) return sent.message();
        if (wait == null) return sent.message() + startupNote(sent.widget());
        Wait withId = new Wait(wait.seconds(), wait.token(), wait.shell(), sent.terminalId());
        return sent.message() + "\n\n" + awaitCompletion(sent.widget(), withId, parsed.notice(), maxLines(args));
    }

    /**
     * Resolves the shell and, when its family is recognised, a completion-marker token for the requested wait.
     */
    private @NotNull Wait prepareWait(int seconds, @Nullable String shell) {
        String effectiveShell = resolveShell(shell);
        TerminalDoneMarker.Shell family = TerminalDoneMarker.detect(effectiveShell);
        String token = family == null ? null : TerminalDoneMarker.newToken();
        return new Wait(seconds, token, effectiveShell, null);
    }

    /**
     * {@code command} with the completion marker appended, or unchanged when no wait or no marker was prepared.
     */
    private static @NotNull String withCompletionMarker(@NotNull String command, @Nullable Wait wait) {
        if (wait == null || wait.token() == null) return command;
        TerminalDoneMarker.Shell family = TerminalDoneMarker.detect(wait.shell());
        return family == null ? command : command + TerminalDoneMarker.suffix(family, wait.token());
    }

    /**
     * Opens or reuses the terminal and sends the command. Must run on the EDT.
     */
    private @NotNull Sent sendOnEdt(@NotNull TerminalRequest request) {
        try {
            var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
            var manager = managerClass.getMethod(GET_INSTANCE_METHOD, Project.class)
                .invoke(null, project);

            var result = getOrCreateTerminalWidget(request.ownerId(), managerClass, manager,
                request.terminalId(), request.tabName(), request.newTab(), request.shell(), request.command());
            sendTerminalCommand(result.widget(), request.sendText());

            return new Sent(
                "Command sent to " + (result.reused() ? "reused" : "new")
                    + " terminal '" + result.tabName() + "'.\n"
                    + "terminal_id: " + result.terminalId() + "\n"
                    + "command: " + redactSecrets(request.command())
                    + "\n\nReuse this terminal_id with run_in_terminal, read_terminal_output, "
                    + "write_terminal_input, and close_terminal."
                    + (request.waiting() ? "" : WAIT_HINT),
                result.widget(), result.terminalId());
        } catch (ClassNotFoundException e) {
            return Sent.plain("Error: Terminal plugin not available. Use run_command tool instead.");
        } catch (IllegalStateException e) {
            return Sent.plain(formatCapacityError(e));
        } catch (Exception e) {
            LOG.warn("Failed to open terminal", e);
            return Sent.plain("Error: Failed to open terminal: " + e.getMessage() + ". Use run_command tool instead.");
        }
    }

    /**
     * Waits for the EDT send to finish; null when it timed out or was interrupted.
     */
    private static @Nullable Sent awaitSent(@NotNull CompletableFuture<Sent> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Everything the EDT block needs to open or reuse a terminal and send the command.
     */
    private record TerminalRequest(
        @NotNull String ownerId, @Nullable String terminalId, @Nullable String tabName, boolean newTab,
        @Nullable String shell, @NotNull String command, @NotNull String sendText, boolean waiting
    ) {
    }

    /**
     * Waits for the command sent with a completion marker and returns its exit code and the tail of the output,
     * so the caller needs neither a sleep nor a follow-up read. On timeout it says how to keep waiting.
     */
    private @NotNull String awaitCompletion(
        @NotNull Object widget, @NotNull Wait wait, @Nullable String clampNotice, int maxLines
    ) {
        Pattern pattern = wait.token() == null ? null : Pattern.compile(TerminalDoneMarker.regex(wait.token()));
        String note = McpRequestDeadline.prependNotice(
            clampNotice, waitForWidget(widget, pattern, wait.seconds()).note());
        CharSequence text = readWidgetText(widget);
        return TerminalWaitResultFormatter.format(
            note, wait.token(), wait.shell(), wait.terminalId(), text == null ? "" : text.toString(), maxLines);
    }

    private static int maxLines(@NotNull JsonObject args) {
        return args.has(JSON_MAX_LINES) ? args.get(JSON_MAX_LINES).getAsInt() : DEFAULT_MAX_LINES;
    }

    /**
     * A requested wait: how long, and the marker token that signals completion (null when the shell is not
     * recognised and no marker can be added).
     */
    private record Wait(int seconds, @Nullable String token, @Nullable String shell, @Nullable String terminalId) {
    }

    /**
     * "Command sent" only means the text was handed to the terminal widget. If the shell behind it has not
     * started, nothing runs and nothing is printed, which used to look identical to a command that was running.
     * Waits briefly for any output and says so when there is none.
     */
    private @NotNull String startupNote(@NotNull Object widget) {
        String warning = dormantTerminalWarning(awaitTerminalOutput(widget, STARTUP_WAIT_MS));
        return warning == null ? "" : "\n\n" + warning;
    }

    /**
     * What the EDT block produced: the message for the agent, and the widget when a command was actually sent.
     */
    private record Sent(@NotNull String message, @Nullable Object widget, @Nullable String terminalId) {
        static Sent plain(@NotNull String message) {
            return new Sent(message, null, null);
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
