package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.event.ChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        long callId = service.recordStart("read_file", "Read File", "{}", "FILE", false, null);
        assertTrue(callId > 0);
        assertEquals(1, service.size());

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertEquals("read_file", entry.toolName());
        assertEquals("Read File", entry.displayName());
        assertTrue(entry.isRunning());
    }

    @Test
    void complete_updates_entry() {
        long callId = service.recordStart("git_status", "Git Status", "{}", "GIT", false, null);
        service.complete(callId, "on branch main", 42, true);

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertFalse(entry.isRunning());
        assertEquals(Boolean.TRUE, entry.success());
        assertEquals(42, entry.durationMs());
        assertEquals("on branch main", entry.output());
    }

    @Test
    void complete_with_failure() {
        long callId = service.recordStart("run_command", "Run Command", "{\"cmd\":\"bad\"}", null, false, null);
        service.complete(callId, "Error: command failed", 100, false);

        LiveToolCallEntry entry = service.getEntries().getFirst();
        assertEquals(Boolean.FALSE, entry.success());
    }

    @Test
    void complete_unknown_callId_is_noop() {
        service.recordStart("test", "Test", "{}", null, false, null);
        // Should not throw — unknown IDs are silently ignored (entry may have been evicted)
        service.complete(999_999, "output", 10, true);
        assertEquals(1, service.size());
        assertTrue(service.getEntries().getFirst().isRunning());
    }

    @Test
    void multiple_entries_ordered() {
        service.recordStart("first", "First", "{}", null, false, null);
        service.recordStart("second", "Second", "{}", null, false, null);
        service.recordStart("third", "Third", "{}", null, false, null);

        List<LiveToolCallEntry> entries = service.getEntries();
        assertEquals(3, entries.size());
        assertEquals("first", entries.get(0).toolName());
        assertEquals("second", entries.get(1).toolName());
        assertEquals("third", entries.get(2).toolName());
    }

    @Test
    void clear_removes_all_entries() {
        service.recordStart("a", "A", "{}", null, false, null);
        service.recordStart("b", "B", "{}", null, false, null);
        service.clear();
        assertEquals(0, service.size());
        assertTrue(service.getEntries().isEmpty());
    }

    @Test
    void getEntries_returns_defensive_copy() {
        service.recordStart("test", "Test", "{}", null, false, null);
        List<LiveToolCallEntry> snapshot = service.getEntries();
        service.recordStart("another", "Another", "{}", null, false, null);
        assertEquals(1, snapshot.size());
    }

    @Test
    void listener_notified_on_start() {
        AtomicInteger count = new AtomicInteger();
        service.addChangeListener(e -> count.incrementAndGet());
        service.recordStart("tool", "Tool", "{}", null, false, null);
        assertEquals(1, count.get());
    }

    @Test
    void listener_notified_on_complete() {
        AtomicInteger count = new AtomicInteger();
        long callId = service.recordStart("tool", "Tool", "{}", null, false, null);
        service.addChangeListener(e -> count.incrementAndGet());
        service.complete(callId, "done", 5, true);
        assertEquals(1, count.get());
    }

    @Test
    void throwingStartListenerDoesNotLoseCallIdOrBlockLaterListener() {
        String secretPayload = "arguments=super-secret result=private";
        List<LiveToolCallService.ListenerFailure> failures = new ArrayList<>();
        LiveToolCallService isolated = new LiveToolCallService(
            LiveToolCallService.MAX_RETAINED_FULL_PAYLOAD_BYTES, failures::add);
        AtomicInteger succeedingNotifications = new AtomicInteger();
        isolated.addChangeListener(event -> {
            throw new IllegalStateException(secretPayload);
        });
        isolated.addChangeListener(event -> {
            assertTrue(isolated.getEntries().getLast().isRunning());
            succeedingNotifications.incrementAndGet();
        });

        long callId = assertDoesNotThrow(
            () -> isolated.recordStart("read_file", "Read file", "{\"secret\":\"value\"}", "read", false, null));

        assertTrue(callId > 0);
        assertEquals(callId, isolated.getEntries().getLast().callId());
        assertEquals(1, succeedingNotifications.get());
        assertEquals(1, failures.size());
        LiveToolCallService.ListenerFailure failure = failures.getFirst();
        assertEquals("recordStart", failure.stage());
        assertEquals(callId, failure.callId());
        assertEquals(IllegalStateException.class.getName(), failure.exceptionType());
        assertFalse(failure.toString().contains(secretPayload));
    }

    @Test
    void throwingCompletionListenerLeavesFinalStateAndDoesNotBlockLaterListener() {
        String secretPayload = "sha256=super-secret originalInput=private";
        List<LiveToolCallService.ListenerFailure> failures = new ArrayList<>();
        LiveToolCallService isolated = new LiveToolCallService(
            LiveToolCallService.MAX_RETAINED_FULL_PAYLOAD_BYTES, failures::add);
        long callId = isolated.recordStart("run_command", "Run command", "{}", null, false, null);
        AtomicInteger succeedingNotifications = new AtomicInteger();
        isolated.addChangeListener(event -> {
            throw new IllegalArgumentException(secretPayload);
        });
        isolated.addChangeListener(event -> {
            LiveToolCallEntry completed = isolated.findById(callId).orElseThrow();
            assertFalse(completed.isRunning());
            assertEquals("done", completed.output());
            succeedingNotifications.incrementAndGet();
        });

        assertDoesNotThrow(() -> isolated.complete(callId, "done", 11, true));

        LiveToolCallEntry completed = isolated.findById(callId).orElseThrow();
        assertFalse(completed.isRunning());
        assertEquals(Boolean.TRUE, completed.success());
        assertEquals(1, succeedingNotifications.get());
        assertEquals(1, failures.size());
        LiveToolCallService.ListenerFailure failure = failures.getFirst();
        assertEquals("complete", failure.stage());
        assertEquals(callId, failure.callId());
        assertEquals(IllegalArgumentException.class.getName(), failure.exceptionType());
        assertFalse(failure.toString().contains(secretPayload));
    }

    @Test
    void listener_notified_on_clear() {
        AtomicInteger count = new AtomicInteger();
        service.recordStart("tool", "Tool", "{}", null, false, null);
        service.addChangeListener(e -> count.incrementAndGet());
        service.clear();
        assertEquals(1, count.get());
    }

    @Test
    void removeChangeListener_stops_notifications() {
        AtomicInteger count = new AtomicInteger();
        ChangeListener listener = e -> count.incrementAndGet();
        service.addChangeListener(listener);
        service.recordStart("a", "A", "{}", null, false, null);
        assertEquals(1, count.get());

        service.removeChangeListener(listener);
        service.recordStart("b", "B", "{}", null, false, null);
        assertEquals(1, count.get());
    }

    @Test
    void eviction_when_exceeding_max() {
        for (int i = 0; i < 210; i++) {
            service.recordStart("tool_" + i, "Tool " + i, "{}", null, false, null);
        }
        assertEquals(200, service.size());
        assertEquals("tool_10", service.getEntries().getFirst().toolName());
    }

    @Test
    void setDisplayName_updates_display_name() {
        long callId = service.recordStart("run_command", "Run Command", "{}", null, false, null);
        service.setDisplayName(callId, "Run failing tests");

        assertEquals("Run failing tests", service.getEntries().getFirst().displayName());
        assertTrue(service.getEntries().getFirst().acpTitleSet());
    }

    @Test
    void setDisplayName_no_downgrade_once_acp_title_set() {
        long callId = service.recordStart("run_command", "Run Command", "{}", null, false, null);
        service.setDisplayName(callId, "Run failing tests");
        service.setDisplayName(callId, "Run Command"); // attempt to downgrade — must be ignored

        assertEquals("Run failing tests", service.getEntries().getFirst().displayName());
    }

    @Test
    void setDisplayName_unknown_callId_is_noop() {
        service.recordStart("tool", "Tool", "{}", null, false, null);
        service.setDisplayName(999_999, "Other"); // safe no-op
        assertEquals("Tool", service.getEntries().getFirst().displayName());
    }

    @Test
    void completion_survives_eviction() {
        // Record first entry, remember its callId
        long earlyCallId = service.recordStart("tool_0", "Tool 0", "{}", null, false, null);

        // Fill to capacity and beyond — tool_0 gets evicted
        for (int i = 1; i <= 205; i++) {
            service.recordStart("tool_" + i, "Tool " + i, "{}", null, false, null);
        }
        assertEquals(200, service.size());
        // tool_0 has been evicted — completing it is a safe no-op
        service.complete(earlyCallId, "late result", 10, true);
        // No entry was incorrectly modified
        assertTrue(service.getEntries().getFirst().isRunning());

        // But completing a still-present entry works
        long recentCallId = service.recordStart("recent", "Recent", "{}", null, false, null);
        service.complete(recentCallId, "done", 5, true);
        LiveToolCallEntry recent = service.getEntries().getLast();
        assertFalse(recent.isRunning());
        assertEquals("done", recent.output());
    }

    @Test
    void evictsOldestFullBundleButKeepsSummaries() {
        LiveToolCallService small = new LiveToolCallService(17_500);
        long first = small.recordStart("first", "First", "x".repeat(9_000), null, false, null);
        long second = small.recordStart("second", "Second", "y".repeat(9_000), null, false, null);

        assertFalse(small.findById(first).orElseThrow().fullPayloadAvailable());
        assertTrue(small.findById(first).orElseThrow().input().endsWith("[…truncated]"));
        assertTrue(small.findById(second).orElseThrow().fullPayloadAvailable());
    }

    @Test
    void completionEvictsInputAndOutputTogetherWhenEntryExceedsBudget() {
        LiveToolCallService small = new LiveToolCallService(17_500);
        long callId = small.recordStart("tool", "Tool", "x".repeat(9_000), null, false, null);
        small.complete(callId, "y".repeat(9_000), 10, true);

        LiveToolCallEntry entry = small.findById(callId).orElseThrow();
        assertFalse(entry.fullPayloadAvailable());
        assertTrue(entry.input().endsWith("[…truncated]"));
        assertTrue(entry.output().endsWith("[…truncated]"));
        assertEquals("memory_budget", entry.fullPayloadUnavailableReason());
    }

    @Test
    void entryCountEvictionReleasesItsRetainedPayloadBytesBeforeBudgetEnforcement() {
        LiveToolCallService small = new LiveToolCallService(1_800_000);
        long first = small.recordStart("tool_0", "Tool 0", "x".repeat(9_000), null, false, null);
        long second = 0;
        for (int i = 1; i <= 200; i++) {
            long callId = small.recordStart("tool_" + i, "Tool " + i, "x".repeat(9_000), null, false, null);
            if (i == 1) {
                second = callId;
            }
        }

        assertEquals(200, small.size());
        assertTrue(small.findById(first).isEmpty());
        assertTrue(small.findById(second).orElseThrow().fullPayloadAvailable());
    }

    @Test
    void clearResetsRetainedPayloadAccounting() {
        LiveToolCallService small = new LiveToolCallService(17_500);
        small.recordStart("first", "First", "x".repeat(9_000), null, false, null);
        small.clear();
        long afterClear = small.recordStart("second", "Second", "y".repeat(9_000), null, false, null);

        assertTrue(small.findById(afterClear).orElseThrow().fullPayloadAvailable());
    }

    @Test
    void listenerSeesOnlyTheBudgetConsistentState() {
        LiveToolCallService small = new LiveToolCallService(17_500);
        long first = small.recordStart("first", "First", "x".repeat(9_000), null, false, null);
        AtomicInteger notifications = new AtomicInteger();
        small.addChangeListener(event -> {
            notifications.incrementAndGet();
            assertFalse(small.findById(first).orElseThrow().fullPayloadAvailable());
        });

        small.recordStart("second", "Second", "y".repeat(9_000), null, false, null);

        assertEquals(1, notifications.get());
    }

    @Test
    void completionDoesNotResurrectOneHalfOfMemoryBudgetEvictedBundle() {
        LiveToolCallService small = new LiveToolCallService(17_500);
        long first = small.recordStart("first", "First", "x".repeat(9_000), null, false, null);
        long second = small.recordStart("second", "Second", "y".repeat(9_000), null, false, null);

        small.complete(first, "z".repeat(8_001), 10, true);

        LiveToolCallEntry firstEntry = small.findById(first).orElseThrow();
        assertNull(firstEntry.completeInputOrNull());
        assertNull(firstEntry.completeOutputOrNull());
        assertEquals(0, firstEntry.retainedFullPayloadBytes());
        assertEquals("memory_budget", firstEntry.fullPayloadUnavailableReason());
        assertTrue(firstEntry.output().endsWith("[…truncated]"));
        assertTrue(small.findById(second).orElseThrow().fullPayloadAvailable());
    }

    @Test
    void completionDoesNotResurrectBundleWhenFieldLimitMasksMemoryBudgetReason() {
        LiveToolCallService small = new LiveToolCallService(17_500);
        long first = small.recordStart("first", "First", "x".repeat(9_000), null, false, null);
        long second = small.recordStart("second", "Second", "y".repeat(9_000), null, false, null);
        small.complete(first, "q".repeat((int) ToolCallPayload.MAX_FULL_PAYLOAD_FIELD_BYTES + 1), 10, true);
        String latestOutput = "z".repeat(8_001);

        small.complete(first, latestOutput, 20, true);

        LiveToolCallEntry firstEntry = small.findById(first).orElseThrow();
        assertNull(firstEntry.completeInputOrNull());
        assertNull(firstEntry.completeOutputOrNull());
        assertEquals(0, firstEntry.retainedFullPayloadBytes());
        assertEquals("memory_budget", firstEntry.fullPayloadUnavailableReason());
        assertEquals(latestOutput.substring(0, LiveToolCallEntry.MAX_IO_CHARS) + "\n[…truncated]", firstEntry.output());
        assertEquals(8_001, firstEntry.outputPayload().byteLength());
        assertEquals(ToolCallPayload.capture(latestOutput).sha256(), firstEntry.outputPayload().sha256());
        assertEquals(20, firstEntry.durationMs());
        assertEquals(Boolean.TRUE, firstEntry.success());
        assertTrue(small.findById(second).orElseThrow().fullPayloadAvailable());
    }
}
