package com.github.catatafishen.agentbridge.memory.mining;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips agent operational narration from response text before it enters the
 * mining pipeline. Lines matching common agent patterns ("I'll use...",
 * "Let me search...", "Looking at...") are removed because they carry no
 * substantive knowledge — only navigation/tool-invocation commentary.
 *
 * <p>Applied in {@link ExchangeChunker} so that downstream stages
 * (classification, embedding, triple extraction) see only meaningful content.
 */
public final class NarrationFilter {

    /**
     * Patterns that match entire lines of agent operational narration.
     * Each pattern is anchored to the start of a trimmed line.
     */
    private static final List<Pattern> LINE_PATTERNS = List.of(
        // "I'll read the file", "I need to check", "Let me search for"
        Pattern.compile(
            "^(I'll|I need to|I'm going to|Let me|Now I'll|Next I'll|First I'll|Now let me)\\s+\\w+",
            Pattern.CASE_INSENSITIVE),
        // "Looking at the code", "Checking the tests", "Reading the file"
        Pattern.compile(
            "^(Looking at|Checking|Reading|Searching|Examining|Investigating|Inspecting|Scanning|Exploring)\\s+",
            Pattern.CASE_INSENSITIVE),
        // "The output shows...", "I can see that..."
        Pattern.compile(
            "^(The output shows|I can see that|I see that|From the output|Based on the output)\\b",
            Pattern.CASE_INSENSITIVE),
        // "Here's what I found", "Let me explain"
        Pattern.compile(
            "^(Here's what|Here is what|Let me explain|I found that|I noticed that)\\b",
            Pattern.CASE_INSENSITIVE),
        // "Good — no compilation errors", "Clean build", "All tests pass"
        Pattern.compile(
            "^(Good\\s*\\p{Pd}|Clean build|All \\d+ tests pass|Tests pass|Build succeeded|No compilation errors)",
            Pattern.CASE_INSENSITIVE),
        // "Now let me also...", "Let me also..."
        Pattern.compile(
            "^(Now let me also|Let me also|I should also|I also need to)\\b",
            Pattern.CASE_INSENSITIVE)
    );

    private NarrationFilter() {
    }

    /**
     * Remove lines of agent operational narration from response text.
     *
     * @param response raw assistant response text
     * @return filtered text with narration lines removed
     */
    public static @NotNull String filter(@NotNull String response) {
        String[] lines = response.split("\n");
        StringBuilder result = new StringBuilder(response.length());
        boolean prevBlank = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!prevBlank && !result.isEmpty()) {
                    result.append('\n');
                    prevBlank = true;
                }
                continue;
            }

            if (isNarration(trimmed)) {
                continue;
            }

            if (!result.isEmpty() && !prevBlank) {
                result.append('\n');
            }
            result.append(line);
            prevBlank = false;
        }

        return result.toString().strip();
    }

    /**
     * Check if a trimmed line is agent narration.
     * Package-private for testing.
     */
    static boolean isNarration(@NotNull String trimmedLine) {
        for (Pattern pattern : LINE_PATTERNS) {
            if (pattern.matcher(trimmedLine).find()) {
                return true;
            }
        }
        return false;
    }
}
