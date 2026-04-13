package com.github.catatafishen.agentbridge.psi.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProjectBuildSupport#formatBuildHeader(boolean, int, int, long)}.
 * <p>
 * Same package as the production class so we can call the package-private method directly.
 */
class ProjectBuildSupportTest {

    @Test
    void buildSucceeded_zeroErrors() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 0, 0, 1000);
        assertTrue(result.contains("✓ Build succeeded"), "Expected success marker");
    }

    @Test
    void buildSucceeded_withWarnings() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 0, 5, 2000);
        assertAll(
            () -> assertTrue(result.contains("✓ Build succeeded"), "Expected success marker"),
            () -> assertTrue(result.contains("5 warnings"), "Expected warning count")
        );
    }

    @Test
    void buildFailed_withErrors() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 3, 2, 1500);
        assertAll(
            () -> assertTrue(result.contains("✗ Build failed"), "Expected failure marker"),
            () -> assertTrue(result.contains("3 errors"), "Expected error count"),
            () -> assertTrue(result.contains("2 warnings"), "Expected warning count")
        );
    }

    @Test
    void buildAborted() {
        String result = ProjectBuildSupport.formatBuildHeader(true, 0, 0, 500);
        assertTrue(result.contains("Build aborted."), "Expected aborted marker");
    }

    @Test
    void elapsedTimeFormatted() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 0, 0, 3400);
        assertTrue(result.contains("3.4s"), "Expected elapsed time 3.4s");
    }

    @Test
    void zeroElapsed() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 0, 0, 0);
        assertTrue(result.contains("0.0s"), "Expected elapsed time 0.0s");
    }

    @Test
    void largeElapsed() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 0, 0, 125000);
        assertTrue(result.contains("125.0s"), "Expected elapsed time 125.0s");
    }

    @Test
    void abortedWithErrors() {
        String result = ProjectBuildSupport.formatBuildHeader(true, 2, 1, 800);
        assertAll(
            () -> assertTrue(result.startsWith("Build aborted."),
                "Aborted build must start with 'Build aborted.', got: " + result),
            () -> assertFalse(result.contains("✗ Build failed"),
                "Aborted build must not contain failure marker")
        );
    }

    @Test
    void endsWithNewline() {
        String result = ProjectBuildSupport.formatBuildHeader(false, 1, 0, 1000);
        assertTrue(result.endsWith(System.lineSeparator()),
            "Result must end with system newline, got: [" + result + "]");
    }
}
