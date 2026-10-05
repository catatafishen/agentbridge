package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.services.McpCallContext;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base for terminal tools. Integrated terminal resources are resolved by stable
 * {@code terminal_id} inside the current MCP owner, never by the globally selected IDE tab.
 */
// S112: These methods wrap reflection-based IntelliJ terminal API calls — generic exceptions are intentional
@SuppressWarnings("java:S112")
public abstract class TerminalTool extends Tool {

    protected static final Logger LOG = Logger.getInstance(TerminalTool.class);
    protected static final String JSON_TERMINAL_ID = "terminal_id";
    protected static final String JSON_TAB_NAME = "tab_name";
    protected static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    protected static final String GET_INSTANCE_METHOD = "getInstance";
    protected static final String TERMINAL_MANAGER_CLASS =
        "org.jetbrains.plugins.terminal.TerminalToolWindowManager";
    protected static final String TERMINAL_WIDGET_CLASS =
        "com.intellij.terminal.ui.TerminalWidget";
    protected static final String TTY_CONNECTOR_CLASS = "com.jediterm.terminal.TtyConnector";
    protected static final String FIND_WIDGET_BY_CONTENT_METHOD = "findWidgetByContent";
    protected static final int DEFAULT_MAX_LINES = 50;
    /**
     * How long run_in_terminal waits for the shell to print anything before warning that it may be dormant.
     */
    protected static final long STARTUP_WAIT_MS = 3_000;
    protected static final long POLL_INTERVAL_MS = 200;

