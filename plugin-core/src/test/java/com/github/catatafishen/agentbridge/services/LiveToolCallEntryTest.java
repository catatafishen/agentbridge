package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LiveToolCallEntry} — data model for the live tool use panel.
 */
class LiveToolCallEntryTest {

    @Test
    void started_creates_running_entry() {
        LiveToolCallEntry entry = LiveToolCallEntry.started("read_file", "Read File", "{\"path\":\"/foo\"}", "FILE", false);
        assertEquals("read_file", entry.toolName());
        assertEquals("Read File", entry.displayName());
        assertEquals("{\"path\":\"/foo\"}", entry.input());
        assertEquals("", entry.output());
        assertEquals(-1, entry.durationMs());
        assertNull(entry.success());
        assertEquals("FILE", entry.category());
        assertTrue(entry.isRunning());
        assertTrue(entry.callId() > 0);
    }

    @Test
    void completed_returns_finished_entry() {
        LiveToolCallEntry running = LiveToolCallEntry.started("git_status", "Git Status", "{}", "GIT", false);
        LiveToolCallEntry done = running.completed("branch: main\nclean", 42, true);

        assertFalse(done.isRunning());
        assertEquals(Boolean.TRUE, done.success());
        assertEquals(42, done.durationMs());
        assertEquals("branch: main\nclean", done.output());
        assertEquals("git_status", done.toolName());
        assertEquals("Git Status", done.displayName());
        assertEquals("{}", done.input());
        assertEquals("GIT", done.category());
        assertEquals(running.callId(), done.callId());
    }

    @Test
    void completed_with_failure() {
        LiveToolCallEntry running = LiveToolCallEntry.started("run_command", "Run Command", "{\"cmd\":\"ls\"}", null, false);
        LiveToolCallEntry failed = running.completed("Error: command not found", 100, false);

        assertFalse(failed.isRunning());
        assertEquals(Boolean.FALSE, failed.success());
        assertEquals("Error: command not found", failed.output());
    }

    @Test
    void timestamp_is_set_on_start() {
        Instant before = Instant.now();
        LiveToolCallEntry entry = LiveToolCallEntry.started("search_text", "Search Text", "{}", null, false);
        Instant after = Instant.now();

        assertFalse(entry.timestamp().isBefore(before));
        assertFalse(entry.timestamp().isAfter(after));
    }

    @Test
    void completed_preserves_original_timestamp() {
        LiveToolCallEntry running = LiveToolCallEntry.started("edit_text", "Edit Text", "{}", null, false);
        Instant originalTs = running.timestamp();

        LiveToolCallEntry done = running.completed("OK", 50, true);
        assertEquals(originalTs, done.timestamp());
    }

    @Test
    void input_truncation_at_max_chars() {
        String longInput = "x".repeat(LiveToolCallEntry.MAX_IO_CHARS + 500);
        LiveToolCallEntry entry = LiveToolCallEntry.started("big_tool", "Big Tool", longInput, null, false);

        assertTrue(entry.input().length() < longInput.length());
        assertTrue(entry.input().endsWith("[…truncated]"));
        assertEquals(LiveToolCallEntry.MAX_IO_CHARS + "\n[…truncated]".length(), entry.input().length());
    }

    @Test
    void output_truncation_at_max_chars() {
        LiveToolCallEntry running = LiveToolCallEntry.started("big_tool", "Big Tool", "{}", null, false);
        String longOutput = "y".repeat(LiveToolCallEntry.MAX_IO_CHARS + 1000);
        LiveToolCallEntry done = running.completed(longOutput, 10, true);

        assertTrue(done.output().length() < longOutput.length());
        assertTrue(done.output().endsWith("[…truncated]"));
    }

    @Test
    void empty_strings_handled_gracefully() {
        LiveToolCallEntry entry = new LiveToolCallEntry(
            1, "test", "test", "", "", Instant.now(), -1, null, null, false, List.of());
        assertEquals("", entry.input());
        assertEquals("", entry.output());
    }

    @Test
    void isRunning_when_success_is_null() {
        LiveToolCallEntry running = new LiveToolCallEntry(
            1, "test", "test", "{}", "", Instant.now(), -1, null, null, false, List.of());
        assertTrue(running.isRunning());
    }

    @Test
    void isRunning_false_when_success_is_set() {
        LiveToolCallEntry done = new LiveToolCallEntry(
            1, "test", "test", "{}", "ok", Instant.now(), 10, true, null, false, List.of());
        assertFalse(done.isRunning());

        LiveToolCallEntry failed = new LiveToolCallEntry(
            2, "test", "test", "{}", "err", Instant.now(), 5, false, null, false, List.of());
        assertFalse(failed.isRunning());
    }
}
