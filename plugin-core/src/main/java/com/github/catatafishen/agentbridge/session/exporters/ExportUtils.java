package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Shared utility methods for session exporters.
 */
public final class ExportUtils {

    private static final int MAX_TOOL_NAME_LENGTH = 200;
    private static final Pattern INVALID_TOOL_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern CONSECUTIVE_UNDERSCORES = Pattern.compile("_{3,}");
    private static final String AGENTBRIDGE_DASH = "agentbridge-";
    private static final String AGENTBRIDGE_UNDERSCORE = "agentbridge_";
    private static final String AGENTBRIDGE_KIRO = "@agentbridge/";

    private static final String SESSIONS_SUBDIR = "sessions";

    /**
     * Legacy hardcoded sessions path retained for callers that still read from disk
     * directly (e.g. {@code SessionStoreV2}, {@code SessionSwitchService}). New code
     * should resolve the sessions directory via {@link #sessionsDir(Project)}, which
     * honors {@link AgentBridgeStorageSettings}.
     *
     * @deprecated Use {@link #sessionsDir(Project)} instead.
     */
    @Deprecated(since = "0.9")
    public static final String LEGACY_SESSIONS_DIR = ".agent-work/sessions";

    private ExportUtils() {
    }

    /**
     * Sanitizes a tool name for the Anthropic API, which requires tool_use names to match
     * {@code [a-zA-Z0-9_-]+} and be at most 200 characters.
     *
     * <p>Our session data stores human-readable titles for tool calls (e.g., "git add src/Foo.java",
     * "Viewing .../ChatConsolePanel.kt") which can exceed the API limit. This method replaces
     * invalid characters, collapses runs of 3+ underscores (preserving the {@code __} MCP
     * separator), and truncates to fit.</p>
     */
    public static String sanitizeToolName(@NotNull String rawName) {
        if (rawName.isEmpty()) return "unknown_tool";
        String sanitized = INVALID_TOOL_NAME_CHARS.matcher(rawName).replaceAll("_");
        sanitized = CONSECUTIVE_UNDERSCORES.matcher(sanitized).replaceAll("__");
        if (sanitized.startsWith("_")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("_")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.length() > MAX_TOOL_NAME_LENGTH) sanitized = sanitized.substring(0, MAX_TOOL_NAME_LENGTH);
        return sanitized.isEmpty() ? "unknown_tool" : sanitized;
    }

    /**
     * Normalizes a tool name for Codex rollout export by ensuring it starts with
     * {@code agentbridge_}.
     *
     * <p>Codex presents MCP tools to the model with the server name as a prefix
     * (e.g., {@code agentbridge_read_file}). The exported rollout must use the same
     * names so the model recognizes them as the same tools after session restore.
     * Different clients use different prefix conventions:</p>
     * <ul>
     *   <li>Copilot: {@code agentbridge-read_file} (dash separator)</li>
     *   <li>Codex/OpenCode: {@code agentbridge_read_file} (underscore separator)</li>
     *   <li>Kiro: {@code @agentbridge/read_file} (at-sign + slash)</li>
     *   <li>Claude: {@code read_file} (no prefix)</li>
     * </ul>
     *
     * <p>This method strips any existing prefix and adds the canonical
     * {@code agentbridge_} prefix that Codex expects.</p>
     */
    @NotNull
    public static String normalizeToolNameForCodex(@NotNull String rawName) {
        String base = rawName;
        if (base.startsWith(AGENTBRIDGE_DASH)) {
            base = base.substring(AGENTBRIDGE_DASH.length());
        } else if (base.startsWith(AGENTBRIDGE_UNDERSCORE)) {
            base = base.substring(AGENTBRIDGE_UNDERSCORE.length());
        } else if (base.startsWith(AGENTBRIDGE_KIRO)) {
            base = base.substring(AGENTBRIDGE_KIRO.length());
        }
        String sanitized = sanitizeToolName(base);
        return AGENTBRIDGE_UNDERSCORE + sanitized;
    }

    /**
     * MCP namespace prefix Claude Code applies to AgentBridge tools
     * (e.g. {@code mcp__agentbridge__read_file}).
     */
    public static final String CLAUDE_MCP_PREFIX = "mcp__agentbridge__";

    /**
     * MCP namespace prefix generic ACP clients (Junie, OpenCode in ACP mode) apply to
     * AgentBridge tools (e.g. {@code agentbridge-read_file}).
     */
    public static final String ACP_MCP_PREFIX = AGENTBRIDGE_DASH;

    /**
     * Known AgentBridge MCP namespace prefixes across all supported clients, longest
     * first so {@code mcp__agentbridge__} is stripped before the shorter {@code agentbridge_}.
     */
    private static final String[] KNOWN_MCP_PREFIXES = {
        CLAUDE_MCP_PREFIX, "mcp_agentbridge_", AGENTBRIDGE_KIRO, AGENTBRIDGE_DASH, AGENTBRIDGE_UNDERSCORE
    };

