package com.github.catatafishen.agentbridge.memory.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link QualityFilter} — content quality assessment.
 * Uses the package-private int constructor to avoid needing a Project context.
 */
class QualityFilterTest {

    private final QualityFilter filter = new QualityFilter(50);

    // --- Min length ---

    @Test
    void belowMinLength_fails() {
        // "hi" + " " + "ok" = 5 chars, well below minChunkLength of 50
        assertFalse(filter.passes("hi", "ok"));
    }

    // --- Status prompt patterns ---

    @Test
    void statusPrompt_continue_fails() {
        assertFalse(filter.passes("continue",
            "I will proceed with implementing the changes to the authentication module now."));
    }

    @Test
    void statusPrompt_yes_fails() {
        assertFalse(filter.passes("yes",
            "Understood, I will start working on the refactoring of the service layer."));
    }

    @Test
    void statusPrompt_looksGood_fails() {
        assertFalse(filter.passes("looks good",
            "Great, I will continue with the next set of changes to the codebase."));
    }

    @Test
    void statusPrompt_shortText_fails() {
        // "ok" matches both the status keyword pattern and the 1-3 non-whitespace char pattern
        assertFalse(filter.passes("ok",
            "I will start making those changes to the project configuration files now."));
    }

    @Test
    void statusPrompt_withPunctuation_fails() {
        // STATUS_PATTERNS allow optional trailing [.!?] punctuation
        assertFalse(filter.passes("sure!",
            "Starting the implementation of the feature with proper error handling."));
    }

    // --- Tool output detection ---

    @Test
    void toolOutputHeavy_fails() {
        // 6 structural lines → 100% > 80% threshold, and >= 5 lines
        String response = "│ src/main/java/Auth.java\n" +
            "│ src/main/java/User.java\n" +
            "│ src/main/java/Config.java\n" +
            "│ src/main/java/Service.java\n" +
            "│ src/main/java/Controller.java\n" +
            "│ src/main/java/Repository.java";
        assertFalse(filter.passes("Show me the project file listing please", response));
    }

    // --- Passing cases ---

    @Test
    void normalExchange_passes() {
        assertTrue(filter.passes(
            "How should we structure the authentication module?",
            "I recommend using JWT tokens with a refresh token flow and proper session management."));
    }

    @Test
    void fewLines_notToolHeavy() {
        // Only 3 lines (all JSON) → < 5 lines → isToolOutputHeavy returns false early
        String response = "{\"key1\": \"value1\"}\n{\"key2\": \"value2\"}\n{\"key3\": \"value3\"}";
        assertTrue(filter.passes(
            "Show me the configuration entries for the project",
            response));
    }

    @Test
    void mixedContent_belowThreshold_passes() {
        // 10 lines total: 6 normal text + 4 structural → 40% tool lines, below 80%
        String response = "Here is the analysis of the codebase:\n" +
            "The service layer handles business logic.\n" +
            "│ src/Service.java\n" +
            "│ src/Repository.java\n" +
            "The controller maps HTTP endpoints.\n" +
            "The repository abstracts database access.\n" +
            "│ src/Controller.java\n" +
            "│ src/Database.java\n" +
            "Overall the architecture is well structured.\n" +
            "I recommend adding more integration tests.";
        assertTrue(filter.passes("Analyze the project architecture", response));
    }

    // --- Edge cases ---

    @Test
    void emptyResponse_belowMinLength() {
        // "What should we do?" + " " + "" = 19 chars < 50
        assertFalse(filter.passes("What should we do?", ""));
    }

    @Test
    void longStatusPrompt_notMatched_passes() {
        // "continue working on the feature" does NOT match STATUS_PATTERNS
        // because patterns require the entire string to be just "continue" (anchored with ^ and $)
        assertTrue(filter.passes(
            "continue working on the feature implementation",
            "I will refactor the authentication module to use dependency injection."));
    }

    // --- Max combined length ---

    @Test
    void exceedsMaxCombinedLength_fails() {
        String longPrompt = "How should we structure the project?";
        String longResponse = "x".repeat(8100);
        assertFalse(filter.passes(longPrompt, longResponse));
    }

    @Test
    void withinMaxCombinedLength_passes() {
        String prompt = "How should we structure the authentication module?";
        String response = "Use JWT tokens with refresh flow. " + "Details here. ".repeat(50);
        assertTrue(filter.passes(prompt, response));
    }

    // --- Short prompt + long response ---

    @Test
    void shortPromptLongResponse_fails() {
        String shortPrompt = "now";
        String longResponse = "I will implement the full authentication module with JWT tokens " +
            "and refresh token flow. " + "More details. ".repeat(150);
        assertFalse(filter.passes(shortPrompt, longResponse));
    }

    @Test
    void longPromptLongResponse_passes() {
        String prompt = "How should we implement the authentication?";
        String response = "Use JWT tokens with a refresh token flow and proper session management. " +
            "Additional details. ".repeat(50);
        assertTrue(filter.passes(prompt, response));
    }
}
