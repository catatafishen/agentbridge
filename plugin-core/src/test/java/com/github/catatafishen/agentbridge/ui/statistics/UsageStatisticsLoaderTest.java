package com.github.catatafishen.agentbridge.ui.statistics;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UsageStatisticsLoader")
class UsageStatisticsLoaderTest {

    // ── toAgentId (package-private static) ──────────────────────────────

    @Nested
    @DisplayName("toAgentId")
    class ToAgentId {

        @Test
        @DisplayName("null → 'unknown'")
        void nullReturnsUnknown() {
            assertEquals("unknown", UsageStatisticsLoader.toAgentId(null));
        }

        @Test
        @DisplayName("empty string → 'unknown'")
        void emptyReturnsUnknown() {
            assertEquals("unknown", UsageStatisticsLoader.toAgentId(""));
        }

        @Test
        @DisplayName("'GitHub Copilot' → 'copilot'")
        void gitHubCopilot() {
            assertEquals("copilot", UsageStatisticsLoader.toAgentId("GitHub Copilot"));
        }

        @Test
        @DisplayName("'Claude Code' → 'claude-cli'")
        void claudeCode() {
            assertEquals("claude-cli", UsageStatisticsLoader.toAgentId("Claude Code"));
        }

        @Test
        @DisplayName("'OpenCode Agent' → 'opencode'")
        void openCodeAgent() {
            assertEquals("opencode", UsageStatisticsLoader.toAgentId("OpenCode Agent"));
        }

        @Test
        @DisplayName("'Junie' → 'junie'")
        void junie() {
            assertEquals("junie", UsageStatisticsLoader.toAgentId("Junie"));
        }

        @Test
        @DisplayName("'Kiro' → 'kiro'")
        void kiro() {
            assertEquals("kiro", UsageStatisticsLoader.toAgentId("Kiro"));
        }

        @Test
        @DisplayName("'codex-mini' → 'codex'")
        void codexMini() {
            assertEquals("codex", UsageStatisticsLoader.toAgentId("codex-mini"));
        }

        @Test
        @DisplayName("'My Custom Agent' → 'my-custom-agent' (sanitized)")
        void customAgentSanitized() {
            assertEquals("my-custom-agent", UsageStatisticsLoader.toAgentId("My Custom Agent"));
        }

        @Test
        void specialCharsStripped() {
            assertEquals("agent-v2-0", UsageStatisticsLoader.toAgentId("Agent V2.0"));
        }
    }

    // ── parsePremiumMultiplier (private static, via reflection) ─────────

    @Nested
    @DisplayName("parsePremiumMultiplier")
    class ParsePremiumMultiplier {

        @Test
        @DisplayName("null → 1.0")
        void nullReturnsOne() throws Exception {
            assertEquals(1.0, invokeParsePremiumMultiplier(null));
        }

        @Test
        @DisplayName("empty string → 1.0")
        void emptyReturnsOne() throws Exception {
            assertEquals(1.0, invokeParsePremiumMultiplier(""));
        }

        @Test
        @DisplayName("'1x' → 1.0")
        void oneX() throws Exception {
            assertEquals(1.0, invokeParsePremiumMultiplier("1x"));
        }

        @Test
        @DisplayName("'0.5x' → 0.5")
        void halfX() throws Exception {
            assertEquals(0.5, invokeParsePremiumMultiplier("0.5x"));
        }

        @Test
        @DisplayName("'2.0' → 2.0 (no suffix)")
        void twoPointZero() throws Exception {
            assertEquals(2.0, invokeParsePremiumMultiplier("2.0"));
        }

        @Test
        @DisplayName("'invalid' → 1.0 (fallback)")
        void invalidReturnsFallback() throws Exception {
            assertEquals(1.0, invokeParsePremiumMultiplier("invalid"));
        }

        @Test
        void zeroMultiplier() throws Exception {
            assertEquals(0.0, invokeParsePremiumMultiplier("0x"));
        }
    }

    // ── extractDate (private static, via reflection) ────────────────────

    @Nested
    @DisplayName("extractDate")
    class ExtractDate {

        private static final String VALID_TS = "2024-06-15T10:30:00Z";
        private static final String VALID_FALLBACK = "2024-03-20T08:00:00Z";

        @Test
        @DisplayName("JSON with valid ISO timestamp → correct LocalDate")
        void jsonWithValidTimestamp() throws Exception {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", VALID_TS);

            LocalDate expected = Instant.parse(VALID_TS)
                .atZone(ZoneId.systemDefault()).toLocalDate();
            assertEquals(expected, invokeExtractDate(obj, null));
        }