    /**
     * Resolves the canonical, prefix-free tool name from a stored tool call.
     *
     * <p>Prefers the recorded tool <em>name</em> ({@code pluginTool}, then {@code acpName})
     * over the human-readable display <em>title</em>, then strips any client-specific MCP
     * namespace prefix so callers can re-apply the prefix for the target client. This is the
     * inverse of the per-client prefixing applied at export time.</p>
     *
     * <p>Using the name rather than the title matters: titles are sometimes custom chip labels
     * or run-configuration names (e.g. "Rebuild plugin-core (clean)") that are not valid tool
     * names. Exporting those as tool_use names primes the model to emit bare/invalid names that
     * the target client rejects with "No such tool available".</p>
     */
    @NotNull
    public static String canonicalToolName(@NotNull EntryData.ToolCall tc) {
        String name = tc.getPluginTool();
        if (name == null || name.isEmpty()) name = tc.getAcpName();
        if (name == null || name.isEmpty()) name = tc.getTitle();
        return stripKnownMcpPrefix(name);
    }

    @NotNull
    private static String stripKnownMcpPrefix(@NotNull String name) {
        for (String prefix : KNOWN_MCP_PREFIXES) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }

    /**
     * Returns {@code true} when the tool call refers to an AgentBridge MCP tool (as opposed to
     * a native tool of the source agent).
     *
     * <p>{@code pluginTool} is populated only when MCP correlation is confirmed, so a non-null
     * value is a reliable signal. As a fallback for legacy data that predates {@code pluginTool}
     * persistence, a title carrying a known MCP prefix also counts.</p>
     */
    public static boolean isAgentBridgeTool(@NotNull EntryData.ToolCall tc) {
        if (tc.getPluginTool() != null) return true;
        String title = tc.getTitle();
        for (String prefix : KNOWN_MCP_PREFIXES) {
            if (title.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Resolves the tool_use {@code name} to export for a target client.
     *
     * <p>For AgentBridge MCP tools, returns {@code mcpPrefix + canonicalName} so the restored
     * transcript uses the same namespaced names the target client exposes — otherwise the model,
     * primed by bare names, emits tool calls the client rejects. Native (non-MCP) tool calls of
     * the source agent are returned by their canonical name unprefixed.</p>
     *
     * @param tc        the stored tool call
     * @param mcpPrefix the target client's MCP namespace prefix (e.g. {@link #CLAUDE_MCP_PREFIX})
     */
    @NotNull
    public static String exportMcpToolName(@NotNull EntryData.ToolCall tc, @NotNull String mcpPrefix) {
        String canonical = canonicalToolName(tc);
        if (isAgentBridgeTool(tc)) {
            return sanitizeToolName(mcpPrefix + canonical);
        }
        return sanitizeToolName(canonical);
    }

    /**
     * Returns the project-specific v2 sessions directory using the configured storage root.
     *
     * <p>Uses {@link AgentBridgeStorageSettings} to resolve the storage root (e.g.
     * {@code {project}/.agentbridge}), then appends {@code sessions/}. The legacy
     * {@code .agent-work/sessions/} path is migrated into this location by
     * {@code LegacyAgentWorkCleanup} at project open.</p>
     *
     * <b>Prefer this overload over {@link #sessionsDir(String)} when a {@link Project} is
     * available.</b>
     *
     * @param project the IntelliJ project
     * @return the sessions directory (may not yet exist on disk)
     */
    @NotNull
    public static File sessionsDir(@NotNull Project project) {
        return AgentBridgeStorageSettings.getInstance()
            .getProjectStorageDir(project)
            .resolve(SESSIONS_SUBDIR)
            .toFile();
    }

    /**
     * Returns the project-specific v2 sessions directory using a path-only fallback.
     *
     * <p>Falls back to {@code {basePath}/.agentbridge/sessions/} (the default storage
     * mode) since no {@link Project} is available to consult
     * {@link AgentBridgeStorageSettings}.</p>
     *
     * @param basePath project base path (may be {@code null})
     * @return the sessions directory (may not yet exist on disk)
     * @deprecated Prefer {@link #sessionsDir(Project)} when a {@link Project} is available;
     * it uses the configured storage root.
     */
    @Deprecated(since = "0.9")
    @NotNull
    public static File sessionsDir(@Nullable String basePath) {
        if (basePath == null || basePath.isEmpty()) {
            return new File(".agentbridge", SESSIONS_SUBDIR);
        }
        return new File(new File(basePath, ".agentbridge"), SESSIONS_SUBDIR);
    }
}
