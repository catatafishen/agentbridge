package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.ToolEntry;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.Set;

/**
 * Filters tools for the MCP server and tool registration UI. Hides chat-specific
 * tools that have no meaning without the Copilot chat UI, and respects user's
 * enable/disable settings.
 */
public final class McpToolFilter {

    /**
     * Tools that are always hidden — they require the Copilot chat panel.
     */
    private static final Set<String> ALWAYS_HIDDEN = Set.of(
        "get_chat_html"
    );

    /**
     * Tools that are shown but disabled by default — cosmetic or rarely useful.
     */
    public static final Set<String> DEFAULT_DISABLED = Set.of(
        "get_notifications",
        "set_theme",
        "list_themes"
    );

    private McpToolFilter() {
    }

    /**
     * Returns all tools that should be visible in the settings UI
     * (excludes always-hidden and built-in tools).
     */
    public static List<ToolEntry> getConfigurableTools() {
        return ToolRegistry.getAllTools().stream()
            .filter(t -> !t.isBuiltIn)
            .filter(t -> !ALWAYS_HIDDEN.contains(t.id))
            .toList();
    }

    /**
     * Returns all configurable tools for a given project context.
     */
    public static List<ToolEntry> getConfigurableTools(Project project) {
        return getConfigurableTools();
    }

    /**
     * Returns tool IDs that are enabled for a given project context.
     */
    public static List<ToolEntry> getEnabledTools(McpServerSettings settings, Project project) {
        return getConfigurableTools(project).stream()
            .filter(t -> settings.isToolEnabled(t.id))
            .toList();
    }

    /**
     * Returns tool IDs that are enabled.
     */
    public static List<ToolEntry> getEnabledTools(McpServerSettings settings) {
        return getConfigurableTools().stream()
            .filter(t -> settings.isToolEnabled(t.id))
            .toList();
    }

    /**
     * Returns true if the tool should be hidden from settings entirely.
     */
    public static boolean isAlwaysHidden(String toolId) {
        return ALWAYS_HIDDEN.contains(toolId);
    }

    /**
     * Returns true if the tool should be disabled by default (first launch).
     */
    public static boolean isDefaultDisabled(String toolId) {
        return DEFAULT_DISABLED.contains(toolId);
    }
}
