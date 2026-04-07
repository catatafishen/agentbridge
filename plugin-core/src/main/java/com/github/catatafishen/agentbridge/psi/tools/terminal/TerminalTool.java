package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base for terminal tools. Provides shared constants and utility
 * methods for terminal operations (formerly in {@code TerminalTools}).
 */
// S112: These methods wrap reflection-based IntelliJ terminal API calls — generic exceptions are intentional
@SuppressWarnings("java:S112")
public abstract class TerminalTool extends Tool {

    protected static final Logger LOG = Logger.getInstance(TerminalTool.class);
    protected static final String JSON_TAB_NAME = "tab_name";
    protected static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    protected static final String GET_INSTANCE_METHOD = "getInstance";
    protected static final String OS_NAME_PROPERTY = "os.name";
    protected static final String TERMINAL_MANAGER_CLASS = "org.jetbrains.plugins.terminal.TerminalToolWindowManager";
    protected static final String TERMINAL_WIDGET_CLASS = "com.intellij.terminal.ui.TerminalWidget";
    protected static final String FIND_WIDGET_BY_CONTENT_METHOD = "findWidgetByContent";
    protected static final String TTY_CONNECTOR_CLASS = "com.jediterm.terminal.TtyConnector";
    protected static final int DEFAULT_MAX_LINES = 50;

    protected TerminalTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.TERMINAL;
    }

    protected Object findTerminalWidget(Class<?> managerClass, String tabName) throws Exception {
        if (tabName != null) {
            return findTerminalWidgetByTabName(managerClass, tabName);
        }
        Object[] result = {null};
        EdtUtil.invokeAndWait(() -> {
            try {
                var toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
                if (toolWindow == null) return;
                var selected = toolWindow.getContentManager().getSelectedContent();
                if (selected == null) return;
                var findWidget = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD, Content.class);
                result[0] = findWidget.invoke(null, selected);
            } catch (Exception e) {
                LOG.warn("findTerminalWidget failed", e);
            }
        });
        return result[0];
    }

    /**
     * Resolve human-readable escape sequences to actual characters.
     * Supports: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, \n, \t
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

    protected record TerminalWidgetResult(Object widget, String tabName) {
    }

    protected TerminalWidgetResult getOrCreateTerminalWidget(Class<?> managerClass, Object manager,
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
        AgentTabTracker.getInstance(project).trackTab(TERMINAL_TOOL_WINDOW_ID, title);
        return new TerminalWidgetResult(widget, title + " (new)");
    }

    /**
     * Send a command to a TerminalWidget, using the interface method to avoid IllegalAccessException.
     */
    protected void sendTerminalCommand(Object widget, String command) throws Exception {
        var widgetInterface = Class.forName(TERMINAL_WIDGET_CLASS);
        try {
            widgetInterface.getMethod("sendCommandToExecute", String.class).invoke(widget, command);
        } catch (NoSuchMethodException e) {
            widget.getClass().getMethod("executeCommand", String.class).invoke(widget, command);
        }
    }

    protected Object findTerminalWidgetByTabName(Class<?> managerClass, String tabName) {
        Object[] result = {null};
        try {
            EdtUtil.invokeAndWait(() -> {
                try {
                    var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
                    if (toolWindow == null) return;

                    var findWidgetByContent = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD, Content.class);

                    for (var content : toolWindow.getContentManager().getContents()) {
                        String displayName = content.getDisplayName();
                        if (displayName != null && displayName.contains(tabName)) {
                            Object widget = findWidgetByContent.invoke(null, content);
                            if (widget != null) {
                                LOG.info("Reusing terminal tab '" + displayName + "'");
                                result[0] = widget;
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Could not find terminal tab: " + tabName, e);
                }
            });
        } catch (Exception e) {
            LOG.warn("findTerminalWidgetByTabName EDT dispatch failed: " + tabName, e);
        }
        return result[0];
    }

    protected Content resolveTerminalContent(String tabName) {
        Content[] result = {null};
        try {
            EdtUtil.invokeAndWait(() -> {
                var toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
                if (toolWindow == null) return;

                var contentManager = toolWindow.getContentManager();
                if (tabName == null) {
                    result[0] = contentManager.getSelectedContent();
                    return;
                }

                for (var content : contentManager.getContents()) {
                    String name = content.getDisplayName();
                    if (name != null && name.contains(tabName)) {
                        result[0] = content;
                        return;
                    }
                }
            });
        } catch (Exception e) {
            LOG.warn("resolveTerminalContent failed", e);
        }
        return result[0];
    }

    protected void readTerminalText(CompletableFuture<String> resultFuture,
                                    Content targetContent,
                                    int maxLines) throws Exception {
        var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
        var findWidgetByContent = managerClass.getMethod(FIND_WIDGET_BY_CONTENT_METHOD, Content.class);
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

    protected void appendOpenTerminalTabs(StringBuilder result) {
        result.append("Open terminal tabs:\n");
        try {
            EdtUtil.invokeAndWait(() -> {
                try {
                    var toolWindowManager = ToolWindowManager.getInstance(project);
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
            });
        } catch (Exception e) {
            result.append("  (Could not list open terminals)\n");
        }
    }

    protected void appendAvailableShells(StringBuilder result) {
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

    protected void appendDefaultShell(StringBuilder result) {
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

    protected void checkShell(StringBuilder result, String name, String path) {
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            result.append("  ✓ ").append(name).append(" — ").append(path).append("\n");
        }
    }

    protected static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }
}
