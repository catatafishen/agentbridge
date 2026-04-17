package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for static/pure utility methods in {@link ToolRenderers}.
 */
class ToolResultRendererTest {

    // ── parseDiffStats ────────────────────────────────────────

    @Nested
    class ParseDiffStats {

        @Test
        void parsesFullDiffStatLine() {
            ToolRenderers.DiffStats stats =
                    ToolRenderers.INSTANCE.parseDiffStats(" 3 files changed, 42 insertions(+), 7 deletions(-)");

            assertEquals("3 files changed", stats.getFiles());
            assertEquals("42", stats.getInsertions());
            assertEquals("7", stats.getDeletions());
        }

        @Test
        void parsesInsertionsOnly() {
            ToolRenderers.DiffStats stats =
                    ToolRenderers.INSTANCE.parseDiffStats(" 1 file changed, 10 insertions(+)");

            assertEquals("1 file changed", stats.getFiles());
            assertEquals("10", stats.getInsertions());
            assertEquals("", stats.getDeletions());
        }

        @Test
        void parsesDeletionsOnly() {
            ToolRenderers.DiffStats stats =
                    ToolRenderers.INSTANCE.parseDiffStats(" 1 file changed, 5 deletions(-)");

            assertEquals("1 file changed", stats.getFiles());
            assertEquals("", stats.getInsertions());
            assertEquals("5", stats.getDeletions());
        }

        @Test
        void fallsBackToFullLineWhenNoMatch() {
            ToolRenderers.DiffStats stats =
                    ToolRenderers.INSTANCE.parseDiffStats("no match text");

            assertEquals("no match text", stats.getFiles());
            assertEquals("", stats.getInsertions());
            assertEquals("", stats.getDeletions());
        }

        @Test
        void parsesLargeNumbers() {
            ToolRenderers.DiffStats stats =
                    ToolRenderers.INSTANCE.parseDiffStats(" 12 files changed, 100 insertions(+), 50 deletions(-)");

            assertEquals("12 files changed", stats.getFiles());
            assertEquals("100", stats.getInsertions());
            assertEquals("50", stats.getDeletions());
        }
    }

    // ── blendColor ────────────────────────────────────────────

    @Nested
    class BlendColor {

        @Test
        void alphaOneReturnsForeground() {
            Color result = ToolRenderers.INSTANCE.blendColor(Color.WHITE, Color.BLACK, 1.0);

            assertEquals(255, result.getRed());
            assertEquals(255, result.getGreen());
            assertEquals(255, result.getBlue());
        }

        @Test
        void alphaZeroReturnsBackground() {
            Color result = ToolRenderers.INSTANCE.blendColor(Color.WHITE, Color.BLACK, 0.0);

            assertEquals(0, result.getRed());
            assertEquals(0, result.getGreen());
            assertEquals(0, result.getBlue());
        }

        @Test
        void halfAlphaBlendsMidpoint() {
            Color result = ToolRenderers.INSTANCE.blendColor(Color.WHITE, Color.BLACK, 0.5);

            // (255 * 0.5 + 0 * 0.5) = 127.5 → truncated to 127
            assertEquals(127, result.getRed());
            assertEquals(127, result.getGreen());
            assertEquals(127, result.getBlue());
        }

        @Test
        void blendsRedAndBlue() {
            Color result = ToolRenderers.INSTANCE.blendColor(Color.RED, Color.BLUE, 0.5);

            // Red:   (255*0.5 + 0*0.5) = 127
            // Green: (0*0.5 + 0*0.5)   = 0
            // Blue:  (0*0.5 + 255*0.5) = 127
            assertEquals(127, result.getRed());
            assertEquals(0, result.getGreen());
            assertEquals(127, result.getBlue());
        }

        @Test
        void sameColorBlendReturnsSameColor() {
            Color color = new Color(100, 150, 200);

            Color atZero = ToolRenderers.INSTANCE.blendColor(color, color, 0.0);
            Color atHalf = ToolRenderers.INSTANCE.blendColor(color, color, 0.5);
            Color atOne = ToolRenderers.INSTANCE.blendColor(color, color, 1.0);

            assertEquals(color.getRed(), atZero.getRed());
            assertEquals(color.getGreen(), atZero.getGreen());
            assertEquals(color.getBlue(), atZero.getBlue());

            assertEquals(color.getRed(), atHalf.getRed());
            assertEquals(color.getGreen(), atHalf.getGreen());
            assertEquals(color.getBlue(), atHalf.getBlue());

            assertEquals(color.getRed(), atOne.getRed());
            assertEquals(color.getGreen(), atOne.getGreen());
            assertEquals(color.getBlue(), atOne.getBlue());
        }
    }

    // ── hasRenderer (null registry → builtin map lookup) ─────

    @Nested
    class HasRenderer {

        @Test
        void globHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("glob", null));
        }

        @Test
        void globCapitalizedHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("Glob", null));
        }

        @Test
        void editHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("edit", null));
        }

        @Test
        void nonexistentToolReturnsFalse() {
            assertFalse(ToolRenderers.INSTANCE.hasRenderer("nonexistent_tool", null));
        }

        @Test
        void updateTodoHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("update_todo", null));
        }

        @Test
        void todowriteHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("todowrite", null));
        }

        @Test
        void todoWriteCapitalizedHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("TodoWrite", null));
        }

        @Test
        void taskHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("task", null));
        }

        @Test
        void bashHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("bash", null));
        }

        @Test
        void listHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("list", null));
        }

        @Test
        void lsHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("ls", null));
        }

        @Test
        void patchHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("patch", null));
        }

        @Test
        void webFetchHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("webfetch", null));
        }

        @Test
        void webSearchHasRenderer() {
            assertTrue(ToolRenderers.INSTANCE.hasRenderer("websearch", null));
        }
    }
}
