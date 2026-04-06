package com.github.catatafishen.ideagentforcopilot.session.exporters;

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
     * Returns the project-specific v2 sessions directory.
     *
     * @param basePath project base path (may be {@code null})
     * @return the sessions directory (may not yet exist on disk)
     */
    @NotNull
    public static File sessionsDir(@Nullable String basePath) {
        String base = basePath != null ? basePath : "";
        return new File(base + "/.agent-work/sessions");
    }
}
