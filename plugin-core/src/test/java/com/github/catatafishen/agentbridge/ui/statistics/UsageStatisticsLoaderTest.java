package com.github.catatafishen.agentbridge.ui.statistics;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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
