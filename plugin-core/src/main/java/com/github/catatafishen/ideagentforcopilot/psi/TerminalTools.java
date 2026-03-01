package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Terminal tool handlers: run_in_terminal, read_terminal_output, list_terminals.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
final class TerminalTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(TerminalTools.class);
    private static final String JSON_TAB_NAME = "tab_name";
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    private static final String GET_INSTANCE_METHOD = "getInstance";
    private static final String OS_NAME_PROPERTY = "os.name";

    TerminalTools(Project project) {
        super(project);
        if (isPluginInstalled("org.jetbrains.plugins.terminal")) {
            register("run_in_terminal", this::runInTerminal);
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
                var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager");
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
        // Resolve method via the interface class, not the implementation (avoids IllegalAccessException on inner classes)
        var widgetInterface = Class.forName("com.intellij.terminal.ui.TerminalWidget");
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

            var findWidgetByContent = managerClass.getMethod("findWidgetByContent",
                com.intellij.ui.content.Content.class);

            for (var content : toolWindow.getContentManager().getContents()) {
                String displayName = content.getDisplayName();
                if (displayName != null && displayName.contains(tabName)) {
                    Object widget = findWidgetByContent.invoke(null, content);
                    if (widget != null) {
                        LOG.info("Reusing terminal tab '" + displayName + "'");
                        return widget;
                    }
                    // Reworked terminal (IntelliJ 2025+) may not set userData — tab not reusable
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

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager");
                var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
                if (toolWindow == null) {
                    resultFuture.complete("Terminal tool window not available.");
                    return;
                }

                com.intellij.ui.content.Content targetContent = findTerminalContent(toolWindow, tabName);
                if (targetContent == null) {
                    resultFuture.complete("No terminal tab found" +
                        (tabName != null ? " matching '" + tabName + "'" : "") + ".");
                    return;
                }

                readTerminalText(managerClass, targetContent, resultFuture);

            } catch (Exception e) {
                LOG.warn("Failed to read terminal output", e);
                resultFuture.complete("Failed to read terminal output: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Timed out reading terminal output.";
        } catch (Exception e) {
            return "Timed out reading terminal output.";
        }
    }

    private com.intellij.ui.content.Content findTerminalContent(
        com.intellij.openapi.wm.ToolWindow toolWindow, String tabName) {
        var contentManager = toolWindow.getContentManager();

        // Find by name if specified
        if (tabName != null) {
            for (var content : contentManager.getContents()) {
                String displayName = content.getDisplayName();
                if (displayName != null && displayName.contains(tabName)) {
                    return content;
                }
            }
        }

        // Fall back to selected content
        return contentManager.getSelectedContent();
    }

    private void readTerminalText(Class<?> managerClass, com.intellij.ui.content.Content targetContent,
                                  CompletableFuture<String> resultFuture) throws Exception {
        // Find widget via findWidgetByContent
        var findWidgetByContent = managerClass.getMethod("findWidgetByContent",
            com.intellij.ui.content.Content.class);
        Object widget = findWidgetByContent.invoke(null, targetContent);
        if (widget == null) {
            resultFuture.complete("No terminal widget found for tab '" + targetContent.getDisplayName() +
                "'. The auto-created default tab may not be readable — use agent-created tabs instead.");
            return;
        }

        // Call getText() via the TerminalWidget interface
        try {
            var widgetInterface = Class.forName("com.intellij.terminal.ui.TerminalWidget");
            var getText = widgetInterface.getMethod("getText");
            CharSequence text = (CharSequence) getText.invoke(widget);
            String output = text != null ? text.toString().strip() : "";
            if (output.isEmpty()) {
                resultFuture.complete("Terminal '" + targetContent.getDisplayName() + "' has no output.");
            } else {
                resultFuture.complete("Terminal '" + targetContent.getDisplayName() + "' output:\n" +
                    ToolUtils.truncateOutput(output));
            }
        } catch (NoSuchMethodException e) {
            resultFuture.complete("getText() not available on this terminal type (" +
                widget.getClass().getSimpleName() + "). Terminal output reading not supported.");
        }
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
