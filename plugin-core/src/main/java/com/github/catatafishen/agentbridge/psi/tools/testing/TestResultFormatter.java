package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure formatting utilities for test execution results.
 * No IDE dependencies — fully unit-testable.
 */
final class TestResultFormatter {

    private static final String TESTS_PASSED = "Test Results: ";
    private static final String TESTS_FAILED_PREFIX = "Tests FAILED (exit code ";
    private static final String RESULTS_IN_RUNNER_PANEL = "\n(See detailed results in the IDE's Run panel)";

    private TestResultFormatter() {
    }

    /**
     * Formats a test execution summary from exit code, config name, and test output.
     */
    static String formatTestSummary(int exitCode, @NotNull String configName, @NotNull String testOutput) {
        String summary = (exitCode == 0 ? TESTS_PASSED : TESTS_FAILED_PREFIX + exitCode + ")")
            + " — " + configName;
        if (testOutput.startsWith(TESTS_PASSED)) {
            return testOutput + "\n\nRun configuration: " + configName + " (exit code " + exitCode + ")";
        }
        return testOutput.isEmpty()
            ? summary + RESULTS_IN_RUNNER_PANEL
            : summary + "\n" + testOutput;
    }

    /**
     * Formats aggregate counts obtained from the IDE test-results model.
     */
    static String formatTestResults(int total, int passed, int failed, int errors, int skipped) {
        return String.format("Test Results: %d tests, %d passed, %d failed, %d errors, %d skipped",
            total, passed, failed, errors, skipped);
    }

    /**
     * Determines the test status label from passed/defect flags.
     */
    static String determineTestStatus(boolean passed, boolean defect) {
        if (passed) return "PASSED";
        if (defect) return "FAILED";
        return "UNKNOWN";
    }

    /**
     * Formats a single test detail line with optional failure information.
     */
    static String formatTestDetail(@NotNull String name, boolean passed, boolean defect,
                                   @Nullable String errorMsg, @Nullable String stacktrace) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(determineTestStatus(passed, defect)).append(" ").append(name).append("\n");
        if (defect) {
            if (errorMsg != null && !errorMsg.isEmpty()) {
                sb.append("    Error: ").append(errorMsg).append("\n");
            }
            if (stacktrace != null && !stacktrace.isEmpty()) {
                sb.append("    Stacktrace:\n").append(stacktrace).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Formats raw console text into a labelled console output section.
     * Returns {@code null} if text is null or blank.
     */
    @Nullable
    static String formatConsoleSection(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        return "\n=== Console Output ===\n" + ToolUtils.truncateOutput(text);
    }
}
