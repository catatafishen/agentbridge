package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.hooks.HookStageResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallHistoryJsonTest {

    @Test
    void summaryPreservesEveryLegacyFieldAndAddsPayloadMetadata() {
        LiveToolCallEntry entry = completeEntry(41L, "edit", longValue("arguments"), longValue("result"));

        JsonObject summary = ToolCallHistoryJson.summary(entry);

        assertEquals(41L, summary.get("id").getAsLong());
        assertEquals("Write source", summary.get("title").getAsString());
        assertEquals("edit_text", summary.get("toolName").getAsString());
        assertEquals("edit", summary.get("kind").getAsString());
        assertEquals("success", summary.get("status").getAsString());
        assertEquals("2026-07-24T04:00:00Z", summary.get("timestamp").getAsString());
        assertEquals(entry.input(), summary.get("arguments").getAsString());
        assertEquals(entry.output(), summary.get("result").getAsString());
        assertEquals(87L, summary.get("durationMs").getAsLong());
        assertTrue(summary.get("hasHooks").getAsBoolean());
        JsonArray stages = summary.getAsJsonArray("hookStages");
        assertEquals(1, stages.size());
        assertEquals("pre", stages.get(0).getAsJsonObject().get("trigger").getAsString());
        assertEquals("validate.sh", stages.get(0).getAsJsonObject().get("scriptName").getAsString());
        assertEquals("success", stages.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals(3L, stages.get(0).getAsJsonObject().get("durationMs").getAsLong());
        assertEquals("passed", stages.get(0).getAsJsonObject().get("detail").getAsString());

        assertPayloadMetadata(summary, "arguments", entry.inputPayload());
        assertPayloadMetadata(summary, "result", entry.outputPayload());
        assertTrue(summary.get("fullPayloadAvailable").getAsBoolean());
        assertEquals("/tool-calls/41", summary.get("detailUrl").getAsString());
        assertFalse(summary.has("fullPayloadUnavailableReason"));
        assertFalse(summary.has("originalInput"));
    }

    @Test
    void summaryKeepsPlainTextResultAndOmitsOptionalKind() {
        LiveToolCallEntry entry = new LiveToolCallEntry(
            7L, "run_command", "Run command", ToolCallPayload.capture("{\"cmd\":\"echo ok\"}"), "{\"cmd\":\"before\"}",
            ToolCallPayload.capture("plain-text result\\nwith no JSON envelope"), Instant.parse("2026-07-24T04:01:00Z"),
            9L, true, null, false, List.of(), false);

        JsonObject summary = ToolCallHistoryJson.summary(entry);

        assertEquals("plain-text result\\nwith no JSON envelope", summary.get("result").getAsString());
        assertFalse(summary.has("kind"));
        assertFalse(summary.has("hookStages"));
        assertFalse(summary.has("originalInput"));
    }

    @Test
    void detailUsesExactCompleteValuesWithIdentityTimingLengthAndHash() {
        LiveToolCallEntry entry = completeEntry(53L, "edit", longValue("arguments"), longValue("result"));

        JsonObject detail = ToolCallHistoryJson.detail(entry);

        assertEquals(53L, detail.get("id").getAsLong());
        assertEquals("edit_text", detail.get("toolName").getAsString());
        assertEquals("2026-07-24T04:00:00Z", detail.get("timestamp").getAsString());
        assertEquals(87L, detail.get("durationMs").getAsLong());
        assertEquals(entry.completeInputOrNull(), detail.get("arguments").getAsString());
        assertEquals(entry.completeOutputOrNull(), detail.get("result").getAsString());
        assertPayloadMetadata(detail, "arguments", entry.inputPayload());
        assertPayloadMetadata(detail, "result", entry.outputPayload());
        assertFalse(detail.has("originalInput"));
    }

    @Test
    void summaryReportsExactFieldLimitReasonWithoutDetailUrl() {
        String tooLargeInput = "x".repeat(512 * 1024 + 1);
        LiveToolCallEntry entry = completeEntry(61L, null, tooLargeInput, "plain text");

        JsonObject summary = ToolCallHistoryJson.summary(entry);

        assertFalse(summary.get("fullPayloadAvailable").getAsBoolean());
        assertFalse(summary.has("detailUrl"));
        assertEquals("field_limit", summary.get("fullPayloadUnavailableReason").getAsString());
        assertPayloadMetadata(summary, "arguments", entry.inputPayload());
        assertPayloadMetadata(summary, "result", entry.outputPayload());
        assertThrows(IllegalStateException.class, () -> ToolCallHistoryJson.detail(entry));
    }

    @Test
    void summaryReportsExactMemoryBudgetReasonWithoutDetailUrl() {
        LiveToolCallEntry evicted = completeEntry(67L, null, longValue("arguments"), longValue("result"))
            .dropRetainedPayloadsForMemoryBudget();

        JsonObject summary = ToolCallHistoryJson.summary(evicted);

        assertFalse(summary.get("fullPayloadAvailable").getAsBoolean());
        assertFalse(summary.has("detailUrl"));
        assertEquals("memory_budget", summary.get("fullPayloadUnavailableReason").getAsString());
        assertNull(evicted.completeInputOrNull());
        assertNull(evicted.completeOutputOrNull());
        assertThrows(IllegalStateException.class, () -> ToolCallHistoryJson.detail(evicted));
    }

    private static LiveToolCallEntry completeEntry(long callId, String category, String input, String output) {
        return new LiveToolCallEntry(
            callId, "edit_text", "Write source", ToolCallPayload.capture(input), "{\"before\":true}",
            ToolCallPayload.capture(output), Instant.parse("2026-07-24T04:00:00Z"),
            87L, true, category, true,
            List.of(new HookStageResult("pre", "validate.sh", "success", 3L, "passed")), false);
    }

    private static String longValue(String name) {
        return "{\"" + name + "\":\"" + "x".repeat(8_100) + "\"}";
    }

    private static void assertPayloadMetadata(JsonObject json, String prefix, ToolCallPayload payload) {
        assertEquals(payload.truncated(), json.get(prefix + "Truncated").getAsBoolean());
        assertEquals(payload.byteLength(), json.get(prefix + "Bytes").getAsLong());
        assertEquals(payload.sha256(), json.get(prefix + "Sha256").getAsString());
    }
}
