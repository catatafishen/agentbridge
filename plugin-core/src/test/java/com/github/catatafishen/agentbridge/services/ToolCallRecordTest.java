package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolCallRecord} — immutable record validation.
 */
class ToolCallRecordTest {

    @Test
    void fieldsStoredCorrectly() {
        Instant ts = Instant.parse("2026-01-15T12:00:00Z");
        var rec = new ToolCallRecord("read_file", "FILE", 256, 4096, 42, true, "copilot", ts);

        assertEquals("read_file", rec.toolName());
        assertEquals("FILE", rec.category());
        assertEquals(256, rec.inputSizeBytes());
        assertEquals(4096, rec.outputSizeBytes());
        assertEquals(42, rec.durationMs());
        assertTrue(rec.success());
        assertEquals("copilot", rec.clientId());
        assertEquals(ts, rec.timestamp());
    }

    @Test
    void nullCategoryAllowed() {
        var rec = new ToolCallRecord("custom", null, 0, 0, 10, true, "unknown", Instant.now());
        assertNull(rec.category());
    }

    @Test
    void equalityByValue() {
        Instant ts = Instant.parse("2026-01-15T12:00:00Z");
        var a = new ToolCallRecord("tool", "CAT", 100, 200, 50, false, "client", ts);
        var b = new ToolCallRecord("tool", "CAT", 100, 200, 50, false, "client", ts);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityOnDifferentFields() {
        Instant ts = Instant.now();
        var a = new ToolCallRecord("tool_a", "CAT", 100, 200, 50, true, "client", ts);
        var b = new ToolCallRecord("tool_b", "CAT", 100, 200, 50, true, "client", ts);
        assertNotEquals(a, b);
    }

    @Test
    void toStringContainsAllFields() {
        var rec = new ToolCallRecord("search_text", "NAV", 512, 1024, 75, true, "opencode", Instant.EPOCH);
        String str = rec.toString();
        assertTrue(str.contains("search_text"));
        assertTrue(str.contains("NAV"));
        assertTrue(str.contains("512"));
        assertTrue(str.contains("1024"));
        assertTrue(str.contains("opencode"));
    }

    @Test
    void zeroSizesAllowed() {
        var rec = new ToolCallRecord("noop", null, 0, 0, 0, true, "test", Instant.now());
        assertEquals(0, rec.inputSizeBytes());
        assertEquals(0, rec.outputSizeBytes());
        assertEquals(0, rec.durationMs());
    }
}
