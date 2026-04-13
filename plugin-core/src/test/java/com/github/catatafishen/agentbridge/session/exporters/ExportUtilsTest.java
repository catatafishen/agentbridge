package com.github.catatafishen.agentbridge.session.exporters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExportUtils}.
 */
class ExportUtilsTest {

    // ── sanitizeToolName ──────────────────────────────────────────────────────

    @Test
    void emptyStringBecomesUnknownTool() {
        assertEquals("unknown_tool", ExportUtils.sanitizeToolName(""));
    }

    @Test
    void plainAlphanumericPassesThrough() {
        assertEquals("read_file", ExportUtils.sanitizeToolName("read_file"));
    }

    @Test
    void spacesAndDotsReplacedWithUnderscores() {
        String result = ExportUtils.sanitizeToolName("git add src/Foo.java");
        assertTrue(result.matches("[a-zA-Z0-9_-]+"), "result must match [a-zA-Z0-9_-]+: " + result);
    }

    @Test
    void runsOfThreePlusUnderscoresCollapsedToTwo() {
        // Three underscores become two (preserving the __ MCP separator convention)
        assertEquals("a__b", ExportUtils.sanitizeToolName("a___b"));
        assertEquals("a__b", ExportUtils.sanitizeToolName("a____b"));
        assertEquals("a__b", ExportUtils.sanitizeToolName("a_____b"));
    }

    @Test
    void doubleUnderscorePreserved() {
        assertEquals("agentbridge__read_file", ExportUtils.sanitizeToolName("agentbridge__read_file"));
    }

    @Test
    void leadingUnderscoreStripped() {
        assertEquals("foo", ExportUtils.sanitizeToolName("_foo"));
        // leading space becomes underscore, which then gets stripped
        String result = ExportUtils.sanitizeToolName(" foo");
        assertTrue(result.startsWith("f"), "result should not start with underscore: " + result);
    }

    @Test
    void trailingUnderscoreStripped() {
        assertEquals("foo", ExportUtils.sanitizeToolName("foo_"));
    }

    @Test
    void longNameTruncatedTo200Chars() {
        String longName = "a".repeat(300);
        String result = ExportUtils.sanitizeToolName(longName);
        assertEquals(200, result.length());
    }

    @Test
    void nameExactly200CharsNotTruncated() {
        String name = "a".repeat(200);
        assertEquals(name, ExportUtils.sanitizeToolName(name));
    }

    @Test
    void hyphensAllowedInResult() {
        assertEquals("my-tool", ExportUtils.sanitizeToolName("my-tool"));
    }

    @Test
    void allInvalidCharsResultingInEmptyStringBecomesUnknownTool() {
        // A single special char becomes single underscore → leading/trailing stripped → empty → unknown_tool
        assertEquals("unknown_tool", ExportUtils.sanitizeToolName("!"));
    }

    @ParameterizedTest
    @CsvSource({
        "git status, g",
        "agentbridge-read_file, a",
        "Viewing ChatConsolePanel, V",
    })
    void typicalInputsProduceValidToolNames(String input, String expectedFirstChar) {
        String result = ExportUtils.sanitizeToolName(input);
        assertTrue(result.matches("[a-zA-Z0-9_-]+"),
            "result must match [a-zA-Z0-9_-]+: " + result);
        assertEquals(expectedFirstChar.charAt(0), result.charAt(0),
            "first char mismatch for input: " + input);
    }

    // ── normalizeToolNameForCodex ────────────────────────────────────────────

    @Test
    void codexStripsDashPrefix() {
        assertEquals("agentbridge_read_file",
            ExportUtils.normalizeToolNameForCodex("agentbridge-read_file"));
    }

    @Test
    void codexStripsUnderscorePrefix() {
        assertEquals("agentbridge_read_file",
            ExportUtils.normalizeToolNameForCodex("agentbridge_read_file"));
    }

    @Test
    void codexStripsKiroPrefix() {
        assertEquals("agentbridge_read_file",
            ExportUtils.normalizeToolNameForCodex("@agentbridge/read_file"));
    }

    @Test
    void codexAddsCanonicalPrefix() {
        assertEquals("agentbridge_git_status",
            ExportUtils.normalizeToolNameForCodex("git_status"));
    }

