package com.github.catatafishen.agentbridge.ui;

import kotlin.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolCallArgParser")
class ToolCallArgParserTest {

    private final ToolCallArgParser parser = ToolCallArgParser.INSTANCE;

    // ── isJson ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isJson")
    class IsJson {

        @Test
        @DisplayName("returns true for simple JSON object")
        void simpleObject() {
            assertTrue(parser.isJson("{\"key\":\"value\"}"));
        }

        @Test
        @DisplayName("returns true for empty JSON object")
        void emptyObject() {
            assertTrue(parser.isJson("{}"));
        }

        @Test
        @DisplayName("returns true for JSON array")
        void simpleArray() {
            assertTrue(parser.isJson("[1,2,3]"));
        }

        @Test
        @DisplayName("returns true for empty JSON array")
        void emptyArray() {
            assertTrue(parser.isJson("[]"));
        }

        @Test
        @DisplayName("returns true for nested JSON object")
        void nestedObject() {
            assertTrue(parser.isJson("{\"a\":{\"b\":[1,2]}}"));
        }

        @Test
        @DisplayName("returns false for plain text")
        void plainText() {
            assertFalse(parser.isJson("hello world"));
        }

        @Test
        @DisplayName("returns false for blank string")
        void blankString() {
            assertFalse(parser.isJson("   "));
        }

        @Test
        @DisplayName("returns false for string starting with { but not ending with }")
        void mismatchedBraces() {
            assertFalse(parser.isJson("{\"key\":\"value\""));
        }

        @Test
        @DisplayName("returns false for string starting with [ but not ending with ]")
        void mismatchedBrackets() {
            assertFalse(parser.isJson("[1,2,3"));
        }

        @Test
        @DisplayName("returns false for empty string")
        void emptyString() {
            assertFalse(parser.isJson(""));
        }

        @Test
        @DisplayName("returns true for array of objects")
        void arrayOfObjects() {
            assertTrue(parser.isJson("[{\"a\":1},{\"b\":2}]"));
        }
    }

    // ── prettyJson ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("prettyJson")
    class PrettyJson {

        @Test
        @DisplayName("formats valid JSON object with indentation")
        void formatsValidObject() {
            String result = parser.prettyJson("{\"a\":1,\"b\":2}");
            assertTrue(result.contains("\n"), "pretty-printed JSON should contain newlines");
            assertTrue(result.contains("  "), "pretty-printed JSON should contain indentation");
            assertTrue(result.contains("\"a\""), "should preserve key 'a'");
            assertTrue(result.contains("\"b\""), "should preserve key 'b'");
        }

        @Test
        @DisplayName("formats valid JSON array with indentation")
        void formatsValidArray() {
            String result = parser.prettyJson("[1,2,3]");
            assertTrue(result.contains("\n"), "pretty-printed JSON should contain newlines");
        }

        @Test
        @DisplayName("returns original text for invalid JSON")
        void returnsOriginalForInvalid() {
            String input = "not json at all";
            assertEquals(input, parser.prettyJson(input));
        }

        @Test
        @DisplayName("does not throw for empty string")
        void doesNotThrowForEmpty() {
            assertDoesNotThrow(() -> parser.prettyJson(""));
        }

        @Test
        @DisplayName("formats nested JSON correctly")
        void formatsNestedJson() {
            String result = parser.prettyJson("{\"outer\":{\"inner\":true}}");
            assertTrue(result.contains("\"outer\""));
            assertTrue(result.contains("\"inner\""));
            assertTrue(result.contains("true"));
        }
    }

    // ── extractFilePathFromArgs ─────────────────────────────────────────

    @Nested
    @DisplayName("extractFilePathFromArgs")
    class ExtractFilePathFromArgs {

        @Test
        @DisplayName("extracts 'path' key")
        void extractsPathKey() {
            assertEquals("src/Main.java",
                parser.extractFilePathFromArgs("{\"path\":\"src/Main.java\"}"));
        }

