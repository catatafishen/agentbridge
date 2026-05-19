package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the package-private pure static helpers in {@link CopilotClientExporter}:
 * {@code findFirstModel} and {@code resolveStartTime}.
 */
class CopilotClientExporterStaticMethodsTest {

    // ── findFirstModel ───────────────────────────────────────────────────────

    @Test
    void findFirstModel_emptyList_returnsNull() {
        assertNull(CopilotClientExporter.findFirstModel(List.of()));
    }

    @Test
    void findFirstModel_noModelInEntries_returnsNull() {
        // Prompt and plain Text (1-arg) don't carry model info
        assertNull(CopilotClientExporter.findFirstModel(
            List.of(new EntryData.Prompt("Hi"), new EntryData.Text("Hello"))));
    }

    @Test
    void findFirstModel_textWithModel_returnsModel() {
        EntryData.Text textWithModel = new EntryData.Text(
            "Answer", Instant.now().toString(), "", "gpt-4o", "");

        assertEquals("gpt-4o",
            CopilotClientExporter.findFirstModel(List.of(new EntryData.Prompt("Q"), textWithModel)));
    }

    @Test
    void findFirstModel_multipleModels_returnsFirst() {
        EntryData.Text t1 = new EntryData.Text("A1", "", "", "model-a", "");
        EntryData.Text t2 = new EntryData.Text("A2", "", "", "model-b", "");

        assertEquals("model-a",
            CopilotClientExporter.findFirstModel(List.of(new EntryData.Prompt("Q"), t1, t2)));
    }

    @Test
    void findFirstModel_thinkingWithModel_returnsModel() {
        EntryData.Thinking thinking = new EntryData.Thinking(
            "reasoning", "", "", "claude-sonnet-4-20250514");

        assertEquals("claude-sonnet-4-20250514",
            CopilotClientExporter.findFirstModel(List.of(new EntryData.Prompt("Q"), thinking)));
    }

    @Test
    void findFirstModel_toolCallWithModel_returnsModel() {
        // Set model via the full constructor
        EntryData.ToolCall tcWithModel = new EntryData.ToolCall(
            "read_file", "{}", "other", "result", null, null, null,
            false, null, null, "", "", "o3-mini", "");

        assertEquals("o3-mini",
            CopilotClientExporter.findFirstModel(List.of(new EntryData.Prompt("Q"), tcWithModel)));
    }

    @Test
    void findFirstModel_subAgentWithModel_returnsModel() {
        EntryData.SubAgent sa = new EntryData.SubAgent(
            "general-purpose", "desc", null, null, "done", 0, null,
            false, null, "", "", "gpt-4o-mini", "");

        assertEquals("gpt-4o-mini",
            CopilotClientExporter.findFirstModel(List.of(new EntryData.Prompt("Q"), sa)));
    }

    @Test
    void findFirstModel_emptyModelString_skipped() {
        // Text with empty model should be skipped, next one with model wins
        EntryData.Text noModel = new EntryData.Text("A1", "", "", "", "");
        EntryData.Text withModel = new EntryData.Text("A2", "", "", "gpt-4o", "");

        assertEquals("gpt-4o",
            CopilotClientExporter.findFirstModel(List.of(noModel, withModel)));
    }

    @Test
    void findFirstModel_promptSkipped() {
        // Prompts don't have model info — only assistant-side entries do
        EntryData.Text withModel = new EntryData.Text("Answer", "", "", "gpt-4o", "");
        assertEquals("gpt-4o",
            CopilotClientExporter.findFirstModel(
                List.of(new EntryData.Prompt("Q1"), new EntryData.Prompt("Q2"), withModel)));
    }

    // ── resolveStartTime ─────────────────────────────────────────────────────

    @Test
    void resolveStartTime_emptyList_returnsNonNull() {
        // Falls back to Instant.now() — just verify not null and recent
        Instant before = Instant.now().minusSeconds(1);
        Instant result = CopilotClientExporter.resolveStartTime(List.of());
        Instant after = Instant.now().plusSeconds(1);

        assertTrue(result.isAfter(before) && result.isBefore(after),
            "Empty list should fall back to approximately now");
    }

    @Test
    void resolveStartTime_entryWithTimestamp_parsesIt() {
        String ts = "2024-06-15T12:00:00Z";
        EntryData.Prompt prompt = new EntryData.Prompt("Hello", ts);

        assertEquals(Instant.parse(ts), CopilotClientExporter.resolveStartTime(List.of(prompt)));
    }

    @Test
    void resolveStartTime_firstValidTimestampWins() {
        // First entry has no timestamp, second does
        EntryData.Prompt p1 = new EntryData.Prompt("Q1"); // no timestamp
        EntryData.Prompt p2 = new EntryData.Prompt("Q2", "2024-01-01T00:00:00Z");

        assertEquals(Instant.parse("2024-01-01T00:00:00Z"),
            CopilotClientExporter.resolveStartTime(List.of(p1, p2)));
    }

    @Test
    void resolveStartTime_entriesWithEmptyTimestamp_fallsBackToNow() {
        // Both entries have empty timestamps
        Instant before = Instant.now().minusSeconds(1);
        Instant result = CopilotClientExporter.resolveStartTime(
            List.of(new EntryData.Prompt("Q1"), new EntryData.Text("A1")));
        Instant after = Instant.now().plusSeconds(1);

        assertTrue(result.isAfter(before) && result.isBefore(after));
    }

    @Test
    void resolveStartTime_timestampWithMillis_parsed() {
        String ts = "2024-06-15T12:00:00.500Z";
        EntryData.Prompt prompt = new EntryData.Prompt("Hello", ts);

        assertEquals(Instant.parse(ts), CopilotClientExporter.resolveStartTime(List.of(prompt)));
    }
}
