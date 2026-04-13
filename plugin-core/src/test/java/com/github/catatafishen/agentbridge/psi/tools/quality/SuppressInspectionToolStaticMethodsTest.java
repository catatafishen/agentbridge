package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for extracted pure-logic static helpers in {@link SuppressInspectionTool}.
 * These methods are package-private and tested directly (no reflection needed).
 */
class SuppressInspectionToolStaticMethodsTest {

    // ── extractIndent ────────────────────────────────────────

    @Nested
    class ExtractIndent {

        @Test
        void noIndent() {
            assertEquals("", SuppressInspectionTool.extractIndent("int x = 5;"));
        }

        @Test
        void spacesOnly() {
            assertEquals("    ", SuppressInspectionTool.extractIndent("    int x = 5;"));
        }

        @Test
        void tabsOnly() {
            assertEquals("\t\t", SuppressInspectionTool.extractIndent("\t\tint x = 5;"));
        }

        @Test
        void mixedSpacesAndTabs() {
            assertEquals(" \t ", SuppressInspectionTool.extractIndent(" \t int x = 5;"));
        }

        @Test
        void emptyLine() {
            assertEquals("", SuppressInspectionTool.extractIndent(""));
        }

        @Test
        void allWhitespace() {
            assertEquals("   ", SuppressInspectionTool.extractIndent("   "));
        }

        @Test
        void singleSpace() {
            assertEquals(" ", SuppressInspectionTool.extractIndent(" x"));
        }
    }

    // ── buildSuppressComment ─────────────────────────────────

    @Nested
    class BuildSuppressComment {

        @Test
        void noIndent() {
            assertEquals("//noinspection SpellCheckingInspection\n",
                SuppressInspectionTool.buildSuppressComment("", "SpellCheckingInspection"));
        }

        @Test
        void withIndent() {
            assertEquals("    //noinspection unused\n",
                SuppressInspectionTool.buildSuppressComment("    ", "unused"));
        }

        @Test
        void withTabIndent() {
            assertEquals("\t//noinspection MyInspection\n",
                SuppressInspectionTool.buildSuppressComment("\t", "MyInspection"));
        }
    }

    // ── buildKotlinSuppressAnnotation ────────────────────────

    @Nested
    class BuildKotlinSuppressAnnotation {

        @Test
        void noIndent() {
            assertEquals("@Suppress(\"unused\")\n",
                SuppressInspectionTool.buildKotlinSuppressAnnotation("", "unused"));
        }

        @Test
        void withIndent() {
            assertEquals("    @Suppress(\"UNCHECKED_CAST\")\n",
                SuppressInspectionTool.buildKotlinSuppressAnnotation("    ", "UNCHECKED_CAST"));
        }

        @Test
        void preservesExactInspectionId() {
            assertEquals("  @Suppress(\"SpellCheckingInspection\")\n",
                SuppressInspectionTool.buildKotlinSuppressAnnotation("  ", "SpellCheckingInspection"));
        }
    }

    // ── formatCommentResult ──────────────────────────────────

    @Nested
    class FormatCommentResult {

        @Test
        void containsInspectionIdAndLine() {
            String result = SuppressInspectionTool.formatCommentResult("unused", 42);
            assertEquals("Added //noinspection unused comment at line 42", result);
        }

        @Test
        void lineOne() {
            String result = SuppressInspectionTool.formatCommentResult("SpellCheckingInspection", 1);
            assertEquals("Added //noinspection SpellCheckingInspection comment at line 1", result);
        }
    }

    // ── formatAnnotationResult ───────────────────────────────

    @Nested
    class FormatAnnotationResult {

        @Test
        void containsAnnotationAndLine() {
            String result = SuppressInspectionTool.formatAnnotationResult("unused", 10);
            assertEquals("Added @Suppress(\"unused\") at line 10", result);
        }

        @Test
        void lineOne() {
            String result = SuppressInspectionTool.formatAnnotationResult("UNCHECKED_CAST", 1);
            assertEquals("Added @Suppress(\"UNCHECKED_CAST\") at line 1", result);
        }
    }
}