    @Test
    void codexSanitizesBaseName() {
        // "my tool!@#" → sanitize: "my_tool___" → collapse: "my_tool__" → strip trailing _: "my_tool_"
        assertEquals("agentbridge_my_tool_",
            ExportUtils.normalizeToolNameForCodex("my tool!@#"));
    }

    @Test
    void codexIdempotentForCanonicalName() {
        String canonical = "agentbridge_write_file";
        assertEquals(canonical, ExportUtils.normalizeToolNameForCodex(canonical));
    }

    // ── sessionsDir ───────────────────────────────────────────────────────────

    @Test
    void sessionsDirWithNullBasePathReturnsRelativePath() {
        File dir = ExportUtils.sessionsDir(null);
        assertEquals(".agent-work/sessions", dir.getPath().replace('\\', '/'));
    }

    @Test
    void sessionsDirWithEmptyBasePathReturnsRelativePath() {
        File dir = ExportUtils.sessionsDir("");
        assertEquals(".agent-work/sessions", dir.getPath().replace('\\', '/'));
    }

    @Test
    void sessionsDirWithAbsoluteBasePathCombinesCorrectly() {
        File dir = ExportUtils.sessionsDir("/projects/myapp");
        assertTrue(dir.getPath().endsWith(".agent-work/sessions".replace('/', File.separatorChar)),
            "Expected path to end with .agent-work/sessions, got: " + dir.getPath());
    }

    @Test
    void sessionsDirDoesNotRequireDirectoryToExist() {
        File dir = ExportUtils.sessionsDir("/nonexistent/path");
        // just verifies no exception thrown and returns a File
        assertTrue(dir.getPath().contains(".agent-work"));
    }

    // ── sanitizeToolName — additional edge cases ─────────────────────────────

    @Test
    void unicodeCharactersReplacedWithUnderscores() {
        // Unicode chars (em-dash, smart quotes, emoji) should all be replaced
        String result = ExportUtils.sanitizeToolName("tool\u2014name");
        assertTrue(result.matches("[a-zA-Z0-9_-]+"),
            "result must match [a-zA-Z0-9_-]+: " + result);
    }

    @Test
    void multipleConsecutiveSpecialCharsCollapsed() {
        // "a!!!b" → "a___b" → collapse 3+ → "a__b"
        assertEquals("a__b", ExportUtils.sanitizeToolName("a!!!b"));
    }

    @Test
    void truncationThenTrailingUnderscoreStripped() {
        // Name that after truncation to 200 ends with an underscore
        String base = "a".repeat(199) + "!"; // 200 chars → last char becomes "_"
        String result = ExportUtils.sanitizeToolName(base);
        // After sanitize: "aaa...a_" (200 chars), then trailing underscore stripped → 199 chars
        assertFalse(result.endsWith("_"), "trailing underscore should be stripped: " + result);
        assertTrue(result.length() <= 200);
    }

    @Test
    void onlySpecialCharsLongerProducesUnknownTool() {
        // "!!!" → "___" → collapse → "__" → strip leading → "" → strip trailing already empty → unknown_tool
        assertEquals("unknown_tool", ExportUtils.sanitizeToolName("!!!"));
    }

    @Test
    void dashesPreservedInMiddle() {
        assertEquals("a-b-c", ExportUtils.sanitizeToolName("a-b-c"));
    }

    // ── normalizeToolNameForCodex — additional edge cases ────────────────────

    @Test
    void codexWithEmptyInputAddsPrefix() {
        String result = ExportUtils.normalizeToolNameForCodex("");
        assertEquals("agentbridge_unknown_tool", result);
    }

    @Test
    void codexWithOnlyPrefixAddsBack() {
        // "agentbridge-" with no base → sanitize("") → "unknown_tool"
        assertEquals("agentbridge_unknown_tool",
            ExportUtils.normalizeToolNameForCodex("agentbridge-"));
    }

    @Test
    void codexHandlesDoublePrefix() {
        // Only the first prefix is stripped
        assertEquals("agentbridge_agentbridge-read_file",
            ExportUtils.normalizeToolNameForCodex("agentbridge-agentbridge-read_file"));
    }
}
