package com.github.catatafishen.agentbridge.memory.mining;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link QualityFilter} — content quality assessment.
 * Uses the package-private int constructor to avoid needing a Project context.
 */
class QualityFilterTest {

    private QualityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new QualityFilter(200);
    }

    @Test
    void passesValidContent() {
        String prompt = "How should we structure the authentication module?";
        String response = "I recommend using JWT tokens with a refresh token flow. " +
            "The auth module should have a service layer for token validation, " +
            "a repository for user lookups, and middleware for request interception. " +
            "This keeps the concerns separated and makes testing easier. " +
            "We should also add rate limiting to prevent brute force attacks.";
        assertTrue(filter.passes(prompt, response));
    }

    @Test
    void rejectsShortContent() {
        assertFalse(filter.passes("Hi", "Hello"));
    }

    @Test
    void rejectsStatusMessages() {
        assertFalse(filter.passes("continue", "Continuing with the task..."));
    }

    @Test
    void rejectsGoAhead() {
        assertFalse(filter.passes("go ahead", "Processing your request..."));
    }

    @Test
    void rejectsYes() {
        assertFalse(filter.passes("yes", "Acknowledged. Here is what I will do..."));
    }

    @Test
    void rejectsOk() {
        assertFalse(filter.passes("ok",
            "Moving forward with the implementation of the feature that was discussed in detail earlier..."));
    }

    @Test
    void rejectsSingleCharPrompt() {
        assertFalse(filter.passes("y",
            "This is a much longer response that has meaningful content about architecture and design patterns in the codebase."));
    }

    @Test
    void rejectsToolOutputHeavyResponse() {
        StringBuilder toolOutput = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            toolOutput.append("│ src/main/java/File").append(i).append(".java\n");
        }
        assertFalse(filter.passes(
            "Show me the project structure in detail please",
            toolOutput.toString()));
    }

    @Test
    void passesContentWithSomeToolOutput() {
        String response = "Here is the analysis of the code:\n" +
            "│ src/main/java/Auth.java\n" +
            "│ src/main/java/User.java\n" +
            "The Auth module handles authentication using JWT tokens. " +
            "The User module manages user profiles and preferences. " +
            "I recommend refactoring the Auth module to separate token generation from validation. " +
            "This will make the code more testable and maintainable.";
        assertTrue(filter.passes(
            "Analyze the authentication module architecture and tell me about patterns",
            response));
    }

    @Test
    void rejectsKeepGoing() {
        assertFalse(filter.passes("keep going", "Working on it now..."));
    }

    @Test
    void rejectsLooksGood() {
        assertFalse(filter.passes("looks good", "Thank you! Continuing with the changes..."));
    }

    @Test
    void passesLongPromptWithDetailedResponse() {
        String prompt = "Please analyze the entire codebase architecture and provide a detailed breakdown";
        String response = "After thorough analysis of the codebase, here are the key findings:\n" +
            "1. The core module depends on the UI module through an event bus pattern\n" +
            "2. There is a circular dependency between auth and session modules\n" +
            "3. The database layer correctly uses repository pattern\n" +
            "4. The service layer is well structured with proper separation of concerns";
        assertTrue(filter.passes(prompt, response));
    }

    @Test
    void customMinChunkLength() {
        QualityFilter strictFilter = new QualityFilter(500);
        String prompt = "Please explain the repository pattern and how it applies to our codebase";
        String response = "The repository pattern abstracts data access behind an interface. " +
            "In our codebase, we use it in the UserRepository and SessionRepository classes. " +
            "Each repository handles CRUD operations for its entity type. " +
            "This decouples the service layer from the database implementation details. " +
            "We can easily swap SQLite for PostgreSQL by implementing the same interface.";
        assertTrue(filter.passes(prompt, response));
        assertFalse(strictFilter.passes(prompt, response));
    }

    // --- Additional status patterns ---

    @Test
    void rejectsProceed() {
        // Response long enough so combined > minChunkLength (200), to ensure isStatusMessage() is reached
        assertFalse(filter.passes("proceed",
            "On it! I will start by analyzing the codebase structure, then implement the changes across " +
                "all affected modules. The refactoring will touch the service layer, repository layer, " +
                "and the controller endpoints to ensure consistency."));
    }

    @Test
    void rejectsSure() {
        assertFalse(filter.passes("sure",
            "Starting the implementation now. I will begin with the authentication module and " +
                "refactor the token validation logic to use a more robust approach. Then I will " +
                "update the integration tests to cover the new flows."));
    }

    @Test
    void rejectsThanks() {
        assertFalse(filter.passes("thanks", "You're welcome!"));
    }

    @Test
    void rejectsThankYou() {
        assertFalse(filter.passes("thank you", "Glad to help!"));
    }

    @Test
    void rejectsDone() {
        assertFalse(filter.passes("done", "Alright, wrapping up..."));
    }

    @Test
    void rejectsNext() {
        assertFalse(filter.passes("next", "Moving to the next task..."));
    }

    @Test
    void rejectsNo() {
        assertFalse(filter.passes("no", "Understood, I will not do that."));
    }

    @Test
    void rejectsStatusWithPunctuation() {
        assertFalse(filter.passes("ok!", "Processing..."));
        assertFalse(filter.passes("yes.", "Got it!"));
        assertFalse(filter.passes("thanks!", "Sure!"));
    }

    @Test
    void rejectsStatusWithWhitespace() {
        assertFalse(filter.passes("  continue  ", "Continuing..."));
        assertFalse(filter.passes("\tok\n", "Done!"));
    }

    // --- JSON line tool output detection ---

    @Test
    void rejectsJsonHeavyResponse() {
        StringBuilder jsonOutput = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            jsonOutput.append("\"key").append(i).append("\": \"value").append(i).append("\"\n");
        }
        assertFalse(filter.passes(
            "Show me the entire configuration in JSON format",
            jsonOutput.toString()));
    }

    @Test
    void rejectsJsonBracesHeavyResponse() {
        StringBuilder jsonOutput = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            jsonOutput.append("{\n");
            jsonOutput.append("  \"name\": \"item").append(i).append("\"\n");
            jsonOutput.append("}\n");
        }
        assertFalse(filter.passes(
            "Show me all the items in our data store",
            jsonOutput.toString()));
    }

    // --- Response with fewer than 5 lines (isToolOutputHeavy early return) ---

    @Test
    void shortResponseWithToolCharsIsNotFiltered() {
        String response = "│ src/main/java/Auth.java\n" +
            "│ src/main/java/User.java\n" +
            "│ src/main/java/Service.java";
        // Only 3 lines → isToolOutputHeavy returns false (< 5 lines)
        // Combined is long enough (> 200 chars) so it should pass
        String prompt = "Show me the files related to authentication and user management systems so I can understand the project structure and dependencies better";
        assertTrue(filter.passes(prompt, response));
    }

    @Test
    void fourLineToolOutputNotFiltered() {
        String response = "│ line one with some content here\n│ line two with more content\n│ line three showing output\n│ line four with the last bit of tool output displayed here";
        String prompt = "Please list the top-level source directories in our project structure and explain how they are organized for the build process and continuous integration pipeline";
        assertTrue(filter.passes(prompt, response));
    }
}