    protected TerminalTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.TERMINAL;
    }

    /**
     * Resolve human-readable escape sequences to actual characters.
     * Supports: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down},
     * {left}, {right}, \n, \t
     */
    protected static String resolveInputEscapes(String input) {
        return input
            .replace("{enter}", "\r")
            .replace("{tab}", "\t")
            .replace("{ctrl-c}", "\u0003")
            .replace("{ctrl-d}", "\u0004")
            .replace("{ctrl-z}", "\u001A")
            .replace("{escape}", "\u001B")
            .replace("{up}", "\u001B[A")
            .replace("{down}", "\u001B[B")
            .replace("{right}", "\u001B[C")
            .replace("{left}", "\u001B[D")
            .replace("{backspace}", "\u007F")
            .replace("\\n", "\n")
            .replace("\\t", "\t");
    }

    protected static String describeInput(String raw, String resolved) {
        if (raw.contains("{") || raw.contains("\\")) {
            return "'" + raw + "' (" + resolved.length() + " chars)";
        }
        return "'" + raw + "'";
    }

    protected record TerminalWidgetResult(
        Object widget,
        String terminalId,
        String tabName,
        boolean reused
    ) {
    }

    protected TerminalWidgetResult getOrCreateTerminalWidget(
        @NotNull String ownerId,
        Class<?> managerClass,
        Object manager,
        String terminalId,
        String tabName,
        boolean newTab,
        String shell,
        String command
    ) throws Exception {
        AgentTabTracker tracker = AgentTabTracker.getInstance(project);
        if (!newTab) {
            AgentTabTracker.AgentTerminal reusable =
                tracker.findOwnedTerminal(ownerId, terminalId, tabName);
            if (reusable != null) {
                Object widget = findTerminalWidgetByContent(managerClass, reusable.content());
                if (widget == null) {
                    throw new IllegalStateException(
                        "Owned terminal '" + reusable.terminalId()
                            + "' has no terminal widget. Close it and create a new terminal.");
                }
                LOG.info("Reusing owned terminal " + reusable.terminalId()
                    + " ('" + reusable.displayName() + "')");
                return new TerminalWidgetResult(
                    widget, reusable.terminalId(), reusable.displayName(), true);
            }
            if (terminalId != null) {
                throw new IllegalStateException(
                    "No terminal owned by this MCP session matches terminal_id '"
                        + terminalId + "'. Use list_terminals to see this session's terminals.");
            }
        }

        if (!tracker.hasOpenTerminalCapacity(ownerId)) {
            throw new IllegalStateException(
                "Agent terminal limit reached ("
                    + AgentTabTracker.MAX_OPEN_AGENT_TERMINALS + " per MCP session, "
                    + tracker.getGlobalCap() + " per project). "
                    + "Reuse an existing terminal or close one with close_terminal.");
        }

        String title = tabName != null ? tabName : "Agent: " + truncateForTitle(command);
        List<String> shellCommand = shell != null ? List.of(shell) : null;
        var createSession = managerClass.getMethod(
            "createNewSession",
            String.class,
            String.class,
            List.class,
            boolean.class,
            boolean.class
        );
        // Avoid stealing the chat caret when the AgentBridge chat tool window is active.
        boolean requestFocus = !PsiBridgeService.isChatToolWindowActive(project);
        // Start the shell now rather than when the Terminal tool window is first shown. With deferral, a
        // terminal opened while the chat has focus stays dormant: the command is queued but never runs, and
        // read_terminal_output shows an empty buffer until something finally reveals the tool window.
        Object widget = createSession.invoke(
            manager, project.getBasePath(), title, shellCommand, requestFocus, false);

        Content content = findTerminalContentForWidget(managerClass, widget);
        if (content == null) {
            throw new IllegalStateException(
                "Terminal session was created, but its IDE content could not be resolved");
        }

        String createdTerminalId = tracker.trackTerminal(ownerId, content);
        String displayName = content.getDisplayName() != null ? content.getDisplayName() : title;
        return new TerminalWidgetResult(widget, createdTerminalId, displayName, false);
    }

    /**
     * Send a command to a TerminalWidget, using the interface method to avoid
     * {@link IllegalAccessException}.
     */
    protected void sendTerminalCommand(Object widget, String command) throws Exception {
        var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
        try {
            widgetInterface.getMethod("sendCommandToExecute", String.class)
                .invoke(widget, command);
        } catch (NoSuchMethodException e) {
            widget.getClass().getMethod("executeCommand", String.class)
                .invoke(widget, command);
        }
    }

    /**
     * Warning appended to the {@code run_in_terminal} result when the terminal shows nothing at all after the
     * command was sent: not even the echoed command or a prompt. A live shell always prints at least that, so an
     * empty buffer means the shell has not started and the command is queued rather than running.
     * Returns null when the terminal is alive.
     */
    protected static @Nullable String dormantTerminalWarning(@Nullable CharSequence bufferText) {
        if (!isBlank(bufferText)) return null;
        return "WARNING: the terminal has produced no output yet, so its shell may not have started and the "
            + "command may be queued, not running. Check with read_terminal_output. If it stays empty, bring the "
            + "Terminal tool window into view, or run the command with run_command instead.";
    }

    /**
     * Polls the widget's text until it is non-blank or {@code timeoutMs} elapses, and returns the last text seen.
     * Must not be called on the EDT: each read hops onto it.
     */
    protected @Nullable CharSequence awaitTerminalOutput(@NotNull Object widget, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        CharSequence last = readWidgetText(widget);
        while (isBlank(last) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
            last = readWidgetText(widget);
        }
        return last;
    }

    private static boolean isBlank(@Nullable CharSequence text) {
        return text == null || text.toString().isBlank();
    }

    protected @Nullable CharSequence readWidgetText(@NotNull Object widget) {
        CompletableFuture<CharSequence> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var getText = Class.forName(TERMINAL_WIDGET_CLASS).getMethod("getText");
                future.complete((CharSequence) getText.invoke(widget));
            } catch (Exception e) {
                future.complete(null);
            }
        });
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    protected @Nullable Object findTerminalWidgetByContent(
        @NotNull Class<?> managerClass,
        @NotNull Content content
    ) {
        try {
            var findWidget = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD, Content.class);
            return findWidget.invoke(null, content);
        } catch (Exception e) {
            LOG.warn("Could not resolve terminal widget for " + content.getDisplayName(), e);
            return null;
        }
    }

    protected @Nullable Content findTerminalContentForWidget(
        @NotNull Class<?> managerClass,
        @NotNull Object widget
    ) {
        var toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
        if (toolWindow == null) return null;

        for (Content content : toolWindow.getContentManager().getContents()) {
            Object candidate = findTerminalWidgetByContent(managerClass, content);
            if (candidate == widget) {
                return content;
            }
        }
        return null;
    }

    protected @Nullable AgentTabTracker.AgentTerminal resolveOwnedTerminal(
        @NotNull String ownerId,
        @Nullable String terminalId,
        @Nullable String tabName
    ) {
        return AgentTabTracker.getInstance(project)
            .findOwnedTerminal(ownerId, terminalId, tabName);
    }

    protected static @Nullable String optionalSelector(
        @NotNull JsonObject args,
        @NotNull String key
    ) {
        if (!args.has(key) || args.get(key).isJsonNull()) return null;
        String value = args.get(key).getAsString();
        return value.isBlank() ? null : value;
    }

    protected static @NotNull String selectorDescription(
        @Nullable String terminalId,
        @Nullable String tabName
    ) {
        if (terminalId != null) return "terminal_id '" + terminalId + "'";
        if (tabName != null) return "tab_name '" + tabName + "'";
        return "the caller's most recent terminal";
    }

    protected static @NotNull String currentOwnerId() {
        return McpCallContext.currentOrFallback();
    }

    protected void readTerminalText(
        CompletableFuture<String> resultFuture,
        AgentTabTracker.AgentTerminal terminal,
        int maxLines
    ) throws Exception {
        Content targetContent = terminal.content();
        var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
        var findWidgetByContent =
            managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD, Content.class);
        Object widget = findWidgetByContent.invoke(null, targetContent);
        if (widget == null) {
            resultFuture.complete(
                "No terminal widget found for terminal_id '" + terminal.terminalId() + "'.");
            return;
        }

        try {
            var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
            var getText = widgetInterface.getMethod("getText");
            CharSequence text = (CharSequence) getText.invoke(widget);
            String fullOutput = text != null ? text.toString().strip() : "";
            if (fullOutput.isEmpty()) {
                resultFuture.complete(
                    "Terminal '" + terminal.displayName() + "' [terminal_id="
                        + terminal.terminalId() + "] has no output.");
                return;
            }

            String output = tailLines(fullOutput, maxLines);
            resultFuture.complete(
                "Terminal '" + terminal.displayName() + "' [terminal_id="
                    + terminal.terminalId() + "] output:\n" + output);
        } catch (NoSuchMethodException e) {
            resultFuture.complete(
                "getText() not available on this terminal type ("
                    + widget.getClass().getSimpleName()
                    + "). Terminal output reading not supported.");
        }
    }

    /**
     * Return the last {@code maxLines} lines of the text. If maxLines &le; 0, return the
     * full text (subject to character truncation via {@link ToolUtils#truncateOutput}).
     */
    protected static String tailLines(String text, int maxLines) {
        if (maxLines <= 0) {
            return ToolUtils.truncateOutput(text);
        }
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) {
            return text;
        }
        int start = lines.length - maxLines;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    protected void appendOwnedTerminalTabs(StringBuilder result) {
        List<AgentTabTracker.AgentTerminal> terminals =
            AgentTabTracker.getInstance(project).listOpenTerminals(currentOwnerId());
        result.append("AgentBridge terminals owned by this MCP session:\n");
        if (terminals.isEmpty()) {
            result.append("  (none)\n");
            return;
        }

        for (int i = 0; i < terminals.size(); i++) {
            AgentTabTracker.AgentTerminal terminal = terminals.get(i);
            boolean mostRecent = i == terminals.size() - 1;
            result.append(mostRecent ? "  ▸ " : "  • ")
                .append(terminal.displayName())
                .append(" [terminal_id=")
                .append(terminal.terminalId())
                .append("]\n");
        }
    }

    protected void appendDefaultShell(StringBuilder result) {
        try {
            var settingsClass =
                Class.forName("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider");
            var getInstance = settingsClass.getMethod(GET_INSTANCE_METHOD, Project.class);
            var settings = getInstance.invoke(null, project);
            var getShellPath = settings.getClass().getMethod("getShellPath");
            String defaultShell = (String) getShellPath.invoke(settings);
            result.append("\nIntelliJ default shell: ").append(defaultShell);
        } catch (Exception e) {
            result.append("\nCould not determine IntelliJ default shell.");
        }
    }

    protected static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }
}
