package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

public final class QualityFilter {

    /**
     * Patterns matching content that is purely status/nudge with no semantic value.
     */
    private static final List<Pattern> STATUS_PATTERNS = List.of(
        Pattern.compile("^\\s*(continue|go ahead|proceed|yes|no|ok|okay|sure|thanks|thank you|done|next)\\s*[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*keep going\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*looks? good\\s*[.!?]*\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*\\S{1,3}\\s*$")
    );

    /**
     * Tool-result-heavy content: lines that look like tool call output (paths, JSON, diffs).
     * If most of the content is tool output, there's little semantic value to mine.
     * Split into two patterns to keep regex complexity under SonarQube's threshold.
     */
    private static final Pattern STRUCTURAL_LINE_PATTERN = Pattern.compile(
        "^\\s*([│├└─┌┐┘┤┬┴┼|]|\\+--|//|#|\\d+[.:] ).*$"
    );
    private static final Pattern JSON_LINE_PATTERN = Pattern.compile(
        "^\\s*([{}\\[\\]]|\"[^\"]+\"\\s*:).*$"
    );

    private final int minChunkLength;

    public QualityFilter(@NotNull Project project) {
        this(MemorySettings.getInstance(project).getMinChunkLength());
    }

    /**
     * Constructor for testing — accepts minChunkLength directly without needing a Project.
     */
    QualityFilter(int minChunkLength) {
        this.minChunkLength = minChunkLength;
    }

    /**
     * Check if an exchange chunk passes quality thresholds.
     *
     * @param promptText   the user's prompt text
     * @param responseText the assistant's response text
     * @return true if the chunk is worth mining into a memory drawer
     */
    public boolean passes(@NotNull String promptText, @NotNull String responseText) {
        String combined = promptText + " " + responseText;

        if (combined.length() < minChunkLength) {
            return false;
        }

        if (isStatusMessage(promptText)) {
            return false;
        }

        return !isToolOutputHeavy(responseText);
    }

    private static boolean isStatusMessage(@NotNull String text) {
        for (Pattern pattern : STATUS_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the response is mostly tool output (>80% tool-formatted lines).
     */
    private static boolean isToolOutputHeavy(@NotNull String text) {
        String[] lines = text.split("\n");
        if (lines.length < 5) return false;

        int toolLines = 0;
        for (String line : lines) {
            if (STRUCTURAL_LINE_PATTERN.matcher(line).matches()
                || JSON_LINE_PATTERN.matcher(line).matches()
                || line.isBlank()) {
                toolLines++;
            }
        }
        return (double) toolLines / lines.length > 0.8;
    }
}
