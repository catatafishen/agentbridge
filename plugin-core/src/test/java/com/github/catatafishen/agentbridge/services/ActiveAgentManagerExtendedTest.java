package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link ActiveAgentManager} static utility methods.
 * Covers edge cases not tested in {@link ActiveAgentManagerTest} or
 * {@link ActiveAgentManagerStaticMethodsTest}.
 */
class ActiveAgentManagerExtendedTest {

    @Nested
    @DisplayName("parseIntOrDefault edge cases")
    class ParseIntOrDefaultEdgeCases {

        @Test
        @DisplayName("leading/trailing whitespace returns default (parseInt does not trim)")
        void whitespaceAroundNumber() {
            // Integer.parseInt does not trim, so "  42  " throws NumberFormatException → default
            int result = ActiveAgentManager.parseIntOrDefault("  42  ", 0);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("MAX_VALUE boundary")
        void maxValue() {
            assertEquals(Integer.MAX_VALUE,
                    ActiveAgentManager.parseIntOrDefault(String.valueOf(Integer.MAX_VALUE), 0));
        }

        @Test
        @DisplayName("MIN_VALUE boundary")
        void minValue() {
            assertEquals(Integer.MIN_VALUE,
                    ActiveAgentManager.parseIntOrDefault(String.valueOf(Integer.MIN_VALUE), 0));
        }

        @Test
        @DisplayName("decimal number returns default (not parseable as int)")
        void decimalNumber() {
            assertEquals(7, ActiveAgentManager.parseIntOrDefault("3.14", 7));
        }

        @Test
        @DisplayName("large overflow number returns default")
        void overflow() {
            assertEquals(99, ActiveAgentManager.parseIntOrDefault("99999999999999999999", 99));
        }
    }

    @Nested
    @DisplayName("clamp edge cases")
    class ClampEdgeCases {

        @Test
        @DisplayName("min equals max with value at boundary")
        void minEqualsMax() {
            assertEquals(5, ActiveAgentManager.clamp(5, 5, 5));
        }

        @Test
        @DisplayName("min equals max with value below")
        void minEqualsMaxBelow() {
            assertEquals(5, ActiveAgentManager.clamp(3, 5, 5));
        }

        @Test
        @DisplayName("min equals max with value above")
        void minEqualsMaxAbove() {
            assertEquals(5, ActiveAgentManager.clamp(7, 5, 5));
        }

        @Test
        @DisplayName("negative range")
        void negativeRange() {
            assertEquals(-5, ActiveAgentManager.clamp(-5, -10, -1));
        }

        @Test
        @DisplayName("value at exact minimum boundary")
        void exactMin() {
            assertEquals(0, ActiveAgentManager.clamp(0, 0, 100));
        }

        @Test
        @DisplayName("value at exact maximum boundary")
        void exactMax() {
            assertEquals(100, ActiveAgentManager.clamp(100, 0, 100));
        }

        @Test
        @DisplayName("zero range at zero")
        void zeroRangeAtZero() {
            assertEquals(0, ActiveAgentManager.clamp(0, 0, 0));
        }
    }

    @Nested
    @DisplayName("normalizeSharedTurnTimeoutMinutes edge cases")
    class NormalizeTurnTimeoutEdgeCases {

        @Test
        @DisplayName("value 1 (exact minimum) stays 1")
        void exactMin() {
            assertEquals(1, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("1"));
        }

        @Test
        @DisplayName("value 1440 (exact maximum) stays 1440")
        void exactMax() {
            assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("1440"));
        }

        @Test
        @DisplayName("mid-range value 720")
        void midRange() {
            assertEquals(720, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("720"));
        }

        @Test
        @DisplayName("whitespace-only string returns default")
        void whitespaceOnly() {
            assertEquals(ActiveAgentManager.DEFAULT_TURN_TIMEOUT_MINUTES,
                    ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("   "));
        }
    }

    @Nested
    @DisplayName("normalizeSharedInactivityTimeoutSeconds edge cases")
    class NormalizeInactivityTimeoutEdgeCases {

        @Test
        @DisplayName("value 30 (exact minimum) stays 30")
        void exactMin() {
            assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("30"));
        }

        @Test
        @DisplayName("value 86400 (exact maximum) stays 86400")
        void exactMax() {
            assertEquals(86400, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("86400"));
        }

        @Test
        @DisplayName("whitespace-only string returns default")
        void whitespaceOnly() {
            assertEquals(ActiveAgentManager.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                    ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("   "));
        }
    }

    @Nested
    @DisplayName("normalizeSharedMaxToolCallsPerTurn edge cases")
    class NormalizeMaxToolCallsEdgeCases {

        @Test
        @DisplayName("large positive stored value accepted")
        void largeStoredValue() {
            assertEquals(10000, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("10000", 0));
        }

        @Test
        @DisplayName("null stored with zero legacy returns 0")
        void nullStoredZeroLegacy() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, 0));
        }

        @Test
        @DisplayName("blank stored value returns default 0")
        void blankStored() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("", 42));
        }

        @Test
        @DisplayName("whitespace stored value returns default 0")
        void whitespaceStored() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("   ", 42));
        }
    }

    @Nested
    @DisplayName("default constant values")
    class DefaultConstantValues {

        @Test
        @DisplayName("DEFAULT_TURN_TIMEOUT_MINUTES is positive")
        void turnTimeoutPositive() {
            assertTrue(ActiveAgentManager.DEFAULT_TURN_TIMEOUT_MINUTES > 0);
        }

        @Test
        @DisplayName("DEFAULT_INACTIVITY_TIMEOUT_SECONDS is positive")
        void inactivityTimeoutPositive() {
            assertTrue(ActiveAgentManager.DEFAULT_INACTIVITY_TIMEOUT_SECONDS > 0);
        }

        @Test
        @DisplayName("DEFAULT_MAX_TOOL_CALLS_PER_TURN is non-negative")
        void maxToolCallsNonNegative() {
            assertTrue(ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN >= 0);
        }
    }
}
