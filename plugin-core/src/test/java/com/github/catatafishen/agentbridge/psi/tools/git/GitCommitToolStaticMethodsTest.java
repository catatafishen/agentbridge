package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static helper methods in {@link GitCommitTool}.
 */
@DisplayName("GitCommitTool static methods")
class GitCommitToolStaticMethodsTest {

    @Nested
    @DisplayName("resolveAmend")
    class ResolveAmend {

        @Test
        @DisplayName("defaults to false when 'amend' param is absent")
        void defaultsToFalse() {
            assertFalse(GitCommitTool.resolveAmend(new JsonObject()));
        }

        @Test
        @DisplayName("returns true when 'amend' is explicitly true")
        void explicitlyTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("amend", true);
            assertTrue(GitCommitTool.resolveAmend(args));
        }

        @Test
        @DisplayName("returns false when 'amend' is explicitly false")
        void explicitlyFalse() {
            JsonObject args = new JsonObject();
            args.addProperty("amend", false);
            assertFalse(GitCommitTool.resolveAmend(args));
        }

        @Test
        @DisplayName("other args don't affect amend result")
        void otherArgsIgnored() {
            JsonObject args = new JsonObject();
            args.addProperty("message", "test commit");
            args.addProperty("all", true);
            assertFalse(GitCommitTool.resolveAmend(args));
        }
    }

    @Nested
    @DisplayName("resolveCommitAll")
    class ResolveCommitAll {

        @Test
        @DisplayName("defaults to false when 'all' param is absent")
        void defaultsToFalse() {
            assertFalse(GitCommitTool.resolveCommitAll(new JsonObject()));
        }

        @Test
        @DisplayName("returns true when 'all' is explicitly true")
        void explicitlyTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("all", true);
            assertTrue(GitCommitTool.resolveCommitAll(args));
        }

        @Test
        @DisplayName("returns false when 'all' is explicitly false")
        void explicitlyFalse() {
            JsonObject args = new JsonObject();
            args.addProperty("all", false);
            assertFalse(GitCommitTool.resolveCommitAll(args));
        }

        @Test
        @DisplayName("other args don't affect result")
        void otherArgsIgnored() {
            JsonObject args = new JsonObject();
            args.addProperty("message", "test commit");
            args.addProperty("amend", true);
            assertFalse(GitCommitTool.resolveCommitAll(args));
        }
    }

    @Nested
    @DisplayName("parseAuthorNameEmail")
    class ParseAuthorNameEmail {

        @Test
        @DisplayName("parses standard 'Name <email>' format")
        void parsesStandardFormat() {
            String[] result = GitCommitTool.parseAuthorNameEmail("github-copilot-developer <github-copilot-developer@users.noreply.github.com>");
            assertNotNull(result);
            assertEquals(2, result.length);
            assertEquals("github-copilot-developer", result[0]);
            assertEquals("github-copilot-developer@users.noreply.github.com", result[1]);
        }

        @Test
        @DisplayName("parses author with spaces in name")
        void parsesNameWithSpaces() {
            String[] result = GitCommitTool.parseAuthorNameEmail("John Doe <john@example.com>");
            assertNotNull(result);
            assertEquals("John Doe", result[0]);
            assertEquals("john@example.com", result[1]);
        }

        @Test
        @DisplayName("returns null when angle brackets are missing")
        void returnsNullForMissingBrackets() {
            assertNull(GitCommitTool.parseAuthorNameEmail("No Brackets Here"));
        }

        @Test
        @DisplayName("returns null when name is empty")
        void returnsNullForEmptyName() {
            assertNull(GitCommitTool.parseAuthorNameEmail("<email@example.com>"));
        }

        @Test
        @DisplayName("returns null when email is empty")
        void returnsNullForEmptyEmail() {
            assertNull(GitCommitTool.parseAuthorNameEmail("Name <>"));
        }

        @Test
        @DisplayName("returns null for empty string")
        void returnsNullForEmptyString() {
            assertNull(GitCommitTool.parseAuthorNameEmail(""));
        }
    }
}
