package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TestResultFormatter} — pure formatting for test execution results.
 * No IDE dependencies.
 */
class TestResultFormatterTest {

    @Nested
    @DisplayName("formatTestSummary")
    class FormatTestSummary {

        @Test
        @DisplayName("exit code 0 with empty output shows result header and runner panel message")
        void passedEmptyOutput() {
            String result = TestResultFormatter.formatTestSummary(0, "MyTestConfig", "");

            assertTrue(result.contains("MyTestConfig"), "should contain config name");
            assertTrue(result.contains("Run panel"),
                "should contain runner panel message when output is empty");
            assertFalse(result.contains("FAILED"), "should not contain FAILED");
        }

        @Test
        @DisplayName("exit code 0 with output shows result and appends output")
        void passedWithOutput() {
            String result = TestResultFormatter.formatTestSummary(0, "MyTestConfig", "5 tests, 5 passed");

            assertTrue(result.contains("MyTestConfig"), "should contain config name");
            assertTrue(result.contains("5 tests, 5 passed"), "should append test output");
            assertFalse(result.contains("Run panel"),
                "should not contain runner panel message when output is present");
        }

        @Test
        @DisplayName("exit code 1 with empty output shows FAILED and runner panel message")
        void failedEmptyOutput() {
            String result = TestResultFormatter.formatTestSummary(1, "FailConfig", "");

            assertTrue(result.contains("FAILED (exit code 1)"), "should contain FAILED with exit code");
            assertTrue(result.contains("FailConfig"), "should contain config name");
            assertTrue(result.contains("Run panel"),
                "should contain runner panel message when output is empty");
        }

        @Test
        @DisplayName("exit code 1 with output shows FAILED and appends output")
        void failedWithOutput() {
            String result = TestResultFormatter.formatTestSummary(1, "FailConfig", "2 tests, 1 failed");

            assertTrue(result.contains("FAILED (exit code 1)"), "should contain FAILED with exit code");
            assertTrue(result.contains("FailConfig"), "should contain config name");
            assertTrue(result.contains("2 tests, 1 failed"), "should append test output");
            assertFalse(result.contains("Run panel"),
                "should not contain runner panel message when output is present");
        }

        @Test
        @DisplayName("negative exit code is formatted correctly")
        void negativeExitCode() {
            String result = TestResultFormatter.formatTestSummary(-1, "CrashConfig", "");

            assertTrue(result.contains("FAILED (exit code -1)"), "should handle negative exit code");
            assertTrue(result.contains("CrashConfig"), "should contain config name");
        }

        @Test
        @DisplayName("summary uses em dash separator between status and config name")
        void emDashSeparator() {
            String result = TestResultFormatter.formatTestSummary(0, "DashTest", "");

            assertTrue(result.contains(" — DashTest"),
                "should use em dash separator between status and config name");
        }

        @Test
        @DisplayName("counted results remain the first line and retain the exit code")
        void countedResultsArePreserved() {
            String counts = TestResultFormatter.formatTestResults(4, 2, 1, 0, 1);
            String result = TestResultFormatter.formatTestSummary(1, "MyTestConfig", counts);

            assertTrue(result.startsWith("Test Results: 4 tests, 2 passed, 1 failed, 0 errors, 1 skipped"));
            assertTrue(result.contains("MyTestConfig (exit code 1)"));
        }
    }

    @Nested
    @DisplayName("formatTestResults")
    class FormatTestResults {

        @Test
        @DisplayName("includes every result category")
        void includesAllCounts() {
            assertEquals("Test Results: 5 tests, 2 passed, 1 failed, 1 errors, 1 skipped",
                TestResultFormatter.formatTestResults(5, 2, 1, 1, 1));
        }
    }

    @Nested
    @DisplayName("determineTestStatus")
    class DetermineTestStatus {

        @Test
        @DisplayName("passed=true returns PASSED regardless of defect flag")
        void passedTrue() {
            assertEquals("PASSED", TestResultFormatter.determineTestStatus(true, false));
        }

        @Test
        @DisplayName("passed=true and defect=true still returns PASSED (passed takes priority)")
        void passedTrueDefectTrue() {
            assertEquals("PASSED", TestResultFormatter.determineTestStatus(true, true));
        }

        @Test
        @DisplayName("passed=false and defect=true returns FAILED")
        void defectTrue() {
            assertEquals("FAILED", TestResultFormatter.determineTestStatus(false, true));
        }

