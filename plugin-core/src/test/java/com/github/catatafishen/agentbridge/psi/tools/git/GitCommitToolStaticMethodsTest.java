package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        @DisplayName("defaults to true when 'all' param is absent")
        void defaultsToTrue() {
            assertTrue(GitCommitTool.resolveCommitAll(new JsonObject()));
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
            assertTrue(GitCommitTool.resolveCommitAll(args));
        }
    }
}
