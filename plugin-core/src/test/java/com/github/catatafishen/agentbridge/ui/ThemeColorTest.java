package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemeColorTest {

    // ── Enum values ─────────────────────────────────────────────────

    @Nested
    class EnumValues {

        @Test
        void hasExactly11Entries() {
            assertEquals(11, ThemeColor.values().length);
        }

        @Test
        void everyEntry_hasNonNullDisplayName() {
            for (ThemeColor tc : ThemeColor.values()) {
                assertNotNull(tc.getDisplayName(),
                    tc.name() + " should have a non-null displayName");
            }
        }

        @Test
        void everyEntry_hasNonNullColor() {
            for (ThemeColor tc : ThemeColor.values()) {
                assertNotNull(tc.getColor(),
                    tc.name() + " should have a non-null color");
            }
        }

        @Test
        void displayNames_matchExpected() {
            assertEquals("Teal", ThemeColor.TEAL.getDisplayName());
            assertEquals("Amber", ThemeColor.AMBER.getDisplayName());
            assertEquals("Purple", ThemeColor.PURPLE.getDisplayName());
            assertEquals("Pink", ThemeColor.PINK.getDisplayName());
            assertEquals("Cyan", ThemeColor.CYAN.getDisplayName());
            assertEquals("Lime", ThemeColor.LIME.getDisplayName());
            assertEquals("Coral", ThemeColor.CORAL.getDisplayName());
            assertEquals("Blue", ThemeColor.BLUE.getDisplayName());
            assertEquals("Green", ThemeColor.GREEN.getDisplayName());
            assertEquals("Gray", ThemeColor.GRAY.getDisplayName());
            assertEquals("Red", ThemeColor.RED.getDisplayName());
        }
    }

    // ── fromKey ─────────────────────────────────────────────────────

    @Nested
    class FromKey {

        @Test
        void teal_returnsThemeColorTeal() {
            assertSame(ThemeColor.TEAL, ThemeColor.fromKey("TEAL"));
        }

        @Test
        void amber_returnsThemeColorAmber() {
            assertSame(ThemeColor.AMBER, ThemeColor.fromKey("AMBER"));
        }

        @Test
        void red_returnsThemeColorRed() {
            assertSame(ThemeColor.RED, ThemeColor.fromKey("RED"));
        }

        @Test
        void nullKey_returnsNull() {
            assertNull(ThemeColor.fromKey(null));
        }

        @Test
        void nonexistentKey_returnsNull() {
            assertNull(ThemeColor.fromKey("nonexistent"));
        }

        @Test
        void emptyString_returnsNull() {
            assertNull(ThemeColor.fromKey(""));
        }

        @Test
        void lowercaseKey_returnsNull_caseSensitive() {
            assertNull(ThemeColor.fromKey("teal"));
        }
    }

    // ── Color mapping ───────────────────────────────────────────────

    @Nested
    class ColorMapping {

        @Test
        void teal_usesSaColors0() {
            assertSame(ChatTheme.INSTANCE.getSA_COLORS()[0], ThemeColor.TEAL.getColor());
        }

        @Test
        void amber_usesSaColors1() {
            assertSame(ChatTheme.INSTANCE.getSA_COLORS()[1], ThemeColor.AMBER.getColor());
        }

        @Test
        void green_usesAgentColor() {
            assertSame(ChatTheme.INSTANCE.getAGENT_COLOR(), ThemeColor.GREEN.getColor());
        }

        @Test
        void gray_usesThinkColor() {
            assertSame(ChatTheme.INSTANCE.getTHINK_COLOR(), ThemeColor.GRAY.getColor());
        }

        @Test
        void red_usesErrorColor() {
            assertSame(ChatTheme.INSTANCE.getERROR_COLOR(), ThemeColor.RED.getColor());
        }
    }
}
