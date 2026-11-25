package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads output from a terminal owned by the current MCP session.
 */
public final class ReadTerminalOutputTool extends TerminalTool {

    private static final String PARAM_MAX_LINES = "max_lines";
    private static final String PARAM_WAIT_SECONDS = TerminalWaitArgs.WAIT_SECONDS;
    private static final String PARAM_WAIT_FOR = TerminalWaitArgs.WAIT_FOR;
    private static final long READ_TIMEOUT_SECONDS = 10;

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
            + "available as an owner-scoped compatibility selector. "
            + "Prefer run_in_terminal with wait_seconds, which waits for the command and returns its result in one "
            + "call. Use this tool to follow a command that is still running (for example after a wait timed out) "
            + "or to inspect a terminal: wait_seconds blocks instead of a separate sleep, and wait_for returns as "
            + "soon as the output matches a regex.";
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
                "Maximum lines from the end of the buffer (default: 50). Use 0 for the full buffer"),
            Param.optional(PARAM_WAIT_SECONDS, TYPE_INTEGER,
                "Block up to this many seconds before reading (max 170) instead of sleeping separately. "
                    + "Returns early when wait_for matches or, without wait_for, when the output changed "
                    + "and then stayed unchanged for 2s. Default: read immediately"),
            Param.optional(PARAM_WAIT_FOR, TYPE_STRING,
                "Regex; with wait_seconds, return as soon as the terminal text matches it "
                    + "(for example \"BUILD SUCCESSFUL\" or \"Listening on\")")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String terminalId = optionalSelector(args, JSON_TERMINAL_ID);
        String tabName = optionalSelector(args, JSON_TAB_NAME);
        String ownerId = currentOwnerId();
        int maxLines =
            args.has(PARAM_MAX_LINES) ? args.get(PARAM_MAX_LINES).getAsInt() : DEFAULT_MAX_LINES;

        TerminalWaitArgs.Parsed wait = TerminalWaitArgs.parse(args, true);
        if (wait.error() != null) return wait.error();
        String note = wait.requested() ? waitBeforeRead(wait, ownerId, terminalId, tabName) : null;

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
                CompletableFuture<String> read = new CompletableFuture<>();
                readTerminalText(read, terminal, maxLines);
                read.thenAccept(text -> resultFuture.complete(note == null ? text : note + "\n\n" + text));
            } catch (Exception e) {
                LOG.warn("Failed to read terminal", e);
                resultFuture.complete(err("Failed to read terminal: " + e.getMessage()));
            }
        });

        try {
            return resultFuture.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal read timed out.";
        } catch (Exception e) {
            return "Terminal read timed out.";
        }
    }

    /**
     * Blocks until the requested wait ends and describes why; null when no owned terminal matches the selector
     * (the read that follows reports that).
     */
    private @Nullable String waitBeforeRead(
        @NotNull TerminalWaitArgs.Parsed wait, @NotNull String ownerId,
        @Nullable String terminalId, @Nullable String tabName
    ) {
        AgentTabTracker.AgentTerminal target = resolveOwnedTerminal(ownerId, terminalId, tabName);
        if (target == null) return null;
        WaitResult result = waitForTerminal(target, wait.pattern(), wait.seconds());
        String body = result.exitCode() == null
            ? result.note()
            : result.note() + "\nExit code: " + result.exitCode();
        return McpRequestDeadline.prependNotice(wait.notice(), body);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
