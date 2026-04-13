package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests regex patterns in {@link BuildResultRenderer}.
 */
class BuildResultRendererTest {

    private static final BuildResultRenderer R = BuildResultRenderer.INSTANCE;

    @Nested
    class CountsPattern {

        @Test
        void matchesPluralErrorsAndWarnings() {
            MatchResult match = R.getCOUNTS_PATTERN().find(
                "(3 errors, 5 warnings, 12.5s)", 0);

            assertNotNull(match);
            assertEquals("3", match.getGroupValues().get(1));
            assertEquals("5", match.getGroupValues().get(2));
            assertEquals("12.5", match.getGroupValues().get(3));
        }

        @Test
        void matchesSingularErrorAndWarning() {
            MatchResult match = R.getCOUNTS_PATTERN().find(
                "(1 error, 0 warnings, 0.5s)", 0);

            assertNotNull(match);
            assertEquals("1", match.getGroupValues().get(1));
            assertEquals("0", match.getGroupValues().get(2));
            assertEquals("0.5", match.getGroupValues().get(3));
        }

        @Test
        void matchesZeroErrors() {
            MatchResult match = R.getCOUNTS_PATTERN().find(
                "(0 errors, 0 warnings, 1.2s)", 0);

            assertNotNull(match);
            assertEquals("0", match.getGroupValues().get(1));
            assertEquals("0", match.getGroupValues().get(2));
            assertEquals("1.2", match.getGroupValues().get(3));
        }

        @Test
        void matchesInFullStatusLine() {
            MatchResult match = R.getCOUNTS_PATTERN().find(
                "✓ Build succeeded (0 errors, 2 warnings, 3.0s)", 0);

            assertNotNull(match);
            assertEquals("0", match.getGroupValues().get(1));
            assertEquals("2", match.getGroupValues().get(2));
            assertEquals("3.0", match.getGroupValues().get(3));
        }

        @Test
        void matchesWholeSecondDuration() {
            MatchResult match = R.getCOUNTS_PATTERN().find(
                "(0 errors, 0 warnings, 5s)", 0);

            assertNotNull(match);
            assertEquals("5", match.getGroupValues().get(3));
        }

        @Test
        void doesNotMatchWithoutParens() {
            assertNull(R.getCOUNTS_PATTERN().find("3 errors, 5 warnings, 12.5s", 0));
        }

        @Test
        void doesNotMatchRandomText() {
            assertNull(R.getCOUNTS_PATTERN().find("just some text", 0));
        }
    }
}
