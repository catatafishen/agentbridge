package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link MacroToolRegistrar} static helper methods.
 * These tests are pure unit tests — no IntelliJ platform context required.
 */
class MacroToolRegistrarTest {

    // ── sanitizeToolName ──────────────────────────────────────────────────────

    @Test
    void sanitize_simpleAsciiName_addsMacroPrefix() {
        assertEquals("macro_my_macro", MacroToolRegistrar.sanitizeToolName("My Macro"));
    }

    @Test
    void sanitize_alreadyLowercase_addsPrefix() {
        assertEquals("macro_clean_build", MacroToolRegistrar.sanitizeToolName("clean_build"));
    }

    @Test
    void sanitize_specialCharsReplacedWithUnderscore() {
        assertEquals("macro_run_test_suite", MacroToolRegistrar.sanitizeToolName("Run/Test Suite!"));
    }

    @Test
    void sanitize_consecutiveSpecialCharsCollapsed() {
        // "Format & Lint!!" → lowercase → replace non-alphanumeric sequences → strip trailing _
        assertEquals("macro_format_lint", MacroToolRegistrar.sanitizeToolName("Format & Lint!!"));
    }

    @Test
    void sanitize_leadingAndTrailingSpecialCharsStripped() {
        assertEquals("macro_hello_world", MacroToolRegistrar.sanitizeToolName("__hello__world__"));
    }

    @Test
    void sanitize_emptyName_producesUnnamed() {
        assertEquals("macro_unnamed", MacroToolRegistrar.sanitizeToolName(""));
    }

    @Test
    void sanitize_onlySpecialChars_producesUnnamed() {
        assertEquals("macro_unnamed", MacroToolRegistrar.sanitizeToolName("!!!"));
    }

    @Test
    void sanitize_unicodeCharsReplaced() {
        assertEquals("macro_caf", MacroToolRegistrar.sanitizeToolName("café"));
    }

    @Test
    void sanitize_numberOnly_isPreserved() {
        assertEquals("macro_42", MacroToolRegistrar.sanitizeToolName("42"));
    }

    @Test
    void sanitize_mixedCaseWithNumbers() {
        assertEquals("macro_refactor_v2", MacroToolRegistrar.sanitizeToolName("Refactor V2"));
    }
}
