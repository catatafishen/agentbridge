package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the static helpers in {@link GooseClient}.
 */
class GooseClientStaticMethodsTest {

    // ── resolveToolIdStatic ─────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveToolIdStatic")
    class ResolveToolIdStatic {

        @Test
        @DisplayName("strips agentbridge: prefix and detail suffix, maps spaces to underscores")
        void humanizedTitleWithDetail() {
            assertEquals("read_file",
                GooseClient.resolveToolIdStatic("agentbridge: read file · /src/main.rs"));
        }

        @Test
        @DisplayName("strips prefix without detail suffix")
        void humanizedTitleNoDetail() {
            assertEquals("read_file",
                GooseClient.resolveToolIdStatic("agentbridge: read file"));
        }

        @Test
        @DisplayName("maps run command title to run_command")
        void runCommandTitle() {
            assertEquals("run_command",
                GooseClient.resolveToolIdStatic("agentbridge: run command · ./gradlew build"));
        }

        @Test
        @DisplayName("maps search text title to search_text")
        void searchTextTitle() {
            assertEquals("search_text",
                GooseClient.resolveToolIdStatic("agentbridge: search text · query"));
        }

        @Test
        @DisplayName("non-prefixed title passes through unchanged")
        void nonPrefixedPassthrough() {
            assertEquals("read_file", GooseClient.resolveToolIdStatic("read_file"));
        }
    }

    // ── hasToolPrefix ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasToolPrefix")
    class HasToolPrefix {

        @Test
        @DisplayName("returns true for agentbridge: prefix")
        void trueWithPrefix() {
            assertTrue(GooseClient.hasToolPrefix("agentbridge: read file"));
        }

        @Test
        @DisplayName("returns false without prefix")
        void falseWithoutPrefix() {
            assertFalse(GooseClient.hasToolPrefix("read file"));
        }
    }
}