        @Test
        @DisplayName("extracts 'file' key")
        void extractsFileKey() {
            assertEquals("build.gradle",
                parser.extractFilePathFromArgs("{\"file\":\"build.gradle\"}"));
        }

        @Test
        @DisplayName("extracts 'filename' key")
        void extractsFilenameKey() {
            assertEquals("test.txt",
                parser.extractFilePathFromArgs("{\"filename\":\"test.txt\"}"));
        }

        @Test
        @DisplayName("extracts 'filepath' key")
        void extractsFilepathKey() {
            assertEquals("/tmp/foo.kt",
                parser.extractFilePathFromArgs("{\"filepath\":\"/tmp/foo.kt\"}"));
        }

        @Test
        @DisplayName("prefers 'path' over 'file' when both present")
        void prefersPathOverFile() {
            assertEquals("first.java",
                parser.extractFilePathFromArgs("{\"path\":\"first.java\",\"file\":\"second.java\"}"));
        }

        @Test
        @DisplayName("returns null for null arguments")
        void returnsNullForNull() {
            assertNull(parser.extractFilePathFromArgs(null));
        }

        @Test
        @DisplayName("returns null for blank arguments")
        void returnsNullForBlank() {
            assertNull(parser.extractFilePathFromArgs("   "));
        }

        @Test
        @DisplayName("returns null for invalid JSON")
        void returnsNullForInvalidJson() {
            assertNull(parser.extractFilePathFromArgs("not json"));
        }

        @Test
        @DisplayName("returns null when no path key is present")
        void returnsNullWhenNoPathKey() {
            assertNull(parser.extractFilePathFromArgs("{\"query\":\"something\"}"));
        }

        @Test
        @DisplayName("returns null for JSON array (not object)")
        void returnsNullForJsonArray() {
            assertNull(parser.extractFilePathFromArgs("[\"path\",\"test.java\"]"));
        }

        @Test
        @DisplayName("returns null for empty JSON object")
        void returnsNullForEmptyObject() {
            assertNull(parser.extractFilePathFromArgs("{}"));
        }

        @Test
        @DisplayName("ignores non-primitive path values")
        void ignoresNonPrimitivePathValue() {
            assertNull(parser.extractFilePathFromArgs("{\"path\":{\"nested\":true}}"));
        }
    }

    // ── extractTaskCompleteSummary ──────────────────────────────────────

    @Nested
    @DisplayName("extractTaskCompleteSummary")
    class ExtractTaskCompleteSummary {

        @Test
        @DisplayName("extracts summary from valid JSON")
        void extractsSummary() {
            assertEquals("All done!",
                parser.extractTaskCompleteSummary("{\"summary\":\"All done!\"}"));
        }

        @Test
        @DisplayName("returns empty string for null")
        void returnsEmptyForNull() {
            assertEquals("", parser.extractTaskCompleteSummary(null));
        }

        @Test
        @DisplayName("returns empty string for blank")
        void returnsEmptyForBlank() {
            assertEquals("", parser.extractTaskCompleteSummary("  "));
        }

        @Test
        @DisplayName("returns raw text when no summary key")
        void returnsRawWhenNoSummaryKey() {
            String raw = "{\"result\":\"ok\"}";
            assertEquals(raw, parser.extractTaskCompleteSummary(raw));
        }

        @Test
        @DisplayName("returns raw text for invalid JSON")
        void returnsRawForInvalidJson() {
            String raw = "plain text summary";
            assertEquals(raw, parser.extractTaskCompleteSummary(raw));
        }

        @Test
        @DisplayName("returns raw text for JSON array")
        void returnsRawForJsonArray() {
            String raw = "[\"a\",\"b\"]";
            assertEquals(raw, parser.extractTaskCompleteSummary(raw));
        }

        @Test
        @DisplayName("ignores non-primitive summary value")
        void ignoresNonPrimitiveSummary() {
            String raw = "{\"summary\":{\"detail\":\"nested\"}}";
            assertEquals(raw, parser.extractTaskCompleteSummary(raw));
        }
    }

