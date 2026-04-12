package com.github.catatafishen.agentbridge.ui.statistics;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UsageStatisticsLoaderTest {

    // ── toAgentId (package-private static) ──────────────────────────────

    @Test
    void toAgentId_copilot() {
        assertEquals("copilot", UsageStatisticsLoader.toAgentId("GitHub Copilot"));
    }

    @Test
    void toAgentId_copilotCaseInsensitive() {
        assertEquals("copilot", UsageStatisticsLoader.toAgentId("COPILOT chat"));
    }

    @Test
    void toAgentId_claude() {
        assertEquals("claude-cli", UsageStatisticsLoader.toAgentId("Claude Code"));
    }

    @Test
    void toAgentId_opencode() {
        assertEquals("opencode", UsageStatisticsLoader.toAgentId("OpenCode Agent"));
    }

    @Test
    void toAgentId_junie() {
        assertEquals("junie", UsageStatisticsLoader.toAgentId("Junie AI"));
    }

    @Test
    void toAgentId_kiro() {
        assertEquals("kiro", UsageStatisticsLoader.toAgentId("Kiro Assistant"));
    }

    @Test
    void toAgentId_codex() {
        assertEquals("codex", UsageStatisticsLoader.toAgentId("Codex"));
    }

    @Test
    void toAgentId_unknownFallback() {
        assertEquals("my-custom-agent", UsageStatisticsLoader.toAgentId("My Custom Agent"));
    }

    @Test
    void toAgentId_null() {
        assertEquals("unknown", UsageStatisticsLoader.toAgentId(null));
    }

    @Test
    void toAgentId_empty() {
        assertEquals("unknown", UsageStatisticsLoader.toAgentId(""));
    }

    @Test
    void toAgentId_specialCharsStripped() {
        assertEquals("agent-v2-0", UsageStatisticsLoader.toAgentId("Agent V2.0"));
    }

    // ── parsePremiumMultiplier (private static) ─────────────────────────

    @Test
    void parsePremiumMultiplier_one() throws Exception {
        assertEquals(1.0, invokeParsePremiumMultiplier("1x"));
    }

    @Test
    void parsePremiumMultiplier_fraction() throws Exception {
        assertEquals(0.5, invokeParsePremiumMultiplier("0.5x"));
    }

    @Test
    void parsePremiumMultiplier_noSuffix() throws Exception {
        assertEquals(2.0, invokeParsePremiumMultiplier("2.0"));
    }

    @Test
    void parsePremiumMultiplier_null() throws Exception {
        assertEquals(1.0, invokeParsePremiumMultiplier(null));
    }

    @Test
    void parsePremiumMultiplier_empty() throws Exception {
        assertEquals(1.0, invokeParsePremiumMultiplier(""));
    }

    @Test
    void parsePremiumMultiplier_invalid() throws Exception {
        assertEquals(1.0, invokeParsePremiumMultiplier("abc"));
    }

    @Test
    void parsePremiumMultiplier_zero() throws Exception {
        assertEquals(0.0, invokeParsePremiumMultiplier("0x"));
    }

    // ── extractDate (private static) ────────────────────────────────────

    @Test
    void extractDate_fromTimestamp() throws Exception {
        JsonObject obj = new JsonObject();
        String ts = "2024-06-15T10:30:00Z";
        obj.addProperty("timestamp", ts);
        LocalDate expected = Instant.parse(ts).atZone(ZoneId.systemDefault()).toLocalDate();
        assertEquals(expected, invokeExtractDate(obj, null));
    }

    @Test
    void extractDate_fallback() throws Exception {
        JsonObject obj = new JsonObject();
        String fallback = "2024-06-15T10:30:00Z";
        LocalDate expected = Instant.parse(fallback).atZone(ZoneId.systemDefault()).toLocalDate();
        assertEquals(expected, invokeExtractDate(obj, fallback));
    }

    @Test
    void extractDate_emptyTimestampUsesFallback() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("timestamp", "");
        String fallback = "2024-01-01T00:00:00Z";
        LocalDate expected = Instant.parse(fallback).atZone(ZoneId.systemDefault()).toLocalDate();
        assertEquals(expected, invokeExtractDate(obj, fallback));
    }

    @Test
    void extractDate_noTimestampNoFallback() throws Exception {
        assertNull(invokeExtractDate(new JsonObject(), null));
    }

    @Test
    void extractDate_badTimestampNoFallback() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("timestamp", "not-a-date");
        assertNull(invokeExtractDate(obj, null));
    }

    @Test
    void extractDate_badTimestampWithFallback() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("timestamp", "not-a-date");
        String fallback = "2024-03-20T12:00:00Z";
        // An invalid object timestamp returns null; the fallback is only used when the timestamp is absent.
        assertNull(invokeExtractDate(obj, fallback));
    }

    // ── collectTurnStats (private static) ──────────────────────────────

    /**
     * Two {@code TurnStats} entries on the same date (UTC) and same agentId
     * must land in the same accumulator bucket → map size == 1.
     */
    @Test
    void collectTurnStats_twoEntriesSameDateProducesOneAccumulator(@TempDir Path tempDir) throws Exception {
        Path jsonlPath = tempDir.resolve("session.jsonl");
        String line1 = "{\"type\":\"turnStats\",\"turnId\":\"t1\",\"durationMs\":5000,"
                + "\"inputTokens\":100,\"outputTokens\":200,\"toolCallCount\":3,"
                + "\"linesAdded\":10,\"linesRemoved\":5,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2024-06-15T10:00:00Z\",\"entryId\":\"e1\"}";
        String line2 = "{\"type\":\"turnStats\",\"turnId\":\"t2\",\"durationMs\":3000,"
                + "\"inputTokens\":50,\"outputTokens\":100,\"toolCallCount\":1,"
                + "\"linesAdded\":5,\"linesRemoved\":2,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2024-06-15T12:00:00Z\",\"entryId\":\"e2\"}";
        Files.writeString(jsonlPath, line1 + "\n" + line2 + "\n");

        Map<Object, Object> accumulators = new LinkedHashMap<>();
        Method m = UsageStatisticsLoader.class.getDeclaredMethod(
                "collectTurnStats", Path.class, String.class, LocalDate.class, LocalDate.class, Map.class);
        m.setAccessible(true);
        m.invoke(null, jsonlPath, "copilot",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), accumulators);

        assertEquals(1, accumulators.size(),
                "Two entries on the same date/agent should produce exactly one accumulator bucket");
    }

    @Test
    void collectTurnStats_emptyFile_producesNoAccumulators(@TempDir Path tempDir) throws Exception {
        Path jsonlPath = tempDir.resolve("empty.jsonl");
        Files.writeString(jsonlPath, "");

        Map<Object, Object> accumulators = new LinkedHashMap<>();
        Method m = UsageStatisticsLoader.class.getDeclaredMethod(
                "collectTurnStats", Path.class, String.class, LocalDate.class, LocalDate.class, Map.class);
        m.setAccessible(true);
        m.invoke(null, jsonlPath, "copilot",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), accumulators);

        assertTrue(accumulators.isEmpty(), "Empty JSONL file should produce no accumulators");
    }

    @Test
    void collectTurnStats_entryOutsideDateRange_producesNoAccumulators(@TempDir Path tempDir) throws Exception {
        Path jsonlPath = tempDir.resolve("old.jsonl");
        // Entry is in 2023; range is restricted to 2025
        String line = "{\"type\":\"turnStats\",\"turnId\":\"t1\",\"durationMs\":1000,"
                + "\"inputTokens\":10,\"outputTokens\":20,\"toolCallCount\":1,"
                + "\"linesAdded\":1,\"linesRemoved\":0,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2023-01-01T00:00:00Z\",\"entryId\":\"e1\"}";
        Files.writeString(jsonlPath, line + "\n");

        Map<Object, Object> accumulators = new LinkedHashMap<>();
        Method m = UsageStatisticsLoader.class.getDeclaredMethod(
                "collectTurnStats", Path.class, String.class, LocalDate.class, LocalDate.class, Map.class);
        m.setAccessible(true);
        m.invoke(null, jsonlPath, "copilot",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), accumulators);

        assertTrue(accumulators.isEmpty(), "Entry outside the date range should not be accumulated");
    }

    // ── buildDailyStats (private static) ───────────────────────────────

    /**
     * Populates an accumulators map via {@code collectTurnStats} (already tested
     * above), then passes it to {@code buildDailyStats} and verifies the result
     * is non-null and non-empty.  {@code DayAgentKey} is private so we reuse the
     * map produced by reflection — no need to construct the key directly.
     */
    @Test
    void buildDailyStats_withPopulatedAccumulators_returnsNonEmptyList(@TempDir Path tempDir) throws Exception {
        // Populate the accumulators map
        Path jsonlPath = tempDir.resolve("session.jsonl");
        String line = "{\"type\":\"turnStats\",\"turnId\":\"t1\",\"durationMs\":5000,"
                + "\"inputTokens\":100,\"outputTokens\":200,\"toolCallCount\":3,"
                + "\"linesAdded\":10,\"linesRemoved\":5,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2024-06-15T10:00:00Z\",\"entryId\":\"e1\"}";
        Files.writeString(jsonlPath, line + "\n");

        Map<Object, Object> accumulators = new LinkedHashMap<>();
        Method collectMethod = UsageStatisticsLoader.class.getDeclaredMethod(
                "collectTurnStats", Path.class, String.class, LocalDate.class, LocalDate.class, Map.class);
        collectMethod.setAccessible(true);
        collectMethod.invoke(null, jsonlPath, "copilot",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), accumulators);

        assertFalse(accumulators.isEmpty(), "Precondition: accumulators must be populated by collectTurnStats");

        // Now invoke buildDailyStats with the populated map
        Method buildMethod = UsageStatisticsLoader.class.getDeclaredMethod("buildDailyStats", Map.class);
        buildMethod.setAccessible(true);
        List<?> result = (List<?>) buildMethod.invoke(null, accumulators);

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static double invokeParsePremiumMultiplier(String multiplier) throws Exception {
        Method m = UsageStatisticsLoader.class.getDeclaredMethod("parsePremiumMultiplier", String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, multiplier);
    }

    private static LocalDate invokeExtractDate(JsonObject obj, String fallback) throws Exception {
        Method m = UsageStatisticsLoader.class.getDeclaredMethod("extractDate", JsonObject.class, String.class);
        m.setAccessible(true);
        return (LocalDate) m.invoke(null, obj, fallback);
    }
}
