package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests regex patterns in {@link CoverageRenderer}.
 */
class CoverageRendererTest {

    private static final CoverageRenderer R = CoverageRenderer.INSTANCE;

    @Nested
    class CoverageLinePattern {

        @Test
        void matchesStandardCoverageLine() {
            MatchResult match = R.getCOVERAGE_LINE().find(
                "MyClass: 85.2% covered (42 / 49 lines)", 0);

            assertNotNull(match);
            assertEquals("MyClass", match.getGroupValues().get(1));
            assertEquals("85.2", match.getGroupValues().get(2));
            assertEquals("42", match.getGroupValues().get(3));
            assertEquals("49", match.getGroupValues().get(4));
        }

        @Test
        void matchesZeroPercent() {
            MatchResult match = R.getCOVERAGE_LINE().find(
                "EmptyClass: 0% covered (0 / 10 lines)", 0);

            assertNotNull(match);
            assertEquals("EmptyClass", match.getGroupValues().get(1));
            assertEquals("0", match.getGroupValues().get(2));
            assertEquals("0", match.getGroupValues().get(3));
            assertEquals("10", match.getGroupValues().get(4));
        }

        @Test
        void matchesHundredPercent() {
            MatchResult match = R.getCOVERAGE_LINE().find(
                "FullyCovered: 100% covered (50 / 50 lines)", 0);

            assertNotNull(match);
            assertEquals("FullyCovered", match.getGroupValues().get(1));
            assertEquals("100", match.getGroupValues().get(2));
            assertEquals("50", match.getGroupValues().get(3));
            assertEquals("50", match.getGroupValues().get(4));
        }

        @Test
        void matchesFractionalPercentage() {
            MatchResult match = R.getCOVERAGE_LINE().find(
                "Utils: 33.33% covered (1 / 3 lines)", 0);

            assertNotNull(match);
            assertEquals("33.33", match.getGroupValues().get(2));
        }

        @Test
        void matchesLargeLineCounts() {
            MatchResult match = R.getCOVERAGE_LINE().find(
                "BigService: 72.5% covered (1450 / 2000 lines)", 0);

            assertNotNull(match);
            assertEquals("BigService", match.getGroupValues().get(1));
            assertEquals("72.5", match.getGroupValues().get(2));
            assertEquals("1450", match.getGroupValues().get(3));
            assertEquals("2000", match.getGroupValues().get(4));
        }

        @Test
        void matchesQualifiedClassName() {
            MatchResult match = R.getCOVERAGE_LINE().find(
                "com.example.UserService: 90.0% covered (45 / 50 lines)", 0);

            assertNotNull(match);
            assertEquals("com.example.UserService", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchRandomText() {
            assertNull(R.getCOVERAGE_LINE().find("just some random text", 0));
        }

        @Test
        void doesNotMatchWithoutPercentage() {
            assertNull(R.getCOVERAGE_LINE().find("MyClass: covered (42 / 49 lines)", 0));
        }
    }
}