        @Test
        @DisplayName("passed=false and defect=false returns UNKNOWN")
        void unknown() {
            assertEquals("UNKNOWN", TestResultFormatter.determineTestStatus(false, false));
        }
    }

    @Nested
    @DisplayName("formatTestDetail")
    class FormatTestDetail {

        @Test
        @DisplayName("passed test shows PASSED status with name")
        void passedTest() {
            String result = TestResultFormatter.formatTestDetail("testFoo", true, false, null, null);
            assertEquals("  PASSED testFoo\n", result);
        }

        @Test
        @DisplayName("failed test with error message and stacktrace")
        void failedWithErrorAndStack() {
            String result = TestResultFormatter.formatTestDetail(
                "testBar", false, true, "expected 1 but was 2", "at com.Foo.testBar(Foo.java:10)");
            assertTrue(result.startsWith("  FAILED testBar\n"), "should start with FAILED status line");
            assertTrue(result.contains("    Error: expected 1 but was 2\n"), "should contain error message");
            assertTrue(result.contains("    Stacktrace:\nat com.Foo.testBar(Foo.java:10)\n"),
                "should contain stacktrace");
        }

        @Test
        @DisplayName("failed test with null error message and null stacktrace")
        void failedNullMessages() {
            String result = TestResultFormatter.formatTestDetail("testBaz", false, true, null, null);
            assertEquals("  FAILED testBaz\n", result, "should only have status line when messages are null");
        }

        @Test
        @DisplayName("failed test with empty error message is omitted")
        void failedEmptyErrorMsg() {
            String result = TestResultFormatter.formatTestDetail("testEmpty", false, true, "", "");
            assertEquals("  FAILED testEmpty\n", result, "should omit empty error and stacktrace");
        }

        @Test
        @DisplayName("failed test with error only (no stacktrace)")
        void failedErrorOnly() {
            String result = TestResultFormatter.formatTestDetail(
                "testErr", false, true, "assertion failed", null);
            assertTrue(result.contains("    Error: assertion failed\n"), "should contain error");
            assertFalse(result.contains("Stacktrace"), "should not contain stacktrace when null");
        }

        @Test
        @DisplayName("failed test with stacktrace only (no error message)")
        void failedStacktraceOnly() {
            String result = TestResultFormatter.formatTestDetail(
                "testStack", false, true, null, "at X.y(X.java:5)");
            assertFalse(result.contains("Error:"), "should not contain Error when null");
            assertTrue(result.contains("    Stacktrace:\nat X.y(X.java:5)\n"), "should contain stacktrace");
        }

        @Test
        @DisplayName("unknown status test ignores error/stacktrace even if provided")
        void unknownStatus() {
            String result = TestResultFormatter.formatTestDetail(
                "testUnknown", false, false, "some error", "some stack");
            assertEquals("  UNKNOWN testUnknown\n", result,
                "should not include error/stacktrace when defect=false");
        }
    }

    @Nested
    @DisplayName("formatConsoleSection")
    class FormatConsoleSection {

        @Test
        @DisplayName("null text returns null")
        void nullText() {
            assertNull(TestResultFormatter.formatConsoleSection(null));
        }

        @Test
        @DisplayName("empty text returns null")
        void emptyText() {
            assertNull(TestResultFormatter.formatConsoleSection(""));
        }

        @Test
        @DisplayName("blank text (whitespace only) returns null")
        void blankText() {
            assertNull(TestResultFormatter.formatConsoleSection("   \n  \t  "));
        }

        @Test
        @DisplayName("non-blank text is wrapped with console header")
        void normalText() {
            String result = TestResultFormatter.formatConsoleSection("some test output");
            assertNotNull(result);
            assertTrue(result.startsWith("\n=== Console Output ===\n"),
                "should start with console header");
            assertTrue(result.contains("some test output"), "should contain original text");
        }

        @Test
        @DisplayName("long text is truncated via ToolUtils.truncateOutput")
        void longTextIsTruncated() {
            String longText = "x".repeat(10000);
            String result = TestResultFormatter.formatConsoleSection(longText);
            assertNotNull(result);
            assertTrue(result.startsWith("\n=== Console Output ===\n"),
                "should start with console header");
            assertTrue(result.contains("truncated"), "long text should be truncated");
        }
    }
}
