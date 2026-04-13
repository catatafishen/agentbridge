package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GetActionOptionsTool#formatDialogOptions}.
 */
class GetActionOptionsToolStaticMethodsTest {

    @Nested
    @DisplayName("formatDialogOptions")
    class FormatDialogOptions {

        @Test
        @DisplayName("dialog with radio buttons formats correctly")
        void radioButtons() {
            var info = new DialogInterceptor.DialogInfo(
                List.of("Option A", "Option B"),
                List.of(),
                List.of(),
                List.of("OK", "Cancel")
            );

            String result = GetActionOptionsTool.formatDialogOptions("Rename", "src/Main.java", 10, info);

            assertTrue(result.contains("Radio buttons (mutually exclusive — pick one):"), result);
            assertTrue(result.contains("[0] Option A"), result);
            assertTrue(result.contains("[1] Option B"), result);
            assertTrue(result.contains("Dialog buttons: OK, Cancel"), result);
        }

        @Test
        @DisplayName("dialog with checkboxes formats correctly")
        void checkboxes() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(),
                List.of("Enable X", "Enable Y"),
                List.of(),
                List.of()
            );

            String result = GetActionOptionsTool.formatDialogOptions("Configure", "src/Main.java", 5, info);

            assertTrue(result.contains("Checkboxes (can combine):"), result);
            assertTrue(result.contains("• Enable X"), result);
            assertTrue(result.contains("• Enable Y"), result);
            assertFalse(result.contains("Radio buttons"), result);
        }

        @Test
        @DisplayName("dialog with text inputs formats correctly")
        void textInputs() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(),
                List.of(),
                List.of("default value"),
                List.of()
            );

            String result = GetActionOptionsTool.formatDialogOptions("Edit", "src/Main.java", 1, info);

            assertTrue(result.contains("Text fields (current values):"), result);
            assertTrue(result.contains("\"default value\""), result);
            assertFalse(result.contains("Radio buttons"), result);
            assertFalse(result.contains("Checkboxes"), result);
        }

        @Test
        @DisplayName("radio buttons include apply hint")
        void radioButtonsHint() {
            var info = new DialogInterceptor.DialogInfo(
                List.of("Option A"),
                List.of(),
                List.of(),
                List.of()
            );

            String result = GetActionOptionsTool.formatDialogOptions("Rename", "src/Main.java", 10, info);

            assertTrue(result.contains("To apply with a specific option"), result);
        }

        @Test
        @DisplayName("empty dialog has no section headers")
        void emptyDialog() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            String result = GetActionOptionsTool.formatDialogOptions("NoOp", "src/Main.java", 1, info);

            assertFalse(result.contains("Radio buttons"), result);
            assertFalse(result.contains("Checkboxes"), result);
            assertFalse(result.contains("Text fields"), result);
            assertFalse(result.contains("Dialog buttons:"), result);
        }

        @Test
        @DisplayName("mixed dialog with all sections present")
        void mixedDialog() {
            var info = new DialogInterceptor.DialogInfo(
                List.of("Radio 1"),
                List.of("Check 1"),
                List.of("text value"),
                List.of("OK", "Cancel")
            );

            String result = GetActionOptionsTool.formatDialogOptions("Complex", "src/Main.java", 42, info);

            assertTrue(result.contains("Radio buttons (mutually exclusive — pick one):"), result);
            assertTrue(result.contains("[0] Radio 1"), result);
            assertTrue(result.contains("Checkboxes (can combine):"), result);
            assertTrue(result.contains("• Check 1"), result);
            assertTrue(result.contains("Text fields (current values):"), result);
            assertTrue(result.contains("\"text value\""), result);
            assertTrue(result.contains("Dialog buttons: OK, Cancel"), result);
            assertTrue(result.contains("To apply with a specific option"), result);
        }
    }
}
