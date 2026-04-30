package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApplyActionTool#formatApplyResult}.
 */
class ApplyActionToolStaticMethodsTest {

    @Nested
    @DisplayName("formatApplyResult")
    class FormatApplyResult {

        @Test
        @DisplayName("applied=true with diff starts with 'Applied action:' and contains diff")
        void appliedWithDiff() {
            String result = ApplyActionTool.formatApplyResult(
                "Import class", "src/Main.java", 5, "- old\n+ new", true
            );

            assertTrue(result.startsWith("Applied action: Import class"), result);
            assertTrue(result.contains("File: src/Main.java line 5"), result);
            assertTrue(result.contains("- old\n+ new"), result);
        }

        @Test
        @DisplayName("applied=false with diff starts with 'Applied with option'")
        void appliedWithOptionAndDiff() {
            String result = ApplyActionTool.formatApplyResult(
                "Import class", "src/Main.java", 5, "- old\n+ new", false
            );

            assertTrue(result.startsWith("Applied with option"), result);
            assertTrue(result.contains("File: src/Main.java line 5"), result);
            assertTrue(result.contains("- old\n+ new"), result);
        }

        @Test
        @DisplayName("applied=true with empty diff shows '(no file changes)'")
        void appliedNoChanges() {
            String result = ApplyActionTool.formatApplyResult(
                "Import class", "src/Main.java", 5, "", true
            );

            assertTrue(result.contains("Applied"), result);
            assertTrue(result.contains("action: Import class"), result);
            assertTrue(result.contains("(no file changes)"), result);
        }

        @Test
        @DisplayName("applied=false with empty diff shows 'Selected option for action:'")
        void selectedOptionNoChanges() {
            String result = ApplyActionTool.formatApplyResult(
                "Import class", "src/Main.java", 5, "", false
            );

            assertTrue(result.contains("Selected option for"), result);
            assertTrue(result.contains("action: Import class"), result);
            assertTrue(result.contains("(no file changes)"), result);
        }
    }

    @Nested
    @DisplayName("parseImportSimpleName")
    class ParseImportSimpleName {

        @Test
        @DisplayName("returns simple class name for English action name")
        void englishActionName() {
            assertEquals("Cell", ApplyActionTool.parseImportSimpleName("Import class 'Cell'"));
            assertEquals("ArrayList", ApplyActionTool.parseImportSimpleName("Import class 'ArrayList'"));
        }

        @Test
        @DisplayName("returns null for non-import action names")
        void notAnImportAction() {
            assertNull(ApplyActionTool.parseImportSimpleName("Optimize imports"));
            assertNull(ApplyActionTool.parseImportSimpleName("Rename 'foo' to 'bar'"));
            assertNull(ApplyActionTool.parseImportSimpleName(""));
        }

        @Test
        @DisplayName("returns null for null input")
        void nullInput() {
            assertNull(ApplyActionTool.parseImportSimpleName(null));
        }

        @Test
        @DisplayName("returns null for malformed action name (no closing quote)")
        void noClosingQuote() {
            assertNull(ApplyActionTool.parseImportSimpleName("Import class 'Cell"));
        }

        @Test
        @DisplayName("returns null for empty class name")
        void emptyClassName() {
            assertNull(ApplyActionTool.parseImportSimpleName("Import class ''"));
        }
    }

    @Nested
    @DisplayName("formatAmbiguousImportError")
    class FormatAmbiguousImportError {

        @Test
        @DisplayName("lists all candidates when count is small, sorted alphabetically")
        void smallList() {
            String result = ApplyActionTool.formatAmbiguousImportError(
                "Cell", List.of("z.Cell", "a.Cell", "m.Cell")
            );

            assertTrue(result.startsWith("Error: Import for 'Cell' is ambiguous (3 candidates: "), result);
            // Sorted alphabetically
            int aIdx = result.indexOf("a.Cell");
            int mIdx = result.indexOf("m.Cell");
            int zIdx = result.indexOf("z.Cell");
            assertTrue(aIdx > 0 && aIdx < mIdx && mIdx < zIdx, "Candidates should be sorted: " + result);
            assertTrue(result.contains("edit_text"), result);
            assertFalse(result.contains("more)"), "No 'more' suffix when all listed: " + result);
        }

        @Test
        @DisplayName("truncates with 'N more' when exceeding the display cap")
        void truncatesLongList() {
            List<String> many = List.of(
                "p1.Cell", "p2.Cell", "p3.Cell", "p4.Cell", "p5.Cell", "p6.Cell", "p7.Cell"
            );

            String result = ApplyActionTool.formatAmbiguousImportError("Cell", many);

            assertTrue(result.contains("(7 candidates: "), result);
            assertTrue(result.contains("p1.Cell"), result);
            assertTrue(result.contains("p5.Cell"), result);
            assertTrue(result.contains("… (2 more)"), result);
        }

        @Test
        @DisplayName("error message points the user to edit_text")
        void mentionsEditText() {
            String result = ApplyActionTool.formatAmbiguousImportError("X", List.of("a.X", "b.X"));
            assertTrue(result.contains("edit_text"), result);
            assertTrue(result.contains("freeze") || result.contains("non-interactively"), result);
        }
    }
}