        @Test
        @DisplayName("JSON without timestamp field + valid fallback → date from fallback")
        void noTimestampFieldUsesFallback() throws Exception {
            JsonObject obj = new JsonObject();

            LocalDate expected = Instant.parse(VALID_FALLBACK)
                .atZone(ZoneId.systemDefault()).toLocalDate();
            assertEquals(expected, invokeExtractDate(obj, VALID_FALLBACK));
        }

        @Test
        @DisplayName("both absent → returns null")
        void bothAbsentReturnsNull() throws Exception {
            JsonObject obj = new JsonObject();
            assertNull(invokeExtractDate(obj, null));
        }

        @Test
        @DisplayName("invalid timestamp format → returns null")
        void invalidTimestampReturnsNull() throws Exception {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", "not-a-date");
            assertNull(invokeExtractDate(obj, null));
        }

        @Test
        @DisplayName("empty timestamp string in JSON → falls back to fallback")
        void emptyTimestampFallsBack() throws Exception {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", "");

            LocalDate expected = Instant.parse(VALID_FALLBACK)
                .atZone(ZoneId.systemDefault()).toLocalDate();
            assertEquals(expected, invokeExtractDate(obj, VALID_FALLBACK));
        }

        @Test
        @DisplayName("JSON with null timestamp value → throws (JsonNull.getAsString() unsupported)")
        void nullTimestampValueThrows() {
            JsonObject obj = new JsonObject();
            obj.add("timestamp", JsonNull.INSTANCE);

            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> invokeExtractDate(obj, VALID_FALLBACK));
            assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
        }
    }

    // ── collectTurnStats (private static, via reflection) ───────────────

    @Nested
    @DisplayName("collectTurnStats")
    class CollectTurnStats {

        @Test
        void twoEntriesSameDateProducesOneAccumulator(@TempDir Path tempDir) throws Exception {
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
            LinkedHashSet<String> agentIds = new LinkedHashSet<>();
            Map<String, String> agentDisplayNames = new LinkedHashMap<>();
            invokeCollectTurnStats(jsonlPath, "copilot", accumulators, agentIds, agentDisplayNames);

            assertEquals(1, accumulators.size(),
                "Two entries on the same date/agent should produce exactly one accumulator bucket");
        }

        @Test
        void tracksAgentChangesWithinSession(@TempDir Path tempDir) throws Exception {
            Path jsonlPath = tempDir.resolve("mixed.jsonl");
            String line1 = "{\"type\":\"text\",\"raw\":\"hello\",\"agent\":\"GitHub Copilot\","
                + "\"entryId\":\"e1\"}";
            String line2 = "{\"type\":\"turnStats\",\"turnId\":\"t1\",\"durationMs\":5000,"
                + "\"inputTokens\":100,\"outputTokens\":200,\"toolCallCount\":3,"
                + "\"linesAdded\":10,\"linesRemoved\":5,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2024-06-15T10:00:00Z\",\"entryId\":\"e2\"}";
            String line3 = "{\"type\":\"text\",\"raw\":\"hello\",\"agent\":\"Claude Code\","
                + "\"entryId\":\"e3\"}";
            String line4 = "{\"type\":\"turnStats\",\"turnId\":\"t2\",\"durationMs\":3000,"
                + "\"inputTokens\":50,\"outputTokens\":100,\"toolCallCount\":1,"
                + "\"linesAdded\":5,\"linesRemoved\":2,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2024-06-15T11:00:00Z\",\"entryId\":\"e4\"}";
            Files.writeString(jsonlPath, line1 + "\n" + line2 + "\n" + line3 + "\n" + line4 + "\n");

            Map<Object, Object> accumulators = new LinkedHashMap<>();
            LinkedHashSet<String> agentIds = new LinkedHashSet<>();
            Map<String, String> agentDisplayNames = new LinkedHashMap<>();
            invokeCollectTurnStats(jsonlPath, "unknown", accumulators, agentIds, agentDisplayNames);

            Method buildMethod = UsageStatisticsLoader.class.getDeclaredMethod("buildDailyStats", Map.class);
            buildMethod.setAccessible(true);
            List<?> result = (List<?>) buildMethod.invoke(null, accumulators);

            assertEquals(2, result.size());
            assertTrue(agentIds.contains("copilot"));
            assertTrue(agentIds.contains("claude-cli"));
            assertEquals("GitHub Copilot", agentDisplayNames.get("copilot"));
            assertEquals("Claude Code", agentDisplayNames.get("claude-cli"));

            UsageStatisticsData.DailyAgentStats first = (UsageStatisticsData.DailyAgentStats) result.get(0);
            UsageStatisticsData.DailyAgentStats second = (UsageStatisticsData.DailyAgentStats) result.get(1);
            assertTrue(Set.of(first.agentId(), second.agentId()).contains("copilot"));
            assertTrue(Set.of(first.agentId(), second.agentId()).contains("claude-cli"));
        }

        @Test
        void emptyFile_producesNoAccumulators(@TempDir Path tempDir) throws Exception {
            Path jsonlPath = tempDir.resolve("empty.jsonl");
            Files.writeString(jsonlPath, "");

            Map<Object, Object> accumulators = new LinkedHashMap<>();
            LinkedHashSet<String> agentIds = new LinkedHashSet<>();
            Map<String, String> agentDisplayNames = new LinkedHashMap<>();
            invokeCollectTurnStats(jsonlPath, "copilot", accumulators, agentIds, agentDisplayNames);

            assertTrue(accumulators.isEmpty());
        }

        @Test
        void entryOutsideDateRange_producesNoAccumulators(@TempDir Path tempDir) throws Exception {
            Path jsonlPath = tempDir.resolve("old.jsonl");
            String line = "{\"type\":\"turnStats\",\"turnId\":\"t1\",\"durationMs\":1000,"
                + "\"inputTokens\":10,\"outputTokens\":20,\"toolCallCount\":1,"
                + "\"linesAdded\":1,\"linesRemoved\":0,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2023-01-01T00:00:00Z\",\"entryId\":\"e1\"}";
            Files.writeString(jsonlPath, line + "\n");

            Map<Object, Object> accumulators = new LinkedHashMap<>();
            LinkedHashSet<String> agentIds = new LinkedHashSet<>();
            Map<String, String> agentDisplayNames = new LinkedHashMap<>();
            invokeCollectTurnStats(jsonlPath, "copilot", accumulators, agentIds, agentDisplayNames);

            assertTrue(accumulators.isEmpty());
        }
    }

    // ── buildDailyStats (private static, via reflection) ────────────────

    @Nested
    @DisplayName("buildDailyStats")
    class BuildDailyStats {

        @Test
        void withPopulatedAccumulators_returnsNonEmptyList(@TempDir Path tempDir) throws Exception {
            Path jsonlPath = tempDir.resolve("session.jsonl");
            String line = "{\"type\":\"turnStats\",\"turnId\":\"t1\",\"durationMs\":5000,"
                + "\"inputTokens\":100,\"outputTokens\":200,\"toolCallCount\":3,"
                + "\"linesAdded\":10,\"linesRemoved\":5,\"multiplier\":\"1x\","
                + "\"timestamp\":\"2024-06-15T10:00:00Z\",\"entryId\":\"e1\"}";
            Files.writeString(jsonlPath, line + "\n");

            Map<Object, Object> accumulators = new LinkedHashMap<>();
            LinkedHashSet<String> agentIds = new LinkedHashSet<>();
            Map<String, String> agentDisplayNames = new LinkedHashMap<>();
            invokeCollectTurnStats(jsonlPath, "copilot", accumulators, agentIds, agentDisplayNames);

            assertFalse(accumulators.isEmpty(), "Precondition: accumulators must be populated");

            Method buildMethod = UsageStatisticsLoader.class.getDeclaredMethod("buildDailyStats", Map.class);
            buildMethod.setAccessible(true);
            List<?> result = (List<?>) buildMethod.invoke(null, accumulators);

            assertNotNull(result);
            assertFalse(result.isEmpty());
        }
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

    private static void invokeCollectTurnStats(Path jsonlPath, String agentId,
                                               Map<Object, Object> accumulators,
                                               Set<String> agentIds,
                                               Map<String, String> agentDisplayNames) throws Exception {
        Method m = UsageStatisticsLoader.class.getDeclaredMethod(
            "collectTurnStats", Path.class, String.class, LocalDate.class, LocalDate.class, Map.class, Set.class, Map.class);
        m.setAccessible(true);
        m.invoke(null, jsonlPath, agentId,
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
            accumulators, agentIds, agentDisplayNames);
    }
}