    // ── extractDiffFromArgs ─────────────────────────────────────────────

    @Nested
    @DisplayName("extractDiffFromArgs")
    class ExtractDiffFromArgs {

        @Test
        @DisplayName("extracts old_str and new_str pair")
        void extractsOldAndNewStr() {
            Pair<String, String> result =
                parser.extractDiffFromArgs("{\"old_str\":\"foo\",\"new_str\":\"bar\"}");
            assertNotNull(result);
            assertEquals("foo", result.getFirst());
            assertEquals("bar", result.getSecond());
        }

        @Test
        @DisplayName("returns pair when old_str is blank but new_str is not")
        void returnsWhenOldStrBlankNewStrNot() {
            Pair<String, String> result =
                parser.extractDiffFromArgs("{\"old_str\":\"\",\"new_str\":\"added\"}");
            assertNotNull(result);
            assertEquals("", result.getFirst());
            assertEquals("added", result.getSecond());
        }

        @Test
        @DisplayName("returns pair when new_str is blank but old_str is not")
        void returnsWhenNewStrBlankOldStrNot() {
            Pair<String, String> result =
                parser.extractDiffFromArgs("{\"old_str\":\"removed\",\"new_str\":\"\"}");
            assertNotNull(result);
            assertEquals("removed", result.getFirst());
            assertEquals("", result.getSecond());
        }

        @Test
        @DisplayName("returns null when both old_str and new_str are blank")
        void returnsNullWhenBothBlank() {
            assertNull(parser.extractDiffFromArgs("{\"old_str\":\"\",\"new_str\":\"\"}"));
        }

        @Test
        @DisplayName("returns null for null arguments")
        void returnsNullForNull() {
            assertNull(parser.extractDiffFromArgs(null));
        }

        @Test
        @DisplayName("returns null for blank arguments")
        void returnsNullForBlank() {
            assertNull(parser.extractDiffFromArgs("  "));
        }

        @Test
        @DisplayName("returns null for invalid JSON")
        void returnsNullForInvalidJson() {
            assertNull(parser.extractDiffFromArgs("not json"));
        }

        @Test
        @DisplayName("returns null when old_str is missing")
        void returnsNullWhenOldStrMissing() {
            assertNull(parser.extractDiffFromArgs("{\"new_str\":\"bar\"}"));
        }

        @Test
        @DisplayName("returns null when new_str is missing")
        void returnsNullWhenNewStrMissing() {
            assertNull(parser.extractDiffFromArgs("{\"old_str\":\"foo\"}"));
        }

        @Test
        @DisplayName("returns null when neither key present")
        void returnsNullWhenNeitherKey() {
            assertNull(parser.extractDiffFromArgs("{\"content\":\"something\"}"));
        }
    }

    // ── extractTabName ──────────────────────────────────────────────────

    @Nested
    @DisplayName("extractTabName")
    class ExtractTabName {

        @Test
        @DisplayName("extracts tab_name for run_in_terminal")
        void extractsTabNameForRunInTerminal() {
            assertEquals("my-tab",
                parser.extractTabName("run_in_terminal", "{\"tab_name\":\"my-tab\"}"));
        }

        @Test
        @DisplayName("extracts tab_name for read_terminal_output")
        void extractsTabNameForReadTerminalOutput() {
            assertEquals("term1",
                parser.extractTabName("read_terminal_output", "{\"tab_name\":\"term1\"}"));
        }

        @Test
        @DisplayName("extracts tab_name for write_terminal_input")
        void extractsTabNameForWriteTerminalInput() {
            assertEquals("input-tab",
                parser.extractTabName("write_terminal_input", "{\"tab_name\":\"input-tab\"}"));
        }

        @Test
        @DisplayName("extracts title for run_command")
        void extractsTitleForRunCommand() {
            assertEquals("Build Output",
                parser.extractTabName("run_command", "{\"title\":\"Build Output\"}"));
        }

