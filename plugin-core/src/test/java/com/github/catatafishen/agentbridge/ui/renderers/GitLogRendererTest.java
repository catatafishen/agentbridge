package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns and format detection in {@link GitLogRenderer}.
 */
class GitLogRendererTest {

    private static final GitLogRenderer R = GitLogRenderer.INSTANCE;

    @Nested
    class OnelinePattern {

        @Test
        void matchesShortHash() {
            assertTrue(R.getONELINE_PATTERN().matches("abc1234 Initial commit"));
        }

        @Test
        void matchesFullHash() {
            // Git hashes are exactly 40 hex chars
            String hash40 = "0123456789abcdef0123456789abcdef01234567";
            assertTrue(R.getONELINE_PATTERN().matches(hash40 + " Fix bug"));
        }

        @Test
        void doesNotMatchEmptyLine() {
            assertFalse(R.getONELINE_PATTERN().matches(""));
        }

        @Test
        void doesNotMatchNonHex() {
            assertFalse(R.getONELINE_PATTERN().matches("zzzzzzz Fix bug"));
        }

        @Test
        void doesNotMatchHashOnly() {
            // Must have a space then message
            assertFalse(R.getONELINE_PATTERN().matches("abc1234"));
        }
    }

    @Nested
    class ShortPattern {

        @Test
        void matchesShortFormat() {
            assertTrue(R.getSHORT_PATTERN().matches(
                "abc1234 Fix login (John Doe, 2025-01-01)"));
        }

        @Test
        void doesNotMatchOneline() {
            assertFalse(R.getSHORT_PATTERN().matches("abc1234 Fix login"));
        }
    }

    @Nested
    class MediumEntry {

        @Test
        void capturesAllGroups() {
            Regex pattern = R.getMEDIUM_ENTRY();
            MatchResult match = pattern.matchEntire(
                "abc1234 Fix login (John Doe, 2025-01-01)");
            assertNotNull(match);
            assertEquals("abc1234", match.getGroupValues().get(1));
            assertEquals("Fix login ", match.getGroupValues().get(2));
            assertEquals("John Doe", match.getGroupValues().get(3));
            // Group 4 captures everything after comma+space until closing paren
            assertEquals("2025-01-01", match.getGroupValues().get(4).replace(")", ""));
        }

        @Test
        void matchesFullHash() {
            Regex pattern = R.getMEDIUM_ENTRY();
            MatchResult match = pattern.matchEntire(
                "0123456789abcdef0123456789abcdef01234567 Refactor code (Jane, 3 days ago)");
            assertNotNull(match);
            assertEquals("0123456789abcdef0123456789abcdef01234567",
                match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchWithoutParens() {
            assertNull(R.getMEDIUM_ENTRY().matchEntire("abc1234 Fix login"));
        }
    }

    @Nested
    class CommitPrefix {

        @Test
        void hasCorrectValue() {
            assertEquals("commit ", GitLogRenderer.COMMIT_PREFIX);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "commit abc1234",
            "commit 0123456789abcdef0123456789abcdef01234567",
        })
        void detectsMediumFormat(String line) {
            assertTrue(line.startsWith(GitLogRenderer.COMMIT_PREFIX));
        }
    }
}
