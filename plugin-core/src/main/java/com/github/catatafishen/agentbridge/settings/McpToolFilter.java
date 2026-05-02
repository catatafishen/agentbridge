package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Filters tools for the MCP server and tool registration UI. Hides chat-specific
 * tools that have no meaning without the Copilot chat UI, and respects user's
 * enable/disable settings.
 */
public final class McpToolFilter {

    /**
     * Maximum number of tools that may be enabled simultaneously. MCP clients
     * such as Claude Desktop and Copilot CLI enforce a 128-tool limit; exceeding
     * it causes connection failures or silent tool drops.
     */
    public static final int MAX_TOOLS = 128;

    /**
     * Tools that are always hidden — they require the Copilot chat panel.
     */
    private static final Set<String> ALWAYS_HIDDEN = Set.of(
        "get_chat_html"
    );

    /**
     * Tools that are shown but disabled by default — cosmetic, rarely useful, or expensive.
     * This is the full set for fresh installations. For incremental migrations, see
     * {@link #DEFAULTS_BY_VERSION}.
     */
    public static final Set<String> DEFAULT_DISABLED = Set.of(
        "get_notifications",
        "set_theme",
        "list_themes",
        "run_sonarqube_analysis",
        "get_sonar_rule_description"
    );

    /**
     * Current defaults version. Increment when adding new default-disabled tools.
     * Existing installations with an older version get only the NEW entries applied,
     * preserving any user-made enable/disable choices for previously-known tools.
     */
    public static final int CURRENT_DEFAULTS_VERSION = 2;

    /**
     * Incremental defaults per version. Each entry maps a version number to the
     * tool IDs that were added as default-disabled in that version.
     * <ul>
     *   <li>Version 1: cosmetic/niche tools (original defaults)</li>
     *   <li>Version 2: SonarQube tools (expensive, opt-in)</li>
     * </ul>
     */
    static final java.util.Map<Integer, Set<String>> DEFAULTS_BY_VERSION = java.util.Map.of(
        1, Set.of("get_notifications", "set_theme", "list_themes"),
        2, Set.of("run_sonarqube_analysis", "get_sonar_rule_description")
    );

    private McpToolFilter() {
    }

    /**
     * Returns all tools that should be visible in the settings UI
     * (excludes always-hidden and built-in tools).
     */
    public static List<ToolDefinition> getConfigurableTools(@NotNull Project project) {
        return ToolRegistry.getInstance(project).getAllTools().stream()
            .filter(t -> !t.isBuiltIn())
            .filter(t -> !ALWAYS_HIDDEN.contains(t.id()))
            .toList();
    }

    /**
     * Returns tools that are enabled for the given project and settings.
     */
    public static List<ToolDefinition> getEnabledTools(@NotNull McpServerSettings settings,
                                                       @NotNull Project project) {
        return getConfigurableTools(project).stream()
            .filter(t -> settings.isToolEnabled(t.id()))
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
