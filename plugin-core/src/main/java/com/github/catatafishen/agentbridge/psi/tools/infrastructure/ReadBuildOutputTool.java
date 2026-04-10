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
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        try {
            var textRef = new AtomicReference<String>();
            EdtUtil.invokeAndWait(() -> textRef.set(readBuildOutputOnEdt(tabName, maxChars)));
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

    private String readBuildOutputOnEdt(String tabName, int maxChars) {
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Build");
        if (toolWindow == null) {
            return "Build tool window is not available (no Java/Kotlin project, or no build has run yet).";
        }
        var contentManager = toolWindow.getContentManager();
        var contents = contentManager.getContents();
        if (contents.length == 0) {
            return "Build tool window is empty — no build has been run yet.";
        }
        com.intellij.ui.content.Content target = resolveTargetContent(contentManager, contents, tabName);
        if (target == null) {
            return buildTabNotFoundMessage(tabName, contents);
        }
        String displayName = target.getDisplayName() != null ? target.getDisplayName() : "Build";
        String text = extractBuildTabText(target);
        if (text == null || text.isBlank()) {
            return buildEmptyContentMessage(displayName, contents, target);
        }
        return formatRunOutput(displayName, text, maxChars);
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

    private static String buildTabNotFoundMessage(String tabName, com.intellij.ui.content.Content[] contents) {
        StringBuilder available = new StringBuilder("No Build tab matching '").append(tabName).append("'. Available tabs:\n");
        for (var c : contents) available.append("  - ").append(c.getDisplayName()).append("\n");
        return available.toString();
    }

    private static String buildEmptyContentMessage(String displayName,
                                                   com.intellij.ui.content.Content[] contents,
                                                   com.intellij.ui.content.Content target) {
        StringBuilder msg = new StringBuilder("Build tab '").append(displayName)
            .append("' has no text content yet (build may still be running).\n");
        if (contents.length > 1) {
            msg.append("Other available tabs:\n");
            for (var c : contents) {
                if (c != target) msg.append("  - ").append(c.getDisplayName()).append("\n");
            }
        }
        return msg.toString();
    }

    private static @Nullable com.intellij.ui.content.Content findBuildContentByName(
        com.intellij.ui.content.Content[] contents, String tabName) {
        for (var c : contents) {
            if (c.getDisplayName() != null && c.getDisplayName().contains(tabName)) return c;
        }
        return null;
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
