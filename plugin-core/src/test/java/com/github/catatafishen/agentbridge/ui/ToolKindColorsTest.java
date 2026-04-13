package com.github.catatafishen.agentbridge.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.UIUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolKindColorsTest {

    /**
     * Returns {@code true} when the IntelliJ platform is fully initialised
     * (e.g. inside a {@code BasePlatformTestCase}).  Plain JUnit runs have
     * no Application and UIUtil cannot initialise.
     */
    static boolean isPlatformAvailable() {
        try {
            return ApplicationManager.getApplication() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── toHex ────────────────────────────────────────────────────────

    @Nested
    class ToHex {

        @Test
        void tealColor() {
            assertEquals("#3a9595", ToolKindColors.toHex(new Color(0x3A, 0x95, 0x95)));
        }

        @Test
        void pureRed() {
            assertEquals("#ff0000", ToolKindColors.toHex(new Color(255, 0, 0)));
        }

        @Test
        void black() {
            assertEquals("#000000", ToolKindColors.toHex(new Color(0, 0, 0)));
        }

        @Test
        void white() {
            assertEquals("#ffffff", ToolKindColors.toHex(new Color(255, 255, 255)));
        }

        @Test
        void mixedBlueGreen() {
            assertEquals("#0080ff", ToolKindColors.toHex(new Color(0, 128, 255)));
        }
    }

    // ── tintedBackground ─────────────────────────────────────────────

    @Nested
    @EnabledIf("com.github.catatafishen.agentbridge.ui.ToolKindColorsTest#isPlatformAvailable")
    class TintedBackground {

        @Test
        void alphaZero_returnsPanelBackground() {
            Color base = UIUtil.getPanelBackground();
            Color result = ToolKindColors.tintedBackground(new Color(255, 0, 0), 0.0);
            assertEquals(base.getRed(), result.getRed());
            assertEquals(base.getGreen(), result.getGreen());
            assertEquals(base.getBlue(), result.getBlue());
        }

        @Test
        void alphaOne_returnsInputColor() {
            Color input = new Color(100, 150, 200);
            Color result = ToolKindColors.tintedBackground(input, 1.0);
            assertEquals(input.getRed(), result.getRed());
            assertEquals(input.getGreen(), result.getGreen());
            assertEquals(input.getBlue(), result.getBlue());
        }

        @Test
        void alphaHalf_blendsMidpoint() {
            Color base = UIUtil.getPanelBackground();
            Color input = new Color(200, 100, 0);
            Color result = ToolKindColors.tintedBackground(input, 0.5);

            int expectedRed = (int) (input.getRed() * 0.5 + base.getRed() * 0.5);
            int expectedGreen = (int) (input.getGreen() * 0.5 + base.getGreen() * 0.5);
            int expectedBlue = (int) (input.getBlue() * 0.5 + base.getBlue() * 0.5);

            assertEquals(expectedRed, result.getRed());
            assertEquals(expectedGreen, result.getGreen());
            assertEquals(expectedBlue, result.getBlue());
        }
    }

    // ── null-settings fall back to defaults ───────────────────────────

    @Nested
    class NullSettingsDefaults {

        @Test
        void readColor_returnsDefaultRead() {
            assertSame(ToolKindColors.DEFAULT_READ, ToolKindColors.readColor(null));
        }

        @Test
        void searchColor_returnsDefaultSearch() {
            assertSame(ToolKindColors.DEFAULT_SEARCH, ToolKindColors.searchColor(null));
        }

        @Test
        void editColor_returnsDefaultEdit() {
            assertSame(ToolKindColors.DEFAULT_EDIT, ToolKindColors.editColor(null));
        }

        @Test
        void executeColor_returnsDefaultExecute() {
            assertSame(ToolKindColors.DEFAULT_EXECUTE, ToolKindColors.executeColor(null));
        }
    }
}
