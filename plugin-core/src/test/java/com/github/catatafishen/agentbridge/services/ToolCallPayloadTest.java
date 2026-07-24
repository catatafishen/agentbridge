package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallPayloadTest {

    @Test
    void capturesUtf8BytesHashAndCompleteValueWithoutTruncatingMultibyteText() {
        String value = "数".repeat(3_000);

        ToolCallPayload payload = ToolCallPayload.capture(value);

        assertFalse(payload.truncated());
        assertEquals(9_000, payload.byteLength());
        assertEquals(value, payload.summary());
        assertEquals(value, payload.completeValueOrNull());
        assertTrue(payload.available());
        assertEquals(0, payload.retainedBytes());
        assertEquals(64, payload.sha256().length());
    }

    @Test
    void rejectsRetentionOneByteAboveFieldLimit() {
        ToolCallPayload payload = ToolCallPayload.capture("x".repeat(512 * 1024 + 1));

        assertFalse(payload.available());
        assertNull(payload.completeValueOrNull());
        assertEquals(ToolCallPayload.UnavailableReason.FIELD_LIMIT, payload.unavailableReason());
        assertEquals(0, payload.retainedBytes());
    }

    @Test
    void leavesAnEightThousandCharacterValueComplete() {
        String value = "x".repeat(8_000);

        ToolCallPayload payload = ToolCallPayload.capture(value);

        assertFalse(payload.truncated());
        assertEquals(value, payload.summary());
        assertEquals(value, payload.completeValueOrNull());
        assertTrue(payload.available());
        assertNull(payload.unavailableReason());
        assertEquals(0, payload.retainedBytes());
    }

    @Test
    void truncatesAndRetainsAnEightThousandOneCharacterValue() {
        String value = "x".repeat(8_001);

        ToolCallPayload payload = ToolCallPayload.capture(value);

        assertTrue(payload.truncated());
        assertEquals(value.substring(0, 8_000) + "\n[…truncated]", payload.summary());
        assertEquals(value, payload.completeValueOrNull());
        assertTrue(payload.available());
        assertEquals(8_001, payload.retainedBytes());
        assertNull(payload.unavailableReason());
    }

    @Test
    void retainsExactlyTheFieldLimitWhenSummaryIsTruncated() {
        String value = "x".repeat(512 * 1024);

        ToolCallPayload payload = ToolCallPayload.capture(value);

        assertTrue(payload.truncated());
        assertTrue(payload.available());
        assertEquals(value, payload.completeValueOrNull());
        assertEquals(512 * 1024, payload.retainedBytes());
        assertNull(payload.unavailableReason());
    }

    @Test
    void evictsOnlyRetainedFullValuesForTheMemoryBudget() {
        ToolCallPayload payload = ToolCallPayload.capture("x".repeat(8_001));

        ToolCallPayload evicted = payload.evictForMemoryBudget();

        assertFalse(evicted.available());
        assertNull(evicted.completeValueOrNull());
        assertEquals(ToolCallPayload.UnavailableReason.MEMORY_BUDGET, evicted.unavailableReason());
        assertEquals(payload.summary(), evicted.summary());
        assertSame(evicted, evicted.evictForMemoryBudget());
    }
}
