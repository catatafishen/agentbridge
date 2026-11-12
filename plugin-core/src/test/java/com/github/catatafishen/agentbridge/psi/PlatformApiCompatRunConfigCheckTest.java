package com.github.catatafishen.agentbridge.psi;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link PlatformApiCompat#checkRunConfigForError} with the platform's own
 * validation exceptions.
 */
class PlatformApiCompatRunConfigCheckTest {

    @Test
    void errorReportsItsMessageNotTheGenericTitle() throws Exception {
        RunConfiguration config = mock(RunConfiguration.class);
        doThrow(new RuntimeConfigurationError("Node interpreter is not specified"))
            .when(config).checkConfiguration();

        assertEquals("Node interpreter is not specified",
            PlatformApiCompat.checkRunConfigForError(config));
    }

    @Test
    void warningDoesNotBlockCreation() throws Exception {
        RunConfiguration config = mock(RunConfiguration.class);
        doThrow(new RuntimeConfigurationWarning("Working directory is empty"))
            .when(config).checkConfiguration();

        assertNull(PlatformApiCompat.checkRunConfigForError(config));
    }

    @Test
    void validConfigurationReturnsNull() {
        assertNull(PlatformApiCompat.checkRunConfigForError(mock(RunConfiguration.class)));
    }
}
