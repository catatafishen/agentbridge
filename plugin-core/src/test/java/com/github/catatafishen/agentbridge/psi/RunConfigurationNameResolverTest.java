package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RunConfigurationNameResolver}'s pure decoration-stripping and
 * error-message logic (no IDE fixtures needed — {@link RunConfigurationNameResolver#findLenient}
 * is covered indirectly via {@code RunConfigurationService}/tool integration tests).
 */
class RunConfigurationNameResolverTest {

    @Nested
    class StripDecoration {

        @Test
        void stripsTypeSuffix() {
            assertEquals("My App", RunConfigurationNameResolver.stripDecoration("My App [Application]"));
        }

        @Test
        void stripsTypeAndTemporarySuffix() {
            assertEquals("Gradle Test: com.wartsila.aistats.*",
                RunConfigurationNameResolver.stripDecoration(
                    "Gradle Test: com.wartsila.aistats.* [Gradle] (temporary)"));
        }

        @Test
        void plainNameReturnsNull() {
            assertNull(RunConfigurationNameResolver.stripDecoration("My App"));
        }

        @Test
        void nameContainingBracketsButNoDecorationReturnsNull() {
            assertNull(RunConfigurationNameResolver.stripDecoration("Run [not a type"));
        }

        @Test
        void temporaryMarkerAloneWithoutTypeReturnsNull() {
            assertNull(RunConfigurationNameResolver.stripDecoration("My App (temporary)"));
        }
    }

    @Nested
    class NotFoundMessage {

        @Test
        void plainNameGetsGenericHint() {
            String message = RunConfigurationNameResolver.notFoundMessage("My App");
            assertEquals("Run configuration not found: 'My App'. "
                + "Use list_run_configurations to see available configs.", message);
        }

        @Test
        void decoratedNameGetsDecorationHint() {
            String message = RunConfigurationNameResolver.notFoundMessage(
                "Gradle Test: com.wartsila.aistats.* [Gradle] (temporary)");
            assertTrue(message.contains("looks like a decorated"));
            assertTrue(message.contains("'Gradle Test: com.wartsila.aistats.*' was also not found"));
        }
    }
}
