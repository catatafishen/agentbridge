package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.session.exporters.AnthropicClientExporter.AnthropicMessage;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the package-private static methods of {@link AnthropicClientExporter}:
 * {@code toAnthropicMessages}, {@code trimToSizeBudget}, and {@code AnthropicMessage.toJsonLine}.
 */
class AnthropicClientExporterTest {

    // ── 1. emptyEntries_returnsEmpty ─────────────────────────────────────────

    @Test
    void emptyEntries_returnsEmpty() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(List.of());
        assertTrue(messages.isEmpty(), "No entries should produce no messages");
    }

    // ── 2. singlePrompt_createsUserMessage ───────────────────────────────────

    @Test
    void singlePrompt_createsUserMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("hello")));

        assertEquals(1, messages.size());
        AnthropicMessage msg = messages.get(0);
        assertEquals("user", msg.role());
        assertEquals(1, msg.contentBlocks().size());
        JsonObject block = msg.contentBlocks().get(0);
        assertEquals("text", block.get("type").getAsString());
        assertEquals("hello", block.get("text").getAsString());
    }

    // ── 3. promptAndText_createsTwoMessages ──────────────────────────────────

    @Test
    void promptAndText_createsTwoMessages() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("question"), text("answer")));

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("answer", messages.get(1).contentBlocks().get(0).get("text").getAsString());
    }

    // ── 4. toolCallCreatesToolUseAndResult ───────────────────────────────────

    @Test
    void toolCallCreatesToolUseAndResult() {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file", "{\"path\":\"/tmp/a.txt\"}");
        tc.setResult("file contents");

        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("go"), tc));

        // user(prompt), assistant(tool_use), user(tool_result)
        assertEquals(3, messages.size());

        assertEquals("assistant", messages.get(1).role());
        JsonObject toolUse = messages.get(1).contentBlocks().get(0);
        assertEquals("tool_use", toolUse.get("type").getAsString());
        assertTrue(toolUse.has("id"), "tool_use block must have an id");
        assertEquals("read_file", toolUse.get("name").getAsString());

        assertEquals("user", messages.get(2).role());
        JsonObject toolResult = messages.get(2).contentBlocks().get(0);
        assertEquals("tool_result", toolResult.get("type").getAsString());
        assertEquals(toolUse.get("id").getAsString(), toolResult.get("tool_use_id").getAsString());
        assertEquals("file contents", toolResult.get("content").getAsString());
    }

    // ── 5. toolCallSanitizesToolName ─────────────────────────────────────────

    @Test
    void toolCallSanitizesToolName() {
        // The exporter must pipe the title through ExportUtils.sanitizeToolName
        EntryData.ToolCall tc = new EntryData.ToolCall("agentbridge-read_file");

        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("go"), tc));

        JsonObject toolUse = messages.get(1).contentBlocks().get(0);
        String expected = ExportUtils.sanitizeToolName("agentbridge-read_file");
        assertEquals(expected, toolUse.get("name").getAsString(),
            "Tool name in tool_use block must match ExportUtils.sanitizeToolName output");
    }

    // ── 6. toolCallParsesJsonArguments ───────────────────────────────────────

    @Test
    void toolCallParsesJsonArguments() {
        EntryData.ToolCall tc = new EntryData.ToolCall("my_tool", "{\"path\":\"/foo\",\"count\":3}");

        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("go"), tc));

        JsonObject input = messages.get(1).contentBlocks().get(0).getAsJsonObject("input");
        assertEquals("/foo", input.get("path").getAsString());
        assertEquals(3, input.get("count").getAsInt());
    }

    // ── 7. toolCallInvalidJsonWrapped ────────────────────────────────────────

    @Test
    void toolCallInvalidJsonWrapped() {
        EntryData.ToolCall tc = new EntryData.ToolCall("my_tool", "not valid json");

        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("go"), tc));

        JsonObject input = messages.get(1).contentBlocks().get(0).getAsJsonObject("input");
        assertTrue(input.has("_raw"), "Invalid JSON args must be wrapped with '_raw' key");
        assertEquals("not valid json", input.get("_raw").getAsString());
    }

    // ── 8. consecutiveSameRoleMerged ─────────────────────────────────────────

    @Test
    void consecutiveSameRoleMerged() {
        // Two consecutive Text entries → accumulated into a single assistant message with 2 blocks
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("question"), text("part one"), text("part two")));

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals(2, messages.get(1).contentBlocks().size(),
            "Both text blocks should be in a single assistant message");
        assertEquals("part one",
            messages.get(1).contentBlocks().get(0).get("text").getAsString());
        assertEquals("part two",
            messages.get(1).contentBlocks().get(1).get("text").getAsString());
    }

    // ── 9. multipleExchanges ─────────────────────────────────────────────────

    @Test
    void multipleExchanges() {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file", "{}");
        tc.setResult("result");

        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                new EntryData.Prompt("P1"),
                text("T1"),
                tc,
                new EntryData.Prompt("P2"),
                text("T2")
            ));

        // P1 → user
        // T1 + tool_use → assistant (text + tool_use blocks)
        // tool_result + P2 → user (merged consecutive user messages)
        // T2 → assistant
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("user", messages.get(2).role());
        assertEquals("assistant", messages.get(3).role());

        // The assistant at index 1 should have text + tool_use blocks
        assertTrue(messages.get(1).contentBlocks().size() >= 2,
            "Assistant message should contain text block and tool_use block");
    }

    // ── 10. thinkingUpdatesModel ─────────────────────────────────────────────

    @Test
    void thinkingUpdatesModel() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                new EntryData.Prompt("Q"),
                new EntryData.Thinking("", "", "", "claude-sonnet-4-20250514"),
                text("Answer")
            ));

        // Thinking doesn't create its own message
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        // Model from the Thinking entry is captured on the assistant message
        assertEquals("claude-sonnet-4-20250514", messages.get(1).model());
    }

    // ── 11. trimBudget_zeroSkips ─────────────────────────────────────────────

    @Test
    void trimBudget_zeroSkips() {
        List<AnthropicMessage> msgs = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(new EntryData.Prompt("hello"), text("world"))));
        int before = msgs.size();
        AnthropicClientExporter.trimToSizeBudget(msgs, 0);
        assertEquals(before, msgs.size(), "maxTotalChars=0 must not trim anything");
    }

    // ── 12. trimBudget_withinBudget ──────────────────────────────────────────

    @Test
    void trimBudget_withinBudget() {
        List<AnthropicMessage> msgs = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(new EntryData.Prompt("hi"), text("yo"))));
        int before = msgs.size();
        AnthropicClientExporter.trimToSizeBudget(msgs, Integer.MAX_VALUE);
        assertEquals(before, msgs.size(), "Large budget must preserve all messages");
    }

    // ── 13. trimBudget_dropsFirstExchange ────────────────────────────────────

    @Test
    void trimBudget_dropsFirstExchange() {
        List<AnthropicMessage> msgs = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(
                    new EntryData.Prompt("Q1"),
                    text("A1 - a long answer to fill the budget up significantly"),
                    new EntryData.Prompt("Q2"),
                    text("A2")
                )));
        int before = msgs.size();
        assertEquals(4, before);

        // Budget of 1 char forces aggressive trimming — first user+assistant pair is dropped
        AnthropicClientExporter.trimToSizeBudget(msgs, 1);
        assertTrue(msgs.size() < before,
            "Over-budget must have dropped at least the first exchange pair");
    }

    // ── 14. trimBudget_singleExchangeStays ───────────────────────────────────

    @Test
    void trimBudget_singleExchangeStays() {
        // With only 1 message the while-loop guard (size >= 2) prevents any trimming
        List<AnthropicMessage> msgs = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(new EntryData.Prompt("Only question"))));
        assertEquals(1, msgs.size());

        AnthropicClientExporter.trimToSizeBudget(msgs, 1);
        assertEquals(1, msgs.size(),
            "Single message must not be dropped — needs >= 2 messages to start trimming");
    }

    // ── 15. toJsonLine_hasRoleAndContent ─────────────────────────────────────

    @Test
    void toJsonLine_hasRoleAndContent() {
        List<AnthropicMessage> msgs = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("test")));

        String json = msgs.get(0).toJsonLine();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.has("role"), "JSON must contain 'role'");
        assertTrue(obj.has("content"), "JSON must contain 'content'");
        assertEquals("user", obj.get("role").getAsString());
        assertTrue(obj.get("content").isJsonArray(), "'content' must be a JSON array");
        assertEquals(1, obj.get("content").getAsJsonArray().size());
    }

    // ── 16. promptWithTimestamp ───────────────────────────────────────────────

    @Test
    void promptWithTimestamp() {
        String iso = "2024-06-15T12:00:00Z";
        List<AnthropicMessage> msgs = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("hello", iso)));

        assertEquals(1, msgs.size());
        assertEquals(Instant.parse(iso).toEpochMilli(), msgs.get(0).createdAt(),
            "createdAt must be parsed from the ISO timestamp");
    }

    // ── 17. emptyTextSkipped ─────────────────────────────────────────────────

    @Test
    void emptyTextSkipped() {
        List<AnthropicMessage> msgs = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("Q"), new EntryData.Text("")));

        // Only the user prompt — the empty text should not produce an assistant message
        assertEquals(1, msgs.size());
        assertEquals("user", msgs.get(0).role());
    }

    // ── 18. nullToolResultHandled ────────────────────────────────────────────

    @Test
    void nullToolResultHandled() {
        EntryData.ToolCall tc = new EntryData.ToolCall("my_tool", "{}");
        // result is null by default (no setResult call)

        List<AnthropicMessage> msgs = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("go"), tc));

        assertEquals(3, msgs.size());
        JsonObject toolResult = msgs.get(2).contentBlocks().get(0);
        assertEquals("tool_result", toolResult.get("type").getAsString());
        assertEquals("", toolResult.get("content").getAsString(),
            "Null result should be exported as empty string in tool_result");
    }

    // ── parseTimestamp tests ───────────────────────────────────────────────

    @Test
    void parseTimestamp_validIsoTimestamp_returnsCorrectEpochMillis() {
        // 2024-01-15T10:30:00Z → known epoch millis
        long expected = Instant.parse("2024-01-15T10:30:00Z").toEpochMilli();
        assertEquals(expected, AnthropicClientExporter.parseTimestamp("2024-01-15T10:30:00Z"));
    }

    @Test
    void parseTimestamp_validTimestampWithMillis_returnsCorrectEpochMillis() {
        long expected = Instant.parse("2024-06-01T12:00:00.123Z").toEpochMilli();
        assertEquals(expected, AnthropicClientExporter.parseTimestamp("2024-06-01T12:00:00.123Z"));
    }

    @Test
    void parseTimestamp_invalidString_returnsZero() {
        assertEquals(0, AnthropicClientExporter.parseTimestamp("not-a-timestamp"));
    }

    @Test
    void parseTimestamp_emptyString_returnsZero() {
        assertEquals(0, AnthropicClientExporter.parseTimestamp(""));
    }

    @Test
    void parseTimestamp_partialDate_returnsZero() {
        // "2024-01-15" is a valid date but not a valid Instant (no time component)
        assertEquals(0, AnthropicClientExporter.parseTimestamp("2024-01-15"));
    }

    @Test
    void parseTimestamp_epochZeroTimestamp_returnsZeroMillis() {
        assertEquals(0, AnthropicClientExporter.parseTimestamp("1970-01-01T00:00:00Z"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static EntryData.Text text(String content) {
        return new EntryData.Text(content);
    }
}
