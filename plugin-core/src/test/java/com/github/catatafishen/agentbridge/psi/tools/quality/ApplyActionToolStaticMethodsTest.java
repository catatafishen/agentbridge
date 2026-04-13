package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
