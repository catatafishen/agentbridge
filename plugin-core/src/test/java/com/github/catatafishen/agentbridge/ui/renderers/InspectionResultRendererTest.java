package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the pure parsing logic in {@link InspectionResultRenderer}.
 */
class InspectionResultRendererTest {

    private static final InspectionResultRenderer R = InspectionResultRenderer.INSTANCE;
    private static final Regex FINDING_PATTERN = R.getFINDING_PATTERN();

    @Nested
    class AbbreviateSeverity {

        @ParameterizedTest
        @CsvSource({
            "ERROR,E",
            "GENERIC_SERVER_ERROR_OR_WARNING,E",
            "WARNING,W",
            "WEAK_WARNING,w",
            "LIKE_UNUSED_SYMBOL,w",
            "INFORMATION,I",
            "INFO,I",
            "TEXT_ATTRIBUTES,I",
        })
        void mapsKnownSeverities(String severity, String expected) {
            assertEquals(expected, R.abbreviateSeverity(severity));
        }

        @Test
        void caseInsensitive() {
            assertEquals("E", R.abbreviateSeverity("error"));
            assertEquals("W", R.abbreviateSeverity("warning"));
        }

        @Test
        void unknownSeverityTakesFirstChar() {
            assertEquals("C", R.abbreviateSeverity("CUSTOM"));
            assertEquals("D", R.abbreviateSeverity("debug"));
        }
    }

    @Nested
    class FindingPatternRegex {

        @Test
        void matchesStandardFinding() {
            String line = "src/Main.java:42 [ERROR] Unused variable 'x'";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1));
            assertEquals("42", match.getGroupValues().get(2));
            assertEquals("ERROR", match.getGroupValues().get(3));
            assertEquals("Unused variable 'x'", match.getGroupValues().get(4));
        }

        @Test
        void matchesFindingWithToolId() {
            String line = "com/example/Foo.kt:10 [WARNING/SpellCheckingInspection] Typo in 'teh'";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            assertEquals("WARNING/SpellCheckingInspection", match.getGroupValues().get(3));
        }

        @Test
        void matchesWeakWarning() {
            String line = "File.java:1 [WEAK_WARNING] Redundant cast";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            assertEquals("WEAK_WARNING", match.getGroupValues().get(3));
        }

        @Test
        void doesNotMatchBlankLine() {
            assertNull(FINDING_PATTERN.matchEntire(""));
        }

        @Test
        void doesNotMatchSummaryLine() {
            assertNull(FINDING_PATTERN.matchEntire("Found 5 problems across 3 files"));
        }

        @Test
        void matchesLongPath() {
            String line = "very/deep/nested/path/to/file/Example.java:999 [INFO] Message";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            assertEquals("very/deep/nested/path/to/file/Example.java", match.getGroupValues().get(1));
        }
    }

    @Nested
    class ParseFinding {

        @Test
        void parsesFindingWithSeverityOnly() {
            String line = "src/Main.java:42 [ERROR] Unused variable 'x'";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            var finding = R.parseFinding(match);

            assertEquals("src/Main.java", finding.getPath());
            assertEquals(42, finding.getLine());
            assertEquals("ERROR", finding.getSeverity());
            assertEquals("", finding.getToolId());
            assertEquals("Unused variable 'x'", finding.getDescription());
        }

        @Test
        void parsesFindingWithToolId() {
            String line = "Foo.kt:10 [WARNING/UnusedImport] Import unused";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            var finding = R.parseFinding(match);

            assertEquals("Foo.kt", finding.getPath());
            assertEquals(10, finding.getLine());
            assertEquals("WARNING", finding.getSeverity());
            assertEquals("UnusedImport", finding.getToolId());
            assertEquals("Import unused", finding.getDescription());
        }

        @Test
        void lineZero() {
            String line = "File.java:0 [INFO] Test";
            MatchResult match = FINDING_PATTERN.matchEntire(line);
            assertNotNull(match);
            var finding = R.parseFinding(match);
            assertEquals(0, finding.getLine());
        }
    }
}
