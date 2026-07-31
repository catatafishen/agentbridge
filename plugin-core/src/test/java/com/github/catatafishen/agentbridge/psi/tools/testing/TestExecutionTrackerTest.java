package com.github.catatafishen.agentbridge.psi.tools.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.intellij.execution.configurations.RunProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the decisions that made {@code run_tests} give up on a running test suite: which execution
 * event belongs to the launch, and how long to wait for it.
 */
class TestExecutionTrackerTest {

    private static final String CONFIG = "MyTest.testFoo";

    @Test
    @DisplayName("the launched profile is recognised even after the configuration was renamed")
    void matchesRenamedConfigurationByProfileIdentity() {
        RunProfile launched = mock(RunProfile.class);

        assertTrue(TestExecutionTracker.matchesLaunch(launched, launched, CONFIG, CONFIG + " (1)"));
    }

    @Test
    @DisplayName("an unrelated run with a different profile and name is ignored")
    void ignoresUnrelatedRun() {
        RunProfile launched = mock(RunProfile.class);
        RunProfile other = mock(RunProfile.class);

        assertFalse(TestExecutionTracker.matchesLaunch(launched, other, CONFIG, "Something Else"));
    }

    @Test
    @DisplayName("a run that reused our configuration name still matches when profiles differ")
    void fallsBackToConfigurationName() {
        RunProfile launched = mock(RunProfile.class);
        RunProfile other = mock(RunProfile.class);

        assertTrue(TestExecutionTracker.matchesLaunch(launched, other, CONFIG, CONFIG));
    }

    @Test
    @DisplayName("before a profile is published only the name can match")
    void matchesByNameWhenNoProfilePublished() {
        assertTrue(TestExecutionTracker.matchesLaunch(null, mock(RunProfile.class), CONFIG, CONFIG));
        assertFalse(TestExecutionTracker.matchesLaunch(null, mock(RunProfile.class), CONFIG, "Other"));
    }

    @Test
    @DisplayName("an event without run configuration settings does not match")
    void doesNotMatchMissingSettings() {
        assertFalse(TestExecutionTracker.matchesLaunch(null, null, CONFIG, null));
    }

    @Test
    @DisplayName("the handle wait covers a slow starter but never outlives the caller's timeout")
    void handlerWaitIsBoundedByCallerTimeout() {
        assertEquals(60, RunTestsTool.handlerWaitSeconds(150));
        assertEquals(60, RunTestsTool.handlerWaitSeconds(600));
        assertEquals(10, RunTestsTool.handlerWaitSeconds(10));
        assertEquals(1, RunTestsTool.handlerWaitSeconds(0));
    }
}
