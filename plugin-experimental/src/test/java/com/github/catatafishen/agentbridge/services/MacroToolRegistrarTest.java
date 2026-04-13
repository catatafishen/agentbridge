package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link MacroToolRegistrar#sanitizeToolName(String)}.
 * Pure unit tests — no IntelliJ platform context required.
 */
class MacroToolRegistrarTest {

    @Nested
    class SanitizeToolName {

        @Test
        void simpleName_addsMacroPrefix() {
            assertEquals("macro_hello", MacroToolRegistrar.sanitizeToolName("hello"));
        }

        @Test
        void mixedCase_isLowercased() {
            assertEquals("macro_helloworld", MacroToolRegistrar.sanitizeToolName("HelloWorld"));
        }

        @Test
        void specialChars_replacedWithUnderscore() {
            assertEquals("macro_run_test_suite", MacroToolRegistrar.sanitizeToolName("run/test!suite"));
        }

        @Test
        void consecutiveUnderscores_collapsed() {
            assertEquals("macro_a_b", MacroToolRegistrar.sanitizeToolName("a___b"));
        }

        @Test
        void leadingAndTrailingUnderscores_stripped() {
            assertEquals("macro_hello", MacroToolRegistrar.sanitizeToolName("_hello_"));
        }

        @Test
        void emptyString_producesMacroUnnamed() {
            assertEquals("macro_unnamed", MacroToolRegistrar.sanitizeToolName(""));
        }

        @Test
        void allSpecialChars_producesMacroUnnamed() {
            assertEquals("macro_unnamed", MacroToolRegistrar.sanitizeToolName("!!!@@@###"));
        }

        @Test
        void numbersPreserved() {
            assertEquals("macro_test123", MacroToolRegistrar.sanitizeToolName("test123"));
        }

        @Test
        void numericOnly_preserved() {
            assertEquals("macro_42", MacroToolRegistrar.sanitizeToolName("42"));
        }

        @Test
        void mixedCaseWithNumbers_lowercasedAndPrefixed() {
            assertEquals("macro_refactor_v2", MacroToolRegistrar.sanitizeToolName("Refactor V2"));
        }

        @Test
        void spacesReplacedAndCollapsed() {
            assertEquals("macro_my_macro", MacroToolRegistrar.sanitizeToolName("My Macro"));
        }

        @Test
        void unicodeCharsReplaced() {
            assertEquals("macro_caf", MacroToolRegistrar.sanitizeToolName("café"));
        }
    }
}
