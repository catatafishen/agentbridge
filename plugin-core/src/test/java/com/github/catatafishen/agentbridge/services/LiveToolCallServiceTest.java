package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.event.ChangeListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
        int idx = service.recordStart("read_file", "{}", "FILE");
        assertEquals(0, idx);
        assertEquals(1, service.size());

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertEquals("read_file", entry.toolName());
        assertTrue(entry.isRunning());
    }

    @Test
    void complete_updates_entry() {
        int idx = service.recordStart("git_status", "{}", "GIT");
        service.complete(idx, "on branch main", 42, true);

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertFalse(entry.isRunning());
        assertEquals(Boolean.TRUE, entry.success());
        assertEquals(42, entry.durationMs());
        assertEquals("on branch main", entry.output());
    }

    @Test
    void complete_with_failure() {
        int idx = service.recordStart("run_command", "{\"cmd\":\"bad\"}", null);
        service.complete(idx, "Error: command failed", 100, false);

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertEquals(Boolean.FALSE, entry.success());
    }

    @Test
    void complete_out_of_range_is_noop() {
        service.recordStart("test", "{}", null);
        // Should not throw
        service.complete(99, "output", 10, true);
        service.complete(-1, "output", 10, true);
        assertEquals(1, service.size());
    }

    @Test
    void multiple_entries_ordered() {
        service.recordStart("first", "{}", null);
        service.recordStart("second", "{}", null);
        service.recordStart("third", "{}", null);

        List<LiveToolCallEntry> entries = service.getEntries();
        assertEquals(3, entries.size());
        assertEquals("first", entries.get(0).toolName());
        assertEquals("second", entries.get(1).toolName());
        assertEquals("third", entries.get(2).toolName());
    }

    @Test
    void clear_removes_all_entries() {
        service.recordStart("a", "{}", null);
        service.recordStart("b", "{}", null);
        service.clear();
        assertEquals(0, service.size());
        assertTrue(service.getEntries().isEmpty());
    }

    @Test
    void getEntries_returns_defensive_copy() {
        service.recordStart("test", "{}", null);
        List<LiveToolCallEntry> snapshot = service.getEntries();
        service.recordStart("another", "{}", null);
        // Original snapshot unchanged
        assertEquals(1, snapshot.size());
    }

    @Test
    void listener_notified_on_start() {
        AtomicInteger count = new AtomicInteger();
        service.addChangeListener(e -> count.incrementAndGet());
        service.recordStart("tool", "{}", null);
        assertEquals(1, count.get());
    }

    @Test
    void listener_notified_on_complete() {
        AtomicInteger count = new AtomicInteger();
        int idx = service.recordStart("tool", "{}", null);
        service.addChangeListener(e -> count.incrementAndGet());
        service.complete(idx, "done", 5, true);
        assertEquals(1, count.get());
    }

    @Test
    void listener_notified_on_clear() {
        AtomicInteger count = new AtomicInteger();
        service.recordStart("tool", "{}", null);
        service.addChangeListener(e -> count.incrementAndGet());
        service.clear();
        assertEquals(1, count.get());
    }

    @Test
    void removeChangeListener_stops_notifications() {
        AtomicInteger count = new AtomicInteger();
        ChangeListener listener = e -> count.incrementAndGet();
        service.addChangeListener(listener);
        service.recordStart("a", "{}", null);
        assertEquals(1, count.get());

        service.removeChangeListener(listener);
        service.recordStart("b", "{}", null);
        assertEquals(1, count.get()); // Not incremented
    }

    @Test
    void eviction_when_exceeding_max() {
        // Add more than MAX_ENTRIES
        for (int i = 0; i < 210; i++) {
            service.recordStart("tool_" + i, "{}", null);
        }
        // Should be capped at 200
        assertEquals(200, service.size());
        // Oldest should be evicted — first entry should be tool_10
        assertEquals("tool_10", service.getEntries().getFirst().toolName());
    }

    @Test
    void eviction_makes_old_indices_update_wrong_entry() {
        // After eviction, old indices point to different entries.
        // This is a known limitation — callers should complete entries promptly
        // before they are evicted.
        for (int i = 0; i < 205; i++) {
            service.recordStart("tool_" + i, "{}", null);
        }
        // Index 0 now points to tool_5 (oldest surviving entry), not tool_0
        service.complete(0, "completed", 10, true);
        // tool_5 (now at index 0) was updated instead of the intended tool_0
        LiveToolCallEntry first = service.getEntries().getFirst();
        assertFalse(first.isRunning());
        assertEquals("tool_5", first.toolName());
    }
}
