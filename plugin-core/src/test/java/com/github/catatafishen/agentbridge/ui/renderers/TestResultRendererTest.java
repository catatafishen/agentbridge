package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests regex patterns in {@link TestResultRenderer}.
 */
class TestResultRendererTest {

    private static final TestResultRenderer R = TestResultRenderer.INSTANCE;

    @Nested
    class SummaryPattern {

        @Test
        void matchesFullSummary() {
            MatchResult match = R.getSUMMARY_PATTERN().find(
                "Test Results: 15 tests, 12 passed, 2 failed, 1 error, 0 skipped (3.5s)", 0);

            assertNotNull(match);
            assertEquals("15", match.getGroupValues().get(1));
            assertEquals("12", match.getGroupValues().get(2));
            assertEquals("2", match.getGroupValues().get(3));
            assertEquals("1", match.getGroupValues().get(4));
            assertEquals("0", match.getGroupValues().get(5));
            assertEquals("3.5", match.getGroupValues().get(6));
        }

        @Test
        void matchesSingularForms() {
            MatchResult match = R.getSUMMARY_PATTERN().find(
                "Test Results: 1 test, 1 passed, 0 failed, 0 errors, 0 skipped (0.1s)", 0);

            assertNotNull(match);
            assertEquals("1", match.getGroupValues().get(1));
            assertEquals("1", match.getGroupValues().get(2));
            assertEquals("0", match.getGroupValues().get(3));
            assertEquals("0", match.getGroupValues().get(4));
            assertEquals("0", match.getGroupValues().get(5));
            assertEquals("0.1", match.getGroupValues().get(6));
        }

        @Test
        void matchesAllFailed() {
            MatchResult match = R.getSUMMARY_PATTERN().find(
                "Test Results: 5 tests, 0 passed, 5 failed, 0 errors, 0 skipped (2.0s)", 0);

            assertNotNull(match);
            assertEquals("5", match.getGroupValues().get(1));
            assertEquals("0", match.getGroupValues().get(2));
            assertEquals("5", match.getGroupValues().get(3));
        }

        @Test
        void matchesAllSkipped() {
            MatchResult match = R.getSUMMARY_PATTERN().find(
                "Test Results: 10 tests, 0 passed, 0 failed, 0 errors, 10 skipped (0.0s)", 0);

            assertNotNull(match);
            assertEquals("10", match.getGroupValues().get(5));
            assertEquals("0.0", match.getGroupValues().get(6));
        }

        @Test
        void matchesLargeNumbers() {
            MatchResult match = R.getSUMMARY_PATTERN().find(
                "Test Results: 1500 tests, 1490 passed, 8 failed, 2 errors, 0 skipped (45.3s)", 0);

            assertNotNull(match);
            assertEquals("1500", match.getGroupValues().get(1));
            assertEquals("1490", match.getGroupValues().get(2));
            assertEquals("8", match.getGroupValues().get(3));
            assertEquals("2", match.getGroupValues().get(4));
            assertEquals("45.3", match.getGroupValues().get(6));
        }

        @Test
        void doesNotMatchRandomText() {
            assertNull(R.getSUMMARY_PATTERN().find("just some text", 0));
        }

        @Test
        void doesNotMatchPartialSummary() {
            assertNull(R.getSUMMARY_PATTERN().find("Test Results: 15 tests", 0));
        }
    }
}
