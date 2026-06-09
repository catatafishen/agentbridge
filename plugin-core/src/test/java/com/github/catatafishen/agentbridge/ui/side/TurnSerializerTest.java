package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TurnSerializer} — pure Kotlin serialization logic.
 */
class TurnSerializerTest {

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("minimal prompt with no entries")
        void minimalPrompt() {
            var prompt = new EntryData.Prompt("Fix the bug", "2024-01-15T10:30:00Z", null, "p1");
            String result = TurnSerializer.INSTANCE.serialize(prompt, Collections.emptyList(), null);

            assertTrue(result.contains("# Historical prompt — 2024-01-15T10:30:00Z"));
            assertTrue(result.contains("## User prompt"));
            assertTrue(result.contains("Fix the bug"));
            assertFalse(result.contains("## Agent thinking"));
            assertFalse(result.contains("## Agent response"));
            assertFalse(result.contains("## Tool calls"));
        }

        @Test
        @DisplayName("prompt with text response")
        void promptWithTextResponse() {
            var prompt = new EntryData.Prompt("What is this?", "2024-01-01T00:00:00Z", null, "p1");
            var text = new EntryData.Text("This is the answer.", "2024-01-01T00:00:01Z", "", "");
            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(text), null);

            assertTrue(result.contains("## Agent response"));
            assertTrue(result.contains("This is the answer."));
        }

        @Test
        @DisplayName("prompt with thinking block")
        void promptWithThinking() {
            var prompt = new EntryData.Prompt("Analyze this", "2024-01-01T00:00:00Z", null, "p1");
            var thinking = new EntryData.Thinking("Let me think about this...", "2024-01-01T00:00:01Z", "", "");
            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(thinking), null);

            assertTrue(result.contains("## Agent thinking"));
            assertTrue(result.contains("Let me think about this..."));
        }

        @Test
        @DisplayName("prompt with tool calls")
        void promptWithToolCalls() {
            var prompt = new EntryData.Prompt("Read the file", "2024-01-01T00:00:00Z", null, "p1");
            var tc = new EntryData.ToolCall("read_file");
            tc.setKind("read");
            tc.setDescription("Reading main.java");
            tc.setStatus("ok");
            tc.setResult("file content here");

            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(tc), null);

            assertTrue(result.contains("## Tool calls"));
            assertTrue(result.contains("`read_file` (read)"));
            assertTrue(result.contains("Reading main.java"));
            assertTrue(result.contains("file content here"));
        }

        @Test
        @DisplayName("tool call with non-ok status shown in brackets")
        void toolCallWithErrorStatus() {
            var prompt = new EntryData.Prompt("Write", "2024-01-01T00:00:00Z", null, "p1");
            var tc = new EntryData.ToolCall("write_file");
            tc.setKind("edit");
            tc.setStatus("error");

            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(tc), null);

            assertTrue(result.contains("[error]"));
        }

        @Test
        @DisplayName("ok status NOT shown in brackets")
        void toolCallOkStatusOmitted() {
            var prompt = new EntryData.Prompt("Status", "2024-01-01T00:00:00Z", null, "p1");
            var tc = new EntryData.ToolCall("git_status");
            tc.setKind("read");
            tc.setStatus("ok");

            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(tc), null);

            assertFalse(result.contains("[ok]"));
        }

        @Test
        @DisplayName("tool result truncated at 200 chars")
        void toolResultTruncated() {
            var prompt = new EntryData.Prompt("Long", "2024-01-01T00:00:00Z", null, "p1");
            var tc = new EntryData.ToolCall("read_file");
            tc.setKind("read");
            tc.setResult("x".repeat(300));

            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(tc), null);

            // Should contain first 200 chars + ellipsis
            assertTrue(result.contains("x".repeat(200) + "…"));
        }

        @Test
        @DisplayName("stats section rendered when present")
        void statsRendered() {
            var prompt = new EntryData.Prompt("Do stuff", "2024-01-01T00:00:00Z", null, "p1");
            var stats = new EntryData.TurnStats("t1", 5000, 100, 200, 0.0,
                3, 10, 5, "gpt-4", "", 0, 0, 0, 0.0, 0, 0, 0, "",
                "t1-entry", List.of("abc1234", "def5678"), null, null);

            String result = TurnSerializer.INSTANCE.serialize(prompt, Collections.emptyList(), stats);

            assertTrue(result.contains("**Stats**:"));
            assertTrue(result.contains("3 tools"));
            assertTrue(result.contains("2 commits: abc1234, def5678"));
        }

        @Test
        @DisplayName("single commit shown as 'Commit: hash'")
        void singleCommit() {
            var prompt = new EntryData.Prompt("Fix", "2024-01-01T00:00:00Z", null, "p1");
            var stats = new EntryData.TurnStats("t1", 1000, 0, 0, 0.0,
                1, 0, 0, "", "", 0, 0, 0, 0.0, 0, 0, 0, "",
                "t1-entry", List.of("abc1234def"), null, null);

            String result = TurnSerializer.INSTANCE.serialize(prompt, Collections.emptyList(), stats);

            assertTrue(result.contains("Commit: abc1234"));
            assertFalse(result.contains("commits:"));
        }

        @Test
        @DisplayName("empty timestamp shows (no timestamp)")
        void emptyTimestamp() {
            var prompt = new EntryData.Prompt("test", "", null, "p1");
            String result = TurnSerializer.INSTANCE.serialize(prompt, Collections.emptyList(), null);

            assertTrue(result.contains("(no timestamp)"));
        }

        @Test
        @DisplayName("multiple entries combined")
        void combinedEntries() {
            var prompt = new EntryData.Prompt("full", "2024-01-01T00:00:00Z", null, "p1");
            var thinking = new EntryData.Thinking("Hmm...", "", "", "");
            var text = new EntryData.Text("Done!", "", "", "");
            var tc = new EntryData.ToolCall("git_commit");
            tc.setKind("write");

            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(thinking, text, tc), null);

            assertTrue(result.contains("## Agent thinking"));
            assertTrue(result.contains("## Agent response"));
            assertTrue(result.contains("## Tool calls"));
        }

        @Test
        @DisplayName("result ends with single newline")
        void endsWithNewline() {
            var prompt = new EntryData.Prompt("x", "ts", null, "p1");
            String result = TurnSerializer.INSTANCE.serialize(prompt, Collections.emptyList(), null);

            assertTrue(result.endsWith("\n"));
            assertFalse(result.endsWith("\n\n"));
        }

        @Test
        @DisplayName("newlines in tool result replaced with spaces")
        void newlinesInResultReplaced() {
            var prompt = new EntryData.Prompt("Read", "ts", null, "p1");
            var tc = new EntryData.ToolCall("read_file");
            tc.setKind("read");
            tc.setResult("line1\nline2\nline3");

            String result = TurnSerializer.INSTANCE.serialize(prompt, List.of(tc), null);

            // The tool result snippet should have newlines replaced with spaces
            assertTrue(result.contains("line1 line2 line3"));
        }
    }
}
