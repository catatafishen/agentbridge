package com.github.catatafishen.agentbridge.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the progress parsing methods in {@link MemorySettingsConfigurable}.
 */
class MemorySettingsConfigurableTest {

    @Test
    void parseFractionSessionOnly() {
        double fraction = MemorySettingsConfigurable.parseFraction(
            "Mining session 3 of 16: Fix auth bug", 16);
        assertEquals(2.0 / 16, fraction, 0.001);
    }

    @Test
    void parseFractionWithExchangeDetail() {
        double fraction = MemorySettingsConfigurable.parseFraction(
            "Mining session 3 of 16 (embedding 4/8): Fix auth bug", 16);
        // (3-1 + 4/8) / 16 = 2.5 / 16
        assertEquals(2.5 / 16, fraction, 0.001);
    }

    @Test
    void parseFractionNonMiningMessage() {
        double fraction = MemorySettingsConfigurable.parseFraction(
            "Found 16 sessions to mine.", 16);
        assertEquals(-1, fraction);
    }

    @Test
    void parseFractionZeroSessions() {
        double fraction = MemorySettingsConfigurable.parseFraction(
            "Mining session 1 of 0: test", 0);
        assertEquals(-1, fraction);
    }

    @Test
    void parseExchangeDetailPresent() {
        String detail = MemorySettingsConfigurable.parseExchangeDetail(
            "Mining session 3 of 16 (embedding 2/8): Fix auth");
        assertEquals("Embedding exchange 2 of 8", detail);
    }

    @Test
    void parseExchangeDetailAbsent() {
        String detail = MemorySettingsConfigurable.parseExchangeDetail(
            "Mining session 3 of 16: Fix auth");
        assertNull(detail);
    }

    @Test
    void stripExchangeDetailPresent() {
        String stripped = MemorySettingsConfigurable.stripExchangeDetail(
            "Mining session 3 of 16 (embedding 2/8): Fix auth");
        assertEquals("Mining session 3 of 16: Fix auth", stripped);
    }

    @Test
    void stripExchangeDetailAbsent() {
        String stripped = MemorySettingsConfigurable.stripExchangeDetail(
            "Mining session 3 of 16: Fix auth");
        assertEquals("Mining session 3 of 16: Fix auth", stripped);
    }

    @Test
    void parseFractionFirstExchange() {
        double fraction = MemorySettingsConfigurable.parseFraction(
            "Mining session 1 of 4 (embedding 1/3): Session A", 4);
        // (1-1 + 1/3) / 4 = 0.333/4
        assertEquals(1.0 / 12, fraction, 0.001);
    }

    @Test
    void parseFractionLastExchangeOfLastSession() {
        double fraction = MemorySettingsConfigurable.parseFraction(
            "Mining session 4 of 4 (embedding 5/5): Session D", 4);
        // (4-1 + 5/5) / 4 = 4/4 = 1.0
        assertEquals(1.0, fraction, 0.001);
    }
}
