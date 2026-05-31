package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.bridge.ContextFileRef;
import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.bridge.NudgeSource;
import com.github.catatafishen.agentbridge.ui.ChatPanelApi;
import com.github.catatafishen.agentbridge.ui.TurnStatsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationEntryStoreTest {

    private ConversationEntryStore store;

    @BeforeEach
    void setUp() {
        store = new ConversationEntryStore();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialState_entriesEmpty() {
        assertTrue(store.getEntries().isEmpty());
    }

    @Test
    void initialState_currentAgentEmpty() {
        assertEquals("", store.getCurrentAgent());
    }

    // ── Change listeners ──────────────────────────────────────────────────────

    @Test
    void addChangeListener_firesOnClear() {
        var count = new AtomicInteger(0);
        store.addChangeListener(count::incrementAndGet);
        store.clear();
        assertEquals(1, count.get());
    }

    @Test
    void removeChangeListener_stopsNotifications() {
        var count = new AtomicInteger(0);
        Runnable listener = count::incrementAndGet;
        store.addChangeListener(listener);
        store.removeChangeListener(listener);
        store.clear();
        assertEquals(0, count.get());
    }

    @Test
    void changeListener_firedOnFinishResponse() {
        var count = new AtomicInteger(0);
        store.addChangeListener(count::incrementAndGet);
        store.finishResponse();
        assertEquals(1, count.get());
    }

    @Test
    void changeListener_firedOnEmitTurnStats() {
        var count = new AtomicInteger(0);
        store.addChangeListener(count::incrementAndGet);
        store.emitTurnStats(makeTurnStats());
        assertEquals(1, count.get());
    }

    @Test
    void changeListener_firedOnAddPromptEntry() {
        var count = new AtomicInteger(0);
        store.addChangeListener(count::incrementAndGet);
        store.addPromptEntry("hello", null, "p1");
        assertEquals(1, count.get());
    }

    // ── Prompt entries ────────────────────────────────────────────────────────

    @Test
    void addPromptEntry_addsToList() {
        store.addPromptEntry("test prompt", null, "p1");
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.Prompt.class, store.getEntries().get(0));
    }

    @Test
    void addPromptEntry_withContextFiles() {
        var files = List.of(new ContextFileRef("file.kt", "/path/file.kt", 0));
        store.addPromptEntry("prompt", files, "p2");
        var entry = (EntryData.Prompt) store.getEntries().get(0);
        assertNotNull(entry.getContextFiles());
        assertEquals(1, entry.getContextFiles().size());
    }

    @Test
    void removePromptEntry_removesById() {
        store.addPromptEntry("first", null, "p1");
        store.addPromptEntry("second", null, "p2");
        store.removePromptEntry("p1");
        assertEquals(1, store.getEntries().size());
        assertEquals("p2", store.getEntries().get(0).getEntryId());
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Test
    void appendText_createsEntryOnFirstCall() {
        store.startStreaming();
        store.appendText("hello");
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.Text.class, store.getEntries().get(0));
    }

    @Test
    void appendText_appendsToExistingEntry() {
        store.startStreaming();
        store.appendText("hello ");
        store.appendText("world");
        assertEquals(1, store.getEntries().size());
        var entry = (EntryData.Text) store.getEntries().get(0);
        assertEquals("hello world", entry.getRaw());
    }

    @Test
    void appendThinkingText_createsEntryOnFirstCall() {
        store.startStreaming();
        store.appendThinkingText("thinking...");
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.Thinking.class, store.getEntries().get(0));
    }

    @Test
    void appendThinkingText_appendsToExistingEntry() {
        store.startStreaming();
        store.appendThinkingText("part1 ");
        store.appendThinkingText("part2");
        assertEquals(1, store.getEntries().size());
        var entry = (EntryData.Thinking) store.getEntries().get(0);
        assertEquals("part1 part2", entry.getRaw());
    }

    @Test
    void startStreaming_resetsCurrentEntries() {
        store.startStreaming();
        store.appendText("first turn");
        store.finishResponse();

        store.startStreaming();
        store.appendText("second turn");
        assertEquals(2, store.getEntries().size());
    }

    @Test
    void finishResponse_clearsStreamingState() {
        store.startStreaming();
        store.appendText("in progress");
        store.finishResponse();

        // Next appendText should create a new entry
        store.appendText("next turn");
        assertEquals(2, store.getEntries().size());
    }

    @Test
    void closeCurrentTextEntry_newAppendCreatesNewEntry() {
        // Reproduces the task_complete persistence bug: summary must not be appended to
        // the already-persisted pre-tool-call text entry — it must land in a new entry.
        store.startStreaming();
        store.appendText("pre-tool text");
        assertEquals(1, store.getEntries().size());
        String firstEntryId = store.getEntries().get(0).getEntryId();

        store.closeCurrentTextEntry();
        store.appendText("task_complete summary");

        var entries = store.getEntries();
        assertEquals(2, entries.size(), "summary must be a separate entry, not appended in-place");
        assertNotEquals(entries.get(0).getEntryId(), entries.get(1).getEntryId(),
            "new entry must have a distinct ID");
        assertEquals("pre-tool text", ((EntryData.Text) entries.get(0)).getRaw());
        assertEquals("task_complete summary", ((EntryData.Text) entries.get(1)).getRaw());
        assertEquals(firstEntryId, entries.get(0).getEntryId(),
            "first entry ID must be unchanged");
    }

    @Test
    void closeCurrentThinkingEntry_newAppendCreatesNewEntry() {
        // Reproduces the THOUGHT→TEXT→THOUGHT persistence bug: the second thinking block must
        // not append to the already-persisted first thinking entry — it must land in a new entry.
        store.startStreaming();
        store.appendThinkingText("first thinking block");
        assertEquals(1, store.getEntries().size());
        String firstEntryId = store.getEntries().getFirst().getEntryId();

        store.closeCurrentThinkingEntry();
        store.appendThinkingText("second thinking block");

        var entries = store.getEntries();
        assertEquals(2, entries.size(), "second thinking block must be a separate entry, not appended in-place");
        assertNotEquals(entries.get(0).getEntryId(), entries.get(1).getEntryId(),
            "new thinking entry must have a distinct ID");
        assertEquals("first thinking block", ((EntryData.Thinking) entries.get(0)).getRaw());
        assertEquals("second thinking block", ((EntryData.Thinking) entries.get(1)).getRaw());
        assertEquals(firstEntryId, entries.get(0).getEntryId(),
            "first entry ID must be unchanged");
    }

    // ── Tool calls ────────────────────────────────────────────────────────────

    @Test
    void addToolCallEntry_addsToList() {
        store.addToolCallEntry("t1", "read_file", "{}", "file");
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.ToolCall.class, store.getEntries().get(0));
    }

    @Test
    void updateToolCall_updatesTrackedEntry() {
        store.addToolCallEntry("t1", "read_file", "{}", "file");
        var update = new ChatPanelApi.ToolCallUpdate("done details", "new desc", "file", false, null, null, null);
        store.updateToolCall("t1", "done", update);

        var entry = (EntryData.ToolCall) store.getEntries().getFirst();
        assertEquals("done", entry.getStatus());
        assertEquals("done details", entry.getResult());
        assertEquals("new desc", entry.getDescription());
    }

    @Test
    void updateToolCall_ignoresUnknownId() {
        var update = new ChatPanelApi.ToolCallUpdate("details", null, null, false, null, null, null);
        // Should not throw
        store.updateToolCall("unknown", "done", update);
        assertTrue(store.getEntries().isEmpty());
    }

    @Test
    void addToolCallEntry_withPluginTool_setsField() {
        store.addToolCallEntry("t2", "Search Text", "{}", "file", "search_text");
        var entry = (EntryData.ToolCall) store.getEntries().getFirst();
        assertEquals("search_text", entry.getPluginTool());
        assertEquals("Search Text", entry.getTitle());
    }

    @Test
    void markToolCallMcp_setsPluginToolOnExistingEntry() {
        store.addToolCallEntry("t3", "Read File", "{}", "file");
        var entry = (EntryData.ToolCall) store.getEntries().getFirst();
        assertNull(entry.getPluginTool());

        store.markToolCallMcp("t3", "read_file");
        assertEquals("read_file", entry.getPluginTool());
        assertEquals("Read File", entry.getTitle());
    }

    @Test
    void markToolCallMcp_ignoresUnknownId() {
        // Should not throw
        store.markToolCallMcp("unknown", "some_tool");
        assertTrue(store.getEntries().isEmpty());
    }

    // ── Sub-agents ────────────────────────────────────────────────────────────

    @Test
    void addSubAgentEntry_addsToList() {
        var state = new ChatPanelApi.SubAgentInitialState(null, "running", null, false, null);
        store.addSubAgentEntry("sa1", "explore", "Find files", null, state);
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.SubAgent.class, store.getEntries().getFirst());
    }

    @Test
    void updateSubAgentResult_updatesTrackedEntry() {
        var state = new ChatPanelApi.SubAgentInitialState(null, "running", null, false, null);
        store.addSubAgentEntry("sa1", "explore", "Find files", null, state);
        store.updateSubAgentResult("sa1", "done", "Found 5 files", null, false, null);

        var entry = (EntryData.SubAgent) store.getEntries().get(0);
        assertEquals("done", entry.getStatus());
        assertEquals("Found 5 files", entry.getResult());
    }

    // ── Nudge entries ─────────────────────────────────────────────────────────

    @Test
    void addNudgeEntry_addsToList() {
        store.addNudgeEntry("n1", "Use MCP tools", NudgeSource.NATIVE_TOOL_REPRIMAND);
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.Nudge.class, store.getEntries().get(0));
    }

    // ── Turn stats ────────────────────────────────────────────────────────────

    @Test
    void emitTurnStats_addsEntryAndReturnsIt() {
        var stats = makeTurnStats();
        var result = store.emitTurnStats(stats);
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.TurnStats.class, store.getEntries().get(0));
        assertEquals(result, store.getEntries().get(0));
        assertEquals(1000L, result.getDurationMs());
    }

    // ── Session separators ────────────────────────────────────────────────────

    @Test
    void addSessionSeparator_addsToList() {
        store.addSessionSeparator("2024-01-01T00:00:00Z", "copilot");
        assertEquals(1, store.getEntries().size());
        assertInstanceOf(EntryData.SessionSeparator.class, store.getEntries().get(0));
    }

    // ── Agent tracking ────────────────────────────────────────────────────────

    @Test
    void setCurrentAgent_affectsNewEntries() {
        store.setCurrentAgent("copilot-cli");
        store.startStreaming();
        store.appendText("hello");

        var entry = (EntryData.Text) store.getEntries().get(0);
        assertEquals("copilot-cli", entry.getAgent());
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    void clear_removesAllEntries() {
        store.addPromptEntry("test", null, "p1");
        store.startStreaming();
        store.appendText("response");
        store.addToolCallEntry("t1", "tool", null, null);
        store.clear();
        assertTrue(store.getEntries().isEmpty());
    }

    // ── isEntryTracked ────────────────────────────────────────────────────────

    @Test
    void isEntryTracked_returnsTrueForKnownEntry() {
        store.addPromptEntry("test", null, "p1");
        assertTrue(store.isEntryTracked("p1"));
    }

    @Test
    void isEntryTracked_returnsFalseForUnknown() {
        assertFalse(store.isEntryTracked("unknown"));
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @Test
    void entriesSnapshot_returnsDefensiveCopy() {
        store.addPromptEntry("test", null, "p1");
        var snapshot = store.entriesSnapshot();
        store.clear();
        // Snapshot retains entries despite clear
        assertEquals(1, snapshot.size());
    }

    @Test
    void getEntries_returnsDefensiveCopy() {
        store.addPromptEntry("test", null, "p1");
        var entries = store.getEntries();
        store.clear();
        assertEquals(1, entries.size());
    }

    // ── Thread safety ──────────────────────────────────────────────────────────

    @Test
    void concurrentAppendText_noExceptions() throws Exception {
        store.startStreaming();
        int threadCount = 8;
        int iterationsPerThread = 500;
        var latch = new java.util.concurrent.CountDownLatch(threadCount);
        var errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            int threadIdx = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        store.appendText("t" + threadIdx + "-" + i + " ");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        // All text should be in a single entry (first append creates it, rest append)
        var entries = store.getEntries();
        assertEquals(1, entries.size());
    }

    @Test
    void concurrentMixedOperations_noExceptions() throws Exception {
        int threadCount = 4;
        int iterationsPerThread = 200;
        var latch = new java.util.concurrent.CountDownLatch(threadCount);
        var errors = new java.util.concurrent.atomic.AtomicInteger(0);

        // Thread 1: appendText
        new Thread(() -> {
            try {
                store.startStreaming();
                for (int i = 0; i < iterationsPerThread; i++) store.appendText("x");
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        // Thread 2: addToolCallEntry
        new Thread(() -> {
            try {
                for (int i = 0; i < iterationsPerThread; i++)
                    store.addToolCallEntry("tc-" + i, "Tool " + i, null, null);
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        // Thread 3: read snapshots
        new Thread(() -> {
            try {
                for (int i = 0; i < iterationsPerThread; i++) {
                    store.entriesSnapshot();
                    store.getEntries();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        // Thread 4: addSubAgentEntry
        new Thread(() -> {
            try {
                for (int i = 0; i < iterationsPerThread; i++)
                    store.addSubAgentEntry("sa-" + i, "explore", "desc", null,
                        new ChatPanelApi.SubAgentInitialState(null, "running", null, false, null));
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        // Should have entries from all threads
        assertTrue(store.getEntries().size() >= iterationsPerThread);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TurnStatsData makeTurnStats() {
        return new TurnStatsData(
            1000L, 500, 200, 0.01, 3, 10, 5,
            "gpt-4", "1x", List.of(), null, null, ""
        );
    }
}