        @Test
        @DisplayName("extracts tab_name for read_run_output")
        void extractsTabNameForReadRunOutput() {
            assertEquals("run-tab",
                parser.extractTabName("read_run_output", "{\"tab_name\":\"run-tab\"}"));
        }

        @Test
        @DisplayName("extracts tab_name for read_build_output")
        void extractsTabNameForReadBuildOutput() {
            assertEquals("build-tab",
                parser.extractTabName("read_build_output", "{\"tab_name\":\"build-tab\"}"));
        }

        @Test
        @DisplayName("extracts name for run_configuration")
        void extractsNameForRunConfiguration() {
            assertEquals("MyApp",
                parser.extractTabName("run_configuration", "{\"name\":\"MyApp\"}"));
        }

        @Test
        @DisplayName("extracts target for run_tests")
        void extractsTargetForRunTests() {
            assertEquals("com.example.FooTest",
                parser.extractTabName("run_tests", "{\"target\":\"com.example.FooTest\"}"));
        }

        @Test
        @DisplayName("returns null for unrecognized tool name")
        void returnsNullForUnrecognizedTool() {
            assertNull(parser.extractTabName("unknown_tool", "{\"tab_name\":\"x\"}"));
        }

        @Test
        @DisplayName("returns null for null arguments")
        void returnsNullForNullArgs() {
            assertNull(parser.extractTabName("run_in_terminal", null));
        }

        @Test
        @DisplayName("returns null for blank arguments")
        void returnsNullForBlankArgs() {
            assertNull(parser.extractTabName("run_in_terminal", "  "));
        }

        @Test
        @DisplayName("returns null for null baseName")
        void returnsNullForNullBaseName() {
            assertNull(parser.extractTabName(null, "{\"tab_name\":\"x\"}"));
        }

        @Test
        @DisplayName("returns null for invalid JSON arguments")
        void returnsNullForInvalidJson() {
            assertNull(parser.extractTabName("run_in_terminal", "not json"));
        }

        @Test
        @DisplayName("returns null when expected key is missing from args")
        void returnsNullWhenKeyMissing() {
            assertNull(parser.extractTabName("run_in_terminal", "{\"other\":\"val\"}"));
        }

        @Test
        @DisplayName("strips surrounding quotes from baseName")
        void stripsSurroundingQuotes() {
            assertEquals("my-tab",
                parser.extractTabName("'run_in_terminal'", "{\"tab_name\":\"my-tab\"}"));
        }

        @Test
        @DisplayName("strips double quotes from baseName")
        void stripsDoubleQuotes() {
            assertEquals("my-tab",
                parser.extractTabName("\"run_in_terminal\"", "{\"tab_name\":\"my-tab\"}"));
        }
    }

    // ── resolveToolWindowId ─────────────────────────────────────────────

    @Nested
    @DisplayName("resolveToolWindowId")
    class ResolveToolWindowId {

        // Terminal tools
        @ParameterizedTest(name = "\"{0}\" → Terminal")
        @ValueSource(strings = {"run_in_terminal", "read_terminal_output",
            "write_terminal_input", "list_terminals"})
        @DisplayName("terminal tools resolve to Terminal")
        void terminalTools(String toolName) {
            assertEquals("Terminal", parser.resolveToolWindowId(toolName));
        }

        // Run tools
        @ParameterizedTest(name = "\"{0}\" → Run")
        @ValueSource(strings = {"run_command", "read_run_output",
            "run_configuration", "run_tests"})
        @DisplayName("run tools resolve to Run")
        void runTools(String toolName) {
            assertEquals("Run", parser.resolveToolWindowId(toolName));
        }

        // Build tools
        @ParameterizedTest(name = "\"{0}\" → Build")
        @ValueSource(strings = {"read_build_output", "build_project"})
        @DisplayName("build tools resolve to Build")
        void buildTools(String toolName) {
            assertEquals("Build", parser.resolveToolWindowId(toolName));
        }

