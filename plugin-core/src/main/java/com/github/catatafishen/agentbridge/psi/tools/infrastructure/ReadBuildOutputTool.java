package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads output from a tab in the Build tool window.
 */
public final class ReadBuildOutputTool extends InfrastructureTool {

    private static final Logger LOG = Logger.getInstance(ReadBuildOutputTool.class);
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String PARAM_OFFSET = "offset";
    private static final String JSON_TAB_NAME = "tab_name";

    public ReadBuildOutputTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_build_output";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Build Output";
    }

    @Override
    public @NotNull String description() {
        return "Read output from a tab in the Build tool window (Gradle/Maven/compiler output)";
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
            Param.optional(JSON_TAB_NAME, TYPE_STRING, "Name of the Build tab to read (default: currently selected or most recent). Use tab names shown in IntelliJ's Build tool window."),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Character offset to start from (default: -1 = show last max_chars chars). Use 0 to read from the beginning, or a previous end offset to paginate forward.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : -1;
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        try {
            var textRef = new AtomicReference<String>();
            EdtUtil.invokeAndWait(() -> textRef.set(readBuildOutputOnEdt(tabName, maxChars, offset)));
            return textRef.get();
        } catch (Exception e) {
            LOG.warn("Failed to read Build output", e);
            return "Error reading Build output: " + e.getMessage();
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }

    private String readBuildOutputOnEdt(String tabName, int maxChars, int offset) {
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Build");
        if (toolWindow != null) {
            var contentManager = toolWindow.getContentManager();
            var contents = contentManager.getContents();
            if (contents.length > 0) {
                com.intellij.ui.content.Content target = resolveTargetContent(contentManager, contents, tabName);
                if (target != null) {
                    String displayName = target.getDisplayName() != null ? target.getDisplayName() : "Build";
                    String text = extractBuildTabText(target);
                    if (text != null && !text.isBlank()) return formatRunOutput(displayName, text, maxChars, offset);
                }
                // tab_name not found in Build window, or Build content had no text → fall through to Run window
            }
        }
        // Build tool window absent or empty — fall back to Run tool window.
        // In CLion/CMake, builds run as run configurations and write to the Run panel,
        // not the JPS Build panel. Return that output directly so the agent gets what it needs.
        return readFromRunWindow(tabName, maxChars, offset);
    }

    /**
     * Falls back to the Run tool window when the Build tool window is absent or empty.
     * Matches the requested tab name if provided, otherwise returns the most recent tab.
     */
    private String readFromRunWindow(String tabName, int maxChars, int offset) {
        var descriptors = com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction((com.intellij.openapi.util.Computable<java.util.List<com.intellij.execution.ui.RunContentDescriptor>>)
                () -> new java.util.ArrayList<>(
                    com.intellij.execution.ui.RunContentManager.getInstance(project).getAllDescriptors()));
        if (descriptors.isEmpty()) {
            return "No build output available. The Build tool window is empty and no Run panel tabs exist. "
                + "Trigger a build or run first.";
        }

        com.intellij.execution.ui.RunContentDescriptor target = findRunDescriptor(descriptors, tabName);
        if (tabName != null && target == null) {
            return buildNoTabError(descriptors, tabName);
        }
        if (target == null) {
            target = descriptors.getLast();
        }

        var console = target.getExecutionConsole();
        if (console == null) return "Run tab '" + target.getDisplayName() + "' has no console output.";

        String text = readConsoleTextOnEdt(console);
        if (text == null || text.isBlank()) {
            return "Run tab '" + target.getDisplayName() + "' has no text content yet "
                + "(build may still be running).";
        }
        return formatRunOutput(target.getDisplayName(), text, maxChars, offset);
    }

    @Nullable
    private static com.intellij.ui.content.Content resolveTargetContent(
        com.intellij.ui.content.ContentManager contentManager,
        com.intellij.ui.content.Content[] contents,
        String tabName) {
        if (tabName != null) {
            return findBuildContentByName(contents, tabName);
        }
        var selected = contentManager.getSelectedContent();
        return selected != null ? selected : contents[contents.length - 1];
    }

    private static @Nullable com.intellij.ui.content.Content findBuildContentByName(
        com.intellij.ui.content.Content[] contents, String tabName) {
        for (var c : contents) {
            if (c.getDisplayName() != null && c.getDisplayName().contains(tabName)) return c;
        }
        return null;
    }

    private static com.intellij.execution.ui.RunContentDescriptor findRunDescriptor(
        java.util.List<com.intellij.execution.ui.RunContentDescriptor> descriptors, String tabName) {
        if (tabName == null) return null;
        for (var d : descriptors) {
            if (d.getDisplayName() != null && d.getDisplayName().contains(tabName)) return d;
        }
        return null;
    }

    private static String buildNoTabError(
        java.util.List<com.intellij.execution.ui.RunContentDescriptor> descriptors, String tabName) {
        var sb = new StringBuilder("No tab matching '").append(tabName)
            .append("' in Build or Run panel. Available Run tabs:\n");
        for (var d : descriptors) sb.append("  - ").append(d.getDisplayName()).append("\n");
        return sb.toString();
    }

    private String extractBuildTabText(com.intellij.ui.content.Content content) {
        var component = content.getComponent();

        try {
            var getConsoleView = component.getClass().getMethod("getConsoleView");
            var consoleView = getConsoleView.invoke(component);
            if (consoleView != null) {
                flushConsoleOutput(consoleView);
                String text = extractPlainConsoleText(consoleView);
                if (text != null && !text.isEmpty()) return text;
            }
        } catch (NoSuchMethodException ignored) {
            // Not a BuildView
        } catch (Exception e) {
            LOG.debug("getConsoleView() failed for Build tab", e);
        }

        flushConsoleOutput(component);
        String text = extractPlainConsoleText(component);
        if (text != null && !text.isEmpty()) return text;

        return findConsoleTextInComponentTree(component, 8);
    }
}
