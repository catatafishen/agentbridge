package com.github.catatafishen.agentbridge.agent.claude;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DebugEvent} — the immutable debug-tab event record.
 */
@DisplayName("DebugEvent")
class DebugEventTest {

    @Test
    @DisplayName("fields are set from constructor")
    void fieldsSet() {
        DebugEvent event = new DebugEvent("TOOL_CALL", "read_file", "{\"path\":\"/tmp\"}");
        assertEquals("TOOL_CALL", event.type);
        assertEquals("read_file", event.message);
        assertEquals("{\"path\":\"/tmp\"}", event.details);
    }

    @Test
    @DisplayName("timestamp is in HH:mm:ss.SSS format")
    void timestampFormat() {
        DebugEvent event = new DebugEvent("TEST", "msg", "det");
        assertNotNull(event.timestamp);
        // Expected format: HH:mm:ss.SSS  e.g. "14:30:05.123"
        assertTrue(event.timestamp.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"),
                "Timestamp should match HH:mm:ss.SSS but was: " + event.timestamp);
    }

    @Test
    @DisplayName("toString includes timestamp, type, and message")
    void toStringFormat() {
        DebugEvent event = new DebugEvent("PERMISSION_DENIED", "write_file denied", "reason");
        String s = event.toString();
        assertTrue(s.contains("PERMISSION_DENIED"), "Should contain type");
        assertTrue(s.contains("write_file denied"), "Should contain message");
        // Format: [HH:mm:ss.SSS] TYPE: message
        assertTrue(s.startsWith("["), "Should start with '['");
        assertTrue(s.contains("]"), "Should contain ']'");
    }

    @Test
    @DisplayName("null details is allowed")
    void nullDetailsAllowed() {
        DebugEvent event = new DebugEvent("INFO", "msg", null);
        assertNull(event.details);
    }

    @Test
    @DisplayName("empty strings are stored as-is")
    void emptyStrings() {
        DebugEvent event = new DebugEvent("", "", "");
        assertEquals("", event.type);
        assertEquals("", event.message);
        assertEquals("", event.details);
    }

    @Test
    @DisplayName("toString with empty type and message")
    void toStringEmpty() {
        DebugEvent event = new DebugEvent("", "", null);
        String s = event.toString();
        // Format: [timestamp] : (empty type and message, but still formatted)
        assertNotNull(s);
        assertFalse(s.isEmpty());
    }

    @Test
    @DisplayName("two events created in sequence have non-null timestamps")
    void consecutiveEvents() {
        DebugEvent e1 = new DebugEvent("A", "first", null);
        DebugEvent e2 = new DebugEvent("B", "second", null);
        assertNotNull(e1.timestamp);
        assertNotNull(e2.timestamp);
        // Both should match the time format
        assertTrue(e1.timestamp.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
        assertTrue(e2.timestamp.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }
}
