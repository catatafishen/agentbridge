package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for pure static utility methods in {@link GenericSettings}.
 *
 * <p>All three methods are package-private, so they can be called directly
 * from this test class (same package) — no reflection needed.</p>
 */
class GenericSettingsStaticMethodsTest {

    // ── parseToolPermission ─────────────────────────────────────────────────

    @Nested
    @DisplayName("parseToolPermission")
    class ParseToolPermission {

        @Test
        @DisplayName("null stored → returns defaultValue (ALLOW)")
        void nullReturnsDefault_ALLOW() {
            assertEquals(ToolPermission.ALLOW,
                    GenericSettings.parseToolPermission(null, ToolPermission.ALLOW));
        }

        @Test
        @DisplayName("null stored → returns defaultValue (DENY)")
        void nullReturnsDefault_DENY() {
            assertEquals(ToolPermission.DENY,
                    GenericSettings.parseToolPermission(null, ToolPermission.DENY));
        }

        @Test
        @DisplayName("\"ALLOW\" → ALLOW")
        void allowString() {
            assertEquals(ToolPermission.ALLOW,
                    GenericSettings.parseToolPermission("ALLOW", ToolPermission.DENY));
        }

        @Test
        @DisplayName("\"ASK\" → ASK")
        void askString() {
            assertEquals(ToolPermission.ASK,
                    GenericSettings.parseToolPermission("ASK", ToolPermission.DENY));
        }

        @Test
        @DisplayName("\"DENY\" → DENY")
        void denyString() {
            assertEquals(ToolPermission.DENY,
                    GenericSettings.parseToolPermission("DENY", ToolPermission.ALLOW));
        }

        @Test
        @DisplayName("invalid string → returns defaultValue")
        void invalidStringReturnsDefault() {
            assertEquals(ToolPermission.ASK,
                    GenericSettings.parseToolPermission("invalid_string", ToolPermission.ASK));
        }

        @Test
        @DisplayName("empty string → returns defaultValue (IllegalArgumentException caught)")
        void emptyStringReturnsDefault() {
            assertEquals(ToolPermission.ALLOW,
                    GenericSettings.parseToolPermission("", ToolPermission.ALLOW));
        }
    }

    // ── resolveEffective ────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveEffective")
    class ResolveEffective {

        @Test
        @DisplayName("top=DENY short-circuits to DENY regardless of other params")
        void topDenyShortCircuits() {
            assertEquals(ToolPermission.DENY,
                    GenericSettings.resolveEffective(
                            ToolPermission.DENY, true, true,
                            ToolPermission.ALLOW, ToolPermission.ALLOW));
        }

        @Test
        @DisplayName("top=ASK short-circuits to ASK regardless of other params")
        void topAskShortCircuits() {
            assertEquals(ToolPermission.ASK,
                    GenericSettings.resolveEffective(
                            ToolPermission.ASK, true, false,
                            ToolPermission.ALLOW, ToolPermission.ALLOW));
        }

        @Test
        @DisplayName("top=ALLOW, supportsSubPerms=false → returns ALLOW")
        void allowNoSubPerms() {
            assertEquals(ToolPermission.ALLOW,
                    GenericSettings.resolveEffective(
                            ToolPermission.ALLOW, false, true,
                            ToolPermission.DENY, ToolPermission.DENY));
        }

        @Test
        @DisplayName("top=ALLOW, supportsSubPerms=true, isInside=true → returns insidePerm")
        void allowSubPermsInsideProject() {
            assertEquals(ToolPermission.ASK,
                    GenericSettings.resolveEffective(
                            ToolPermission.ALLOW, true, true,
                            ToolPermission.ASK, ToolPermission.DENY));
        }

        @Test
        @DisplayName("top=ALLOW, supportsSubPerms=true, isInside=false → returns outsidePerm")
        void allowSubPermsOutsideProject() {
            assertEquals(ToolPermission.DENY,
                    GenericSettings.resolveEffective(
                            ToolPermission.ALLOW, true, false,
                            ToolPermission.ASK, ToolPermission.DENY));
        }

        @Test
        @DisplayName("inside=DENY, outside=ASK — verifies different sub-permissions")
        void differentSubPermissions() {
            assertEquals(ToolPermission.DENY,
                    GenericSettings.resolveEffective(
                            ToolPermission.ALLOW, true, true,
                            ToolPermission.DENY, ToolPermission.ASK));
            assertEquals(ToolPermission.ASK,
                    GenericSettings.resolveEffective(
                            ToolPermission.ALLOW, true, false,
                            ToolPermission.DENY, ToolPermission.ASK));
        }
    }

    // ── parseDoubleSafe ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseDoubleSafe")
    class ParseDoubleSafe {

        @Test
        @DisplayName("null → 0.0")
        void nullReturnsZero() {
            assertEquals(0.0, GenericSettings.parseDoubleSafe(null));
        }

        @Test
        @DisplayName("\"0.0\" → 0.0")
        void zeroString() {
            assertEquals(0.0, GenericSettings.parseDoubleSafe("0.0"));
        }

        @Test
        @DisplayName("\"1.5\" → 1.5")
        void positiveDecimal() {
            assertEquals(1.5, GenericSettings.parseDoubleSafe("1.5"));
        }

        @Test
        @DisplayName("\"-2.7\" → -2.7")
        void negativeDecimal() {
            assertEquals(-2.7, GenericSettings.parseDoubleSafe("-2.7"));
        }

        @Test
        @DisplayName("\"abc\" → 0.0 (parse failure)")
        void nonNumericReturnsZero() {
            assertEquals(0.0, GenericSettings.parseDoubleSafe("abc"));
        }

        @Test
        @DisplayName("empty string → 0.0 (parse failure)")
        void emptyStringReturnsZero() {
            assertEquals(0.0, GenericSettings.parseDoubleSafe(""));
        }

        @Test
        @DisplayName("\"NaN\" → Double.NaN")
        void nanString() {
            assertEquals(Double.NaN, GenericSettings.parseDoubleSafe("NaN"));
        }

        @Test
        @DisplayName("\"Infinity\" → Double.POSITIVE_INFINITY")
        void infinityString() {
            assertEquals(Double.POSITIVE_INFINITY, GenericSettings.parseDoubleSafe("Infinity"));
        }
    }
}
