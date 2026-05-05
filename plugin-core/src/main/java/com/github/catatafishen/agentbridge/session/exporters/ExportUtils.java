package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
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
     * Returns the project-specific v2 sessions directory using the configured storage root.
     *
     * <p>Uses {@link AgentBridgeStorageSettings} to resolve the storage root (e.g.
     * {@code {project}/.agentbridge}), then appends {@code sessions/}. Falls back to the
     * legacy {@code .agent-work/sessions/} path when the configured directory does not yet
     * exist, ensuring backwards-compatibility with existing data.</p>
     *
     * <b>Prefer this overload over {@link #sessionsDir(String)} when a {@link Project} is
     * available.</b>
     *
     * @param project the IntelliJ project
     * @return the sessions directory (may not yet exist on disk)
     */
    @NotNull
    public static File sessionsDir(@NotNull Project project) {
        Path storageRoot = AgentBridgeStorageSettings.getInstance().getProjectStorageDir(project);
        File configured = storageRoot.resolve(SESSIONS_SUBDIR).toFile();
        if (!configured.exists() || !configured.isDirectory()) {
            String basePath = project.getBasePath();
            if (basePath != null) {
                File legacy = new File(basePath, LEGACY_SESSIONS_DIR);
                if (legacy.exists() && legacy.isDirectory()) {
                    return legacy;
                }
            }
        }
        return configured;
    }

    /**
     * Returns the project-specific v2 sessions directory.
     *
     * @param basePath project base path (may be {@code null})
     * @return the sessions directory (may not yet exist on disk)
     * @deprecated Prefer {@link #sessionsDir(Project)} when a {@link Project} is available;
     * it uses the configured storage root instead of the hardcoded legacy path.
     */
    @Deprecated(since = "0.9")
    @NotNull
    public static File sessionsDir(@Nullable String basePath) {
        if (basePath == null || basePath.isEmpty()) {
            return new File(LEGACY_SESSIONS_DIR);
        }
        return new File(basePath, LEGACY_SESSIONS_DIR);
    }
}
