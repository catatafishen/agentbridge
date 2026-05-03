package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.event.ChangeListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LiveToolCallService} — in-memory ring buffer for live tool calls.
 * Does not require IntelliJ platform (service is instantiated directly).
 */
class LiveToolCallServiceTest {

    private LiveToolCallService service;

    @BeforeEach
    void setUp() {
        service = new LiveToolCallService();
    }

    @Test
    void initially_empty() {
        assertEquals(0, service.size());
        assertTrue(service.getEntries().isEmpty());
    }

    @Test
    void recordStart_adds_running_entry() {
        long callId = service.recordStart("read_file", "Read File", "{}", "FILE", false);
        assertTrue(callId > 0);
        assertEquals(1, service.size());

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertEquals("read_file", entry.toolName());
        assertEquals("Read File", entry.displayName());
        assertTrue(entry.isRunning());
    }

    @Test
    void complete_updates_entry() {
        long callId = service.recordStart("git_status", "Git Status", "{}", "GIT", false);
        service.complete(callId, "on branch main", 42, true);

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertFalse(entry.isRunning());
        assertEquals(Boolean.TRUE, entry.success());
        assertEquals(42, entry.durationMs());
        assertEquals("on branch main", entry.output());
    }

    @Test
    void complete_with_failure() {
        long callId = service.recordStart("run_command", "Run Command", "{\"cmd\":\"bad\"}", null, false);
        service.complete(callId, "Error: command failed", 100, false);

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertEquals(Boolean.FALSE, entry.success());
    }

    @Test
    void complete_unknown_callId_is_noop() {
        service.recordStart("test", "Test", "{}", null, false);
        // Should not throw — unknown IDs are silently ignored (entry may have been evicted)
        service.complete(999_999, "output", 10, true);
        assertEquals(1, service.size());
        assertTrue(service.getEntries().getFirst().isRunning());
    }

    @Test
    void multiple_entries_ordered() {
        service.recordStart("first", "First", "{}", null, false);
        service.recordStart("second", "Second", "{}", null, false);
        service.recordStart("third", "Third", "{}", null, false);

        List<LiveToolCallEntry> entries = service.getEntries();
        assertEquals(3, entries.size());
        assertEquals("first", entries.get(0).toolName());
        assertEquals("second", entries.get(1).toolName());
        assertEquals("third", entries.get(2).toolName());
    }

    @Test
    void clear_removes_all_entries() {
        service.recordStart("a", "A", "{}", null, false);
        service.recordStart("b", "B", "{}", null, false);
        service.clear();
        assertEquals(0, service.size());
        assertTrue(service.getEntries().isEmpty());
    }

    @Test
    void getEntries_returns_defensive_copy() {
        service.recordStart("test", "Test", "{}", null, false);
        List<LiveToolCallEntry> snapshot = service.getEntries();
        service.recordStart("another", "Another", "{}", null, false);
        assertEquals(1, snapshot.size());
    }

    @Test
    void listener_notified_on_start() {
        AtomicInteger count = new AtomicInteger();
        service.addChangeListener(e -> count.incrementAndGet());
        service.recordStart("tool", "Tool", "{}", null, false);
        assertEquals(1, count.get());
    }

    @Test
    void listener_notified_on_complete() {
        AtomicInteger count = new AtomicInteger();
        long callId = service.recordStart("tool", "Tool", "{}", null, false);
        service.addChangeListener(e -> count.incrementAndGet());
        service.complete(callId, "done", 5, true);
        assertEquals(1, count.get());
    }

    @Test
    void listener_notified_on_clear() {
        AtomicInteger count = new AtomicInteger();
        service.recordStart("tool", "Tool", "{}", null, false);
        service.addChangeListener(e -> count.incrementAndGet());
        service.clear();
        assertEquals(1, count.get());
    }

    @Test
    void removeChangeListener_stops_notifications() {
        AtomicInteger count = new AtomicInteger();
        ChangeListener listener = e -> count.incrementAndGet();
        service.addChangeListener(listener);
        service.recordStart("a", "A", "{}", null, false);
        assertEquals(1, count.get());

        service.removeChangeListener(listener);
        service.recordStart("b", "B", "{}", null, false);
        assertEquals(1, count.get());
    }

    @Test
    void eviction_when_exceeding_max() {
        for (int i = 0; i < 210; i++) {
            service.recordStart("tool_" + i, "Tool " + i, "{}", null, false);
        }
        assertEquals(200, service.size());
        assertEquals("tool_10", service.getEntries().getFirst().toolName());
    }

    @Test
    void completion_survives_eviction() {
        // Record first entry, remember its callId
        long earlyCallId = service.recordStart("tool_0", "Tool 0", "{}", null, false);

        // Fill to capacity and beyond — tool_0 gets evicted
        for (int i = 1; i <= 205; i++) {
            service.recordStart("tool_" + i, "Tool " + i, "{}", null, false);
        }
        assertEquals(200, service.size());
        // tool_0 has been evicted — completing it is a safe no-op
        service.complete(earlyCallId, "late result", 10, true);
        // No entry was incorrectly modified
        assertTrue(service.getEntries().getFirst().isRunning());

        // But completing a still-present entry works
        long recentCallId = service.recordStart("recent", "Recent", "{}", null, false);
        service.complete(recentCallId, "done", 5, true);
        LiveToolCallEntry recent = service.getEntries().getLast();
        assertFalse(recent.isRunning());
        assertEquals("done", recent.output());
    }
}
