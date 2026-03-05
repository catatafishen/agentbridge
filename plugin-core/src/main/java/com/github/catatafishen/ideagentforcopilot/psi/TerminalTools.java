package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Terminal tool handlers: run_in_terminal, write_terminal_input, read_terminal_output, list_terminals.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
final class TerminalTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(TerminalTools.class);
    private static final String JSON_TAB_NAME = "tab_name";
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    private static final String GET_INSTANCE_METHOD = "getInstance";
    private static final String OS_NAME_PROPERTY = "os.name";
    private static final String TERMINAL_MANAGER_CLASS = "org.jetbrains.plugins.terminal.TerminalToolWindowManager";
    private static final String TERMINAL_WIDGET_CLASS = "com.intellij.terminal.ui.TerminalWidget";
    private static final String FIND_WIDGET_BY_CONTENT_METHOD = "findWidgetByContent";
    private static final String TTY_CONNECTOR_CLASS = "com.jediterm.terminal.TtyConnector";
    private static final int DEFAULT_MAX_LINES = 50;

    TerminalTools(Project project) {
        super(project);
        if (isPluginInstalled("org.jetbrains.plugins.terminal")) {
            register("run_in_terminal", this::runInTerminal);
            register("write_terminal_input", this::writeTerminalInput);
            register("read_terminal_output", this::readTerminalOutput);
            register("list_terminals", args -> listTerminals());
            LOG.info("Terminal plugin detected — terminal tools registered");
        }
    }

    private String runInTerminal(JsonObject args) {
        String command = args.get("command").getAsString();
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;
        boolean newTab = args.has("new_tab") && args.get("new_tab").getAsBoolean();
        String shell = args.has("shell") ? args.get("shell").getAsString() : null;

        // Flush all editor buffers to disk so terminal commands see current content
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()));

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
                var manager = managerClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);

                var result = getOrCreateTerminalWidget(managerClass, manager, tabName, newTab, shell, command);
                sendTerminalCommand(result.widget, command);

                resultFuture.complete("Command sent to terminal '" + result.tabName + "': " + command +
                    "\n\nNote: Use read_terminal_output to read terminal content, or run_command if you need output returned directly.");

            } catch (ClassNotFoundException e) {
                resultFuture.complete("Terminal plugin not available. Use run_command tool instead.");
            } catch (Exception e) {
                LOG.warn("Failed to open terminal", e);
                resultFuture.complete("Failed to open terminal: " + e.getMessage() + ". Use run_command tool instead.");
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

    /**
     * Send raw text/keystrokes to a running terminal without appending Enter.
     * Useful for answering prompts (y/n), sending Ctrl-C, or typing partial input.
     */
    private String writeTerminalInput(JsonObject args) {
        String input = args.get("input").getAsString();
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

    /**
     * Find the best matching terminal widget: by tab name if given, otherwise the selected tab.
     */
    private Object findTerminalWidget(Class<?> managerClass, String tabName) throws Exception {
        if (tabName != null) {
            return findTerminalWidgetByTabName(managerClass, tabName);
        }
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
        if (toolWindow == null) return null;
        var selected = toolWindow.getContentManager().getSelectedContent();
        if (selected == null) return null;
        var findWidget = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD,
            com.intellij.ui.content.Content.class);
        return findWidget.invoke(null, selected);
    }

    /**
     * Resolve human-readable escape sequences to actual characters.
     * Supports: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, \n, \t
     */
    private static String resolveInputEscapes(String input) {
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

    private static String describeInput(String raw, String resolved) {
        if (raw.contains("{") || raw.contains("\\")) {
            return "'" + raw + "' (" + resolved.length() + " chars)";
        }
        return "'" + raw + "'";
    }

    private record TerminalWidgetResult(Object widget, String tabName) {
    }

    private TerminalWidgetResult getOrCreateTerminalWidget(Class<?> managerClass, Object manager,
                                                           String tabName, boolean newTab,
                                                           String shell, String command) throws Exception {
        // Try to reuse existing terminal tab
        if (tabName != null && !newTab) {
            Object widget = findTerminalWidgetByTabName(managerClass, tabName);
            if (widget != null) {
                return new TerminalWidgetResult(widget, tabName);
            }
        }

        // Create new tab
        String title = tabName != null ? tabName : "Agent: " + truncateForTitle(command);
        List<String> shellCommand = shell != null ? List.of(shell) : null;
        var createSession = managerClass.getMethod("createNewSession",
            String.class, String.class, List.class, boolean.class, boolean.class);
        Object widget = createSession.invoke(manager, project.getBasePath(), title, shellCommand, true, true);
        return new TerminalWidgetResult(widget, title + " (new)");
    }

    /**
     * Send a command to a TerminalWidget, using the interface method to avoid IllegalAccessException.
     */
    private void sendTerminalCommand(Object widget, String command) throws Exception {
        var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
        try {
            widgetInterface.getMethod("sendCommandToExecute", String.class).invoke(widget, command);
        } catch (NoSuchMethodException e) {
            widget.getClass().getMethod("executeCommand", String.class).invoke(widget, command);
        }
    }

    /**
     * Find a TerminalWidget by tab name using Content userData.
     */
    private Object findTerminalWidgetByTabName(Class<?> managerClass, String tabName) {
        try {
            var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (toolWindow == null) return null;

            var findWidgetByContent = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD,
                com.intellij.ui.content.Content.class);

            for (var content : toolWindow.getContentManager().getContents()) {
                String displayName = content.getDisplayName();
                if (displayName != null && displayName.contains(tabName)) {
                    Object widget = findWidgetByContent.invoke(null, content);
                    if (widget != null) {
                        LOG.info("Reusing terminal tab '" + displayName + "'");
                        return widget;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not find terminal tab: " + tabName, e);
        }

        return null;
    }

    /**
     * Read terminal output from a named tab using TerminalWidget.getText().
     */
    private String readTerminalOutput(JsonObject args) {
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;
        int maxLines = args.has("max_lines") ? args.get("max_lines").getAsInt() : DEFAULT_MAX_LINES;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                com.intellij.ui.content.Content targetContent = resolveTerminalContent(tabName);
                if (targetContent == null) {
                    resultFuture.complete(tabName != null
                        ? "No terminal tab found matching '" + tabName + "'. Use list_terminals to see available tabs."
                        : "No terminal tab is open. Use run_in_terminal to start one.");
                    return;
                }
                readTerminalText(resultFuture, targetContent, maxLines);
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

    /**
     * Resolve terminal content by tab name (fuzzy match) or return the selected tab.
     * Returns null if no matching tab is found.
     */
    private com.intellij.ui.content.Content resolveTerminalContent(String tabName) {
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
        if (toolWindow == null) return null;

        var contentManager = toolWindow.getContentManager();
        if (tabName == null) {
            return contentManager.getSelectedContent();
        }

        for (var content : contentManager.getContents()) {
            String name = content.getDisplayName();
            if (name != null && name.contains(tabName)) {
                return content;
            }
        }
        return null;
    }

    private void readTerminalText(CompletableFuture<String> resultFuture,
                                  com.intellij.ui.content.Content targetContent,
                                  int maxLines) throws Exception {
        var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
        var findWidgetByContent = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD,
            com.intellij.ui.content.Content.class);
        Object widget = findWidgetByContent.invoke(null, targetContent);
        if (widget == null) {
            resultFuture.complete("No terminal widget found for tab '" + targetContent.getDisplayName() +
                "'. The auto-created default tab may not be readable — use agent-created tabs instead.");
            return;
        }

        try {
            var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
            var getText = widgetInterface.getMethod("getText");
            CharSequence text = (CharSequence) getText.invoke(widget);
            String fullOutput = text != null ? text.toString().strip() : "";
            if (fullOutput.isEmpty()) {
                resultFuture.complete("Terminal '" + targetContent.getDisplayName() + "' has no output.");
                return;
            }

            String output = tailLines(fullOutput, maxLines);
            String tabDisplayName = targetContent.getDisplayName();
            resultFuture.complete("Terminal '" + tabDisplayName + "' output:\n" + output);
        } catch (NoSuchMethodException e) {
            resultFuture.complete("getText() not available on this terminal type (" +
                widget.getClass().getSimpleName() + "). Terminal output reading not supported.");
        }
    }

    /**
     * Return the last {@code maxLines} lines of the text. If maxLines &le; 0, return the full text
     * (subject to character truncation via {@link ToolUtils#truncateOutput}).
     */
    private static String tailLines(String text, int maxLines) {
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

    private String listTerminals() {
        StringBuilder result = new StringBuilder();

        appendOpenTerminalTabs(result);
        appendAvailableShells(result);
        appendDefaultShell(result);

        result.append("\n\nTip: Use run_in_terminal with tab_name to reuse an existing tab, or new_tab=true to force a new one.");
        return result.toString();
    }

    private void appendOpenTerminalTabs(StringBuilder result) {
        result.append("Open terminal tabs:\n");
        try {
            var toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            var toolWindow = toolWindowManager.getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (toolWindow != null) {
                var contentManager = toolWindow.getContentManager();
                var contents = contentManager.getContents();
                if (contents.length == 0) {
                    result.append("  (none)\n");
                } else {
                    for (var content : contents) {
                        String name = content.getDisplayName();
                        boolean selected = content == contentManager.getSelectedContent();
                        result.append(selected ? "  ▸ " : "  • ").append(name).append("\n");
                    }
                }
            } else {
                result.append("  (Terminal tool window not available)\n");
            }
        } catch (Exception e) {
            result.append("  (Could not list open terminals)\n");
        }
    }

    private void appendAvailableShells(StringBuilder result) {
        result.append("\nAvailable shells:\n");
        String os = System.getProperty(OS_NAME_PROPERTY, "").toLowerCase();
        if (os.contains("win")) {
            checkShell(result, "PowerShell", "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            checkShell(result, "PowerShell 7", "C:\\Program Files\\PowerShell\\7\\pwsh.exe");
            checkShell(result, "Command Prompt", "C:\\Windows\\System32\\cmd.exe");
            checkShell(result, "Git Bash", "C:\\Program Files\\Git\\bin\\bash.exe");
            checkShell(result, "WSL", "C:\\Windows\\System32\\wsl.exe");
        } else {
            checkShell(result, "Bash", "/bin/bash");
            checkShell(result, "Zsh", "/bin/zsh");
            checkShell(result, "Fish", "/usr/bin/fish");
            checkShell(result, "sh", "/bin/sh");
        }
    }

    private void appendDefaultShell(StringBuilder result) {
        try {
            var settingsClass = Class.forName("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider");
            var getInstance = settingsClass.getMethod(GET_INSTANCE_METHOD, Project.class);
            var settings = getInstance.invoke(null, project);
            var getShellPath = settings.getClass().getMethod("getShellPath");
            String defaultShell = (String) getShellPath.invoke(settings);
            result.append("\nIntelliJ default shell: ").append(defaultShell);
        } catch (Exception e) {
            result.append("\nCould not determine IntelliJ default shell.");
        }
    }

    private void checkShell(StringBuilder result, String name, String path) {
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            result.append("  ✓ ").append(name).append(" — ").append(path).append("\n");
        }
    }

    private static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }
}