        @Test
        @DisplayName("returns null for unknown tool")
        void returnsNullForUnknown() {
            assertNull(parser.resolveToolWindowId("read_file"));
        }

        @Test
        @DisplayName("returns null for null baseName")
        void returnsNullForNull() {
            assertNull(parser.resolveToolWindowId(null));
        }

        @Test
        @DisplayName("strips surrounding quotes from baseName")
        void stripsSurroundingQuotes() {
            assertEquals("Terminal", parser.resolveToolWindowId("'run_in_terminal'"));
        }

        @Test
        @DisplayName("strips double quotes from baseName")
        void stripsDoubleQuotes() {
            assertEquals("Run", parser.resolveToolWindowId("\"run_command\""));
        }
    }

    // ── normalizeChipStatus ─────────────────────────────────────────────

    @Nested
    @DisplayName("normalizeChipStatus")
    class NormalizeChipStatus {

        @Test
        @DisplayName("'pending' maps to PENDING")
        void pendingStaysAsPending() {
            assertEquals(MessageFormatter.ChipStatus.PENDING,
                parser.normalizeChipStatus("pending"));
        }

        @Test
        @DisplayName("'running' maps to RUNNING")
        void runningStaysAsRunning() {
            assertEquals(MessageFormatter.ChipStatus.RUNNING,
                parser.normalizeChipStatus("running"));
        }

        @Test
        @DisplayName("'complete' maps to COMPLETE")
        void completeStaysAsComplete() {
            assertEquals(MessageFormatter.ChipStatus.COMPLETE,
                parser.normalizeChipStatus("complete"));
        }

        @Test
        @DisplayName("'failed' maps to FAILED")
        void failedStaysAsFailed() {
            assertEquals(MessageFormatter.ChipStatus.FAILED,
                parser.normalizeChipStatus("failed"));
        }

        @Test
        @DisplayName("'denied' maps to DENIED")
        void deniedStaysAsDenied() {
            assertEquals(MessageFormatter.ChipStatus.DENIED,
                parser.normalizeChipStatus("denied"));
        }

        @Test
        @DisplayName("'thinking' maps to THINKING")
        void thinkingStaysAsThinking() {
            assertEquals(MessageFormatter.ChipStatus.THINKING,
                parser.normalizeChipStatus("thinking"));
        }

        @Test
        @DisplayName("null maps to COMPLETE")
        void nullMapsToComplete() {
            assertEquals(MessageFormatter.ChipStatus.COMPLETE,
                parser.normalizeChipStatus(null));
        }

        @Test
        @DisplayName("'completed' maps to COMPLETE")
        void completedMapsToComplete() {
            assertEquals(MessageFormatter.ChipStatus.COMPLETE,
                parser.normalizeChipStatus("completed"));
        }

        @Test
        @DisplayName("unknown value maps to FAILED")
        void unknownMapsToFailed() {
            assertEquals(MessageFormatter.ChipStatus.FAILED,
                parser.normalizeChipStatus("some_unknown_status"));
        }

        @Test
        @DisplayName("empty string maps to FAILED")
        void emptyStringMapsToFailed() {
            assertEquals(MessageFormatter.ChipStatus.FAILED,
                parser.normalizeChipStatus(""));
        }

        @Test
        @DisplayName("'COMPLETE' (uppercase) maps to FAILED (case-sensitive)")
        void uppercaseMapsToFailed() {
            assertEquals(MessageFormatter.ChipStatus.FAILED,
                parser.normalizeChipStatus("COMPLETE"));
        }

        @Test
        @DisplayName("'error' maps to FAILED")
        void errorMapsToFailed() {
            assertEquals(MessageFormatter.ChipStatus.FAILED,
                parser.normalizeChipStatus("error"));
        }

        @Test
        @DisplayName("'done' maps to FAILED")
        void doneMapsToFailed() {
            assertEquals(MessageFormatter.ChipStatus.FAILED,
                parser.normalizeChipStatus("done"));
        }
    }
}
