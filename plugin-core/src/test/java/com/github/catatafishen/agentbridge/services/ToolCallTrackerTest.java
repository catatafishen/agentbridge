package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ToolCallTracker}.
 *
 * <p>Tests verify state and data changes directly — no listeners are registered,
 * so the {@code ApplicationManager.invokeLater()} calls in fire* methods are
 * safely short-circuited by the {@code if (listeners.isEmpty()) return;} guard.
 */
class ToolCallTrackerTest {

    private ToolCallTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ToolCallTracker(mock(Project.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static JsonObject args(String key, String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return obj;
    }

    private static JsonObject argsReadFile(String path) {
        return args("path", path);
    }

    // ── 1. ACP-first registration ────────────────────────────────────────────

    @Test
    void acpRegister_createsNewRecord_withPendingState() {
        JsonObject a = argsReadFile("/src/Main.java");
        ToolCallRecord rec = tracker.acpRegister(
            "acp-1", null, "read_file", a, "file",
            ToolCallRecord.RoutingType.REGULAR, null);

        assertNotNull(rec);
        assertEquals("acp-1", rec.getAcpClientId());
        assertEquals("read_file", rec.getAcpTitle());
        assertEquals(ToolCallRecord.State.PENDING, rec.getState());
        assertTrue(rec.isAcpOnly());
        assertFalse(rec.isCorrelated());
        assertNull(rec.getMcpToolName());
        assertEquals(1, tracker.liveCount());
    }

    @Test
    void acpRegister_withoutArgs_createsRecord() {
        ToolCallRecord rec = tracker.acpRegister(
            "acp-2", null, "some_tool", null, null,
            ToolCallRecord.RoutingType.REGULAR, null);

        assertNotNull(rec);
        assertEquals("acp-2", rec.getAcpClientId());
        assertNull(rec.getArgsHash());
        assertEquals(ToolCallRecord.State.PENDING, rec.getState());
    }

    @Test
    void acpRegister_setsRoutingType() {
        ToolCallRecord rec = tracker.acpRegister(
            "acp-sub", null, "Task", null, null,
            ToolCallRecord.RoutingType.SUB_AGENT, null);

        assertEquals(ToolCallRecord.RoutingType.SUB_AGENT, rec.getRoutingType());
    }

    // ── 2. MCP-first registration ────────────────────────────────────────────

    @Test
    void mcpRegister_createsNewRecord_withRunningState() {
        JsonObject a = argsReadFile("/src/App.java");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, "file", null);

        assertNotNull(rec);
        assertEquals("read_file", rec.getMcpToolName());
        assertEquals(ToolCallRecord.State.RUNNING, rec.getState());
        assertTrue(rec.isMcpOnly());
        assertFalse(rec.isCorrelated());
        assertNull(rec.getAcpClientId());
        assertEquals(1, tracker.liveCount());
    }

    // ── 3. Correlation via toolUseId (Priority 0) ────────────────────────────

    @Test
    void correlation_viaToolUseId_acpFirst() {
        // ACP registers first with toolUseId
        JsonObject a = argsReadFile("/file1.txt");
        ToolCallRecord acpRec = tracker.acpRegister(
            "acp-10", null, "read_file", a, "file",
            ToolCallRecord.RoutingType.REGULAR, "tool-use-42");

        assertEquals(1, tracker.liveCount());
        assertTrue(acpRec.isAcpOnly());

        // MCP registers second with same toolUseId → should correlate
        JsonObject mcpArgs = argsReadFile("/file1.txt");
        ToolCallRecord mcpRec = tracker.mcpRegister("read_file", mcpArgs, "file", "tool-use-42");

        // Same record object returned (merged)
        assertEquals(acpRec.getRecordId(), mcpRec.getRecordId());
        assertTrue(mcpRec.isCorrelated());
        assertEquals("acp-10", mcpRec.getAcpClientId());
        assertEquals("read_file", mcpRec.getMcpToolName());
        assertEquals(ToolCallRecord.State.RUNNING, mcpRec.getState());
        // Still only 1 live record
        assertEquals(1, tracker.liveCount());
    }

    @Test
    void correlation_viaToolUseId_mcpFirst() {
        // MCP registers first with toolUseId
        JsonObject a = argsReadFile("/file2.txt");
        ToolCallRecord mcpRec = tracker.mcpRegister("edit_text", a, "file", "tool-use-99");

        assertEquals(1, tracker.liveCount());
        assertTrue(mcpRec.isMcpOnly());

        // ACP registers with same toolUseId → should correlate
        tracker.acpRegister(
            "acp-20", null, "edit_text", a, "file",
            ToolCallRecord.RoutingType.REGULAR, "tool-use-99");

        // The record should be the same mcpRec updated in-place
        assertTrue(mcpRec.isCorrelated());
        assertEquals("acp-20", mcpRec.getAcpClientId());
        assertEquals("edit_text", mcpRec.getMcpToolName());
    }

    // ── 4. Correlation via args hash (Priority 1) ────────────────────────────

    @Test
    void correlation_viaArgsHash_acpFirst() {
        // ACP registers first
        JsonObject a = argsReadFile("/project/build.gradle");
        ToolCallRecord acpRec = tracker.acpRegister(
            "acp-30", null, "read_file", a, "file",
            ToolCallRecord.RoutingType.REGULAR, null);

        assertEquals(1, tracker.liveCount());

        // MCP registers with same args → hash matches → correlates
        JsonObject mcpArgs = argsReadFile("/project/build.gradle");
        ToolCallRecord mcpRec = tracker.mcpRegister("read_file", mcpArgs, "file", null);

        assertEquals(acpRec.getRecordId(), mcpRec.getRecordId());
        assertTrue(mcpRec.isCorrelated());
        assertEquals("acp-30", mcpRec.getAcpClientId());
        assertEquals("read_file", mcpRec.getMcpToolName());
        assertEquals(1, tracker.liveCount());
    }

    // ── 5. MCP-first correlation via args hash ───────────────────────────────

    @Test
    void correlation_viaArgsHash_mcpFirst() {
        // MCP registers first
        JsonObject a = argsReadFile("/src/Test.java");
        ToolCallRecord mcpRec = tracker.mcpRegister("read_file", a, "file", null);

        assertEquals(1, tracker.liveCount());

        // ACP registers with same args → correlates
        JsonObject acpArgs = argsReadFile("/src/Test.java");
        ToolCallRecord acpRec = tracker.acpRegister(
            "acp-40", null, "read_file", acpArgs, "file",
            ToolCallRecord.RoutingType.REGULAR, null);

        assertSame(mcpRec, acpRec);
        assertTrue(acpRec.isCorrelated());
        assertEquals(1, tracker.liveCount());
    }

    @Test
    void noCorrelation_whenArgsDiffer() {
        // ACP registers
        JsonObject a1 = argsReadFile("/file-a.txt");
        tracker.acpRegister("acp-50", null, "read_file", a1, "file",
            ToolCallRecord.RoutingType.REGULAR, null);

        // MCP registers with different args → no correlation
        JsonObject a2 = argsReadFile("/file-b.txt");
        tracker.mcpRegister("read_file", a2, "file", null);

        assertEquals(2, tracker.liveCount());
    }

    // ── 6. mcpComplete ───────────────────────────────────────────────────────

    @Test
    void mcpComplete_setsResultAndCompletedState() {
        JsonObject a = argsReadFile("/pom.xml");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, "file", null);
        String recordId = rec.getRecordId();

        tracker.mcpComplete(recordId, "<project>...</project>", true);

        assertEquals(ToolCallRecord.State.COMPLETED, rec.getState());
        assertEquals("<project>...</project>", rec.getMcpResult());
        assertTrue(rec.isMcpSuccess());
        assertTrue(rec.getMcpCompletedAt() > 0);
        // Record is still live (only acpComplete flushes)
        assertEquals(1, tracker.liveCount());
    }

    @Test
    void mcpComplete_failure_setsFailedState() {
        JsonObject a = argsReadFile("/missing.txt");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, "file", null);
        String recordId = rec.getRecordId();

        tracker.mcpComplete(recordId, "File not found", false);

        assertEquals(ToolCallRecord.State.FAILED, rec.getState());
        assertFalse(rec.isMcpSuccess());
        assertEquals("File not found", rec.getMcpResult());
    }

    @Test
    void mcpComplete_unknownRecordId_doesNotThrow() {
        // Should just log a warning, not NPE
        assertDoesNotThrow(() -> tracker.mcpComplete("nonexistent-id", "result", true));
    }

    // ── 7. acpComplete ───────────────────────────────────────────────────────

    @Test
    void acpComplete_flushesRecord() {
        JsonObject a = argsReadFile("/src/Foo.java");
        ToolCallRecord rec = tracker.acpRegister(
            "acp-60", null, "read_file", a, "file",
            ToolCallRecord.RoutingType.REGULAR, null);

        assertEquals(1, tracker.liveCount());

        // Complete MCP side first
        tracker.mcpRegister("read_file", argsReadFile("/src/Foo.java"), "file", null);
        // After correlation, still 1 record
        String recordId = rec.getRecordId();
        tracker.mcpComplete(recordId, "content", true);

        // ACP complete → terminal state + flush
        tracker.acpComplete("acp-60", true);

        assertEquals(ToolCallRecord.State.COMPLETED, rec.getState());
        assertEquals(0, tracker.liveCount());
        assertNull(tracker.findByAcpId("acp-60"));
        assertNull(tracker.findByRecordId(recordId));
    }

    @Test
    void acpComplete_failure_setsFailedState() {
        JsonObject a = argsReadFile("/src/Bar.java");
        tracker.acpRegister("acp-70", null, "read_file", a, "file",
            ToolCallRecord.RoutingType.REGULAR, null);

        tracker.acpComplete("acp-70", false);

        assertEquals(0, tracker.liveCount());
    }

    @Test
    void acpComplete_unknownAcpId_doesNotThrow() {
        assertDoesNotThrow(() -> tracker.acpComplete("unknown-acp", true));
    }

    // ── 8. Late args via acpProvideArgs ──────────────────────────────────────

    @Test
    void acpProvideArgs_updatesHashAndCorrelates() {
        // ACP registers without args
        ToolCallRecord acpRec = tracker.acpRegister(
            "acp-80", null, "run_command", null, null,
            ToolCallRecord.RoutingType.REGULAR, null);
        assertNull(acpRec.getArgsHash());

        // MCP registers with args (no ACP match yet — ACP had no hash)
        JsonObject mcpArgs = new JsonObject();
        mcpArgs.addProperty("command", "ls -la");
        tracker.mcpRegister("run_command", mcpArgs, "shell", null);

        // 2 separate records at this point
        assertEquals(2, tracker.liveCount());
        assertFalse(acpRec.isCorrelated());

        // Late args arrive
        JsonObject lateArgs = new JsonObject();
        lateArgs.addProperty("command", "ls -la");
        tracker.acpProvideArgs("acp-80", lateArgs);

        // ACP record should now have MCP data merged in
        assertTrue(acpRec.isCorrelated());
        assertEquals("run_command", acpRec.getMcpToolName());
        // MCP-first record should be removed (merged into ACP record)
        assertEquals(1, tracker.liveCount());
        assertNotNull(tracker.findByAcpId("acp-80"));
    }

    @Test
    void acpProvideArgs_noMatchingMcp_justUpdatesHash() {
        tracker.acpRegister("acp-81", null, "some_tool", null, null,
            ToolCallRecord.RoutingType.REGULAR, null);

        JsonObject a = args("key", "value");
        tracker.acpProvideArgs("acp-81", a);

        ToolCallRecord rec = tracker.findByAcpId("acp-81");
        assertNotNull(rec);
        assertNotNull(rec.getArgsHash());
        assertFalse(rec.isCorrelated());
    }

    @Test
    void acpProvideArgs_unknownAcpId_doesNotThrow() {
        JsonObject a = args("x", "y");
        assertDoesNotThrow(() -> tracker.acpProvideArgs("unknown", a));
    }

    // ── 9. Flush logic — older uncorrelated MCP-only records ─────────────────

    @Test
    void flush_olderCompletedMcpOnlyRecords_whenAcpCorrelates() {
        // MCP record A (will never get ACP match)
        JsonObject a1 = args("cmd", "echo a");
        ToolCallRecord mcpA = tracker.mcpRegister("run_command", a1, "shell", null);
        tracker.mcpComplete(mcpA.getRecordId(), "a-output", true);

        // MCP record B (will never get ACP match)
        JsonObject a2 = args("cmd", "echo b");
        ToolCallRecord mcpB = tracker.mcpRegister("run_command", a2, "shell", null);
        tracker.mcpComplete(mcpB.getRecordId(), "b-output", true);

        // MCP record C (will get ACP match)
        JsonObject a3 = args("cmd", "echo c");
        ToolCallRecord mcpC = tracker.mcpRegister("run_command", a3, "shell", null);

        assertEquals(3, tracker.liveCount());

        // ACP arrives matching record C → correlates, and flushes A and B (completed, uncorrelated, older)
        JsonObject acpArgs = args("cmd", "echo c");
        ToolCallRecord correlated = tracker.acpRegister(
            "acp-90", null, "run_command", acpArgs, "shell",
            ToolCallRecord.RoutingType.REGULAR, null);

        assertSame(mcpC, correlated);
        assertTrue(correlated.isCorrelated());
        // A and B should be flushed (completed MCP-only, older than the correlated record)
        assertEquals(1, tracker.liveCount());
        assertNull(tracker.findByRecordId(mcpA.getRecordId()));
        assertNull(tracker.findByRecordId(mcpB.getRecordId()));
        // Flushed records should be set to EXTERNAL state
        assertEquals(ToolCallRecord.State.EXTERNAL, mcpA.getState());
        assertEquals(ToolCallRecord.State.EXTERNAL, mcpB.getState());
    }

    // ── 10. Flush ordering — running MCP-only records should NOT be flushed ──

    @Test
    void flush_doesNotFlush_runningMcpOnlyRecords() {
        // MCP record A — still running (no mcpComplete)
        JsonObject a1 = args("cmd", "long-running");
        ToolCallRecord mcpA = tracker.mcpRegister("run_command", a1, "shell", null);

        // MCP record B — completed
        JsonObject a2 = args("cmd", "done");
        ToolCallRecord mcpB = tracker.mcpRegister("run_command", a2, "shell", null);
        tracker.mcpComplete(mcpB.getRecordId(), "done-result", true);

        // MCP record C — will be correlated
        JsonObject a3 = args("cmd", "target");
        tracker.mcpRegister("run_command", a3, "shell", null);

        assertEquals(3, tracker.liveCount());

        // ACP correlates with C
        tracker.acpRegister("acp-100", null, "run_command", args("cmd", "target"), "shell",
            ToolCallRecord.RoutingType.REGULAR, null);

        // A is still running → NOT flushed; B is completed → flushed
        assertNotNull(tracker.findByRecordId(mcpA.getRecordId()),
            "Running MCP-only record should NOT be flushed");
        assertNull(tracker.findByRecordId(mcpB.getRecordId()),
            "Completed MCP-only record should be flushed");
        assertEquals(2, tracker.liveCount()); // A (running) + C (correlated)
    }

    // ── 11. clear() ──────────────────────────────────────────────────────────

    @Test
    void clear_removesAllRecords() {
        tracker.acpRegister("acp-a", null, "tool_a", argsReadFile("/a"), null,
            ToolCallRecord.RoutingType.REGULAR, null);
        tracker.mcpRegister("tool_b", argsReadFile("/b"), null, null);
        tracker.acpRegister("acp-c", null, "tool_c", argsReadFile("/c"), null,
            ToolCallRecord.RoutingType.SUB_AGENT, null);

        assertEquals(3, tracker.liveCount());

        tracker.clear();

        assertEquals(0, tracker.liveCount());
        assertNull(tracker.findByAcpId("acp-a"));
        assertNull(tracker.findByAcpId("acp-c"));
    }

    // ── 11b. failAllInFlight() ───────────────────────────────────────────────

    @Test
    void failAllInFlight_marksRecordsFailed_andClears() {
        ToolCallRecord a = tracker.acpRegister("acp-a", null, "tool_a", argsReadFile("/a"), null,
            ToolCallRecord.RoutingType.REGULAR, null);
        ToolCallRecord b = tracker.mcpRegister("tool_b", argsReadFile("/b"), null, null);

        assertEquals(2, tracker.liveCount());

        tracker.failAllInFlight("agent process exited");

        assertEquals(0, tracker.liveCount());
        assertEquals(ToolCallRecord.State.FAILED, a.getState());
        assertEquals(ToolCallRecord.State.FAILED, b.getState());
        assertNull(tracker.findByAcpId("acp-a"));
        assertNull(tracker.findByRecordId(b.getRecordId()));
    }

    @Test
    void failAllInFlight_emptyTracker_doesNotThrow() {
        assertDoesNotThrow(() -> tracker.failAllInFlight("test"));
        assertEquals(0, tracker.liveCount());
    }

    // ── 12. Query methods ────────────────────────────────────────────────────

    @Test
    void findByAcpId_returnsCorrectRecord() {
        JsonObject a = argsReadFile("/hello.txt");
        ToolCallRecord rec = tracker.acpRegister("acp-q1", null, "read_file", a, null,
            ToolCallRecord.RoutingType.REGULAR, null);

        assertSame(rec, tracker.findByAcpId("acp-q1"));
        assertNull(tracker.findByAcpId("nonexistent"));
    }

    @Test
    void findByRecordId_returnsCorrectRecord() {
        JsonObject a = argsReadFile("/world.txt");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, "file", null);

        assertSame(rec, tracker.findByRecordId(rec.getRecordId()));
        assertNull(tracker.findByRecordId("no-such-id"));
    }

    @Test
    void getStoredResult_returnsResultAfterMcpComplete() {
        JsonObject a = argsReadFile("/data.json");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, null, null);

        assertNull(tracker.getStoredResult(rec.getRecordId()));

        tracker.mcpComplete(rec.getRecordId(), "{\"key\":\"value\"}", true);

        assertEquals("{\"key\":\"value\"}", tracker.getStoredResult(rec.getRecordId()));
    }

    @Test
    void getStoredResult_returnsNullForUnknownId() {
        assertNull(tracker.getStoredResult("does-not-exist"));
    }

    @Test
    void findByMcpCall_returnsMatchingRecord() {
        JsonObject a = argsReadFile("/build.gradle.kts");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, "file", null);

        JsonObject queryArgs = argsReadFile("/build.gradle.kts");
        ToolCallRecord found = tracker.findByMcpCall("read_file", queryArgs);

        assertNotNull(found);
        assertEquals(rec.getRecordId(), found.getRecordId());
    }

    @Test
    void findByMcpCall_returnsNull_whenToolNameDiffers() {
        tracker.mcpRegister("read_file", argsReadFile("/x.txt"), null, null);

        assertNull(tracker.findByMcpCall("write_file", argsReadFile("/x.txt")));
    }

    @Test
    void findByMcpCall_returnsNull_whenArgsDiffer() {
        tracker.mcpRegister("read_file", argsReadFile("/x.txt"), null, null);

        assertNull(tracker.findByMcpCall("read_file", argsReadFile("/y.txt")));
    }

    @Test
    void findByMcpCall_returnsNewest_whenMultipleMatch() {
        JsonObject a = argsReadFile("/dup.txt");
        tracker.mcpRegister("read_file", a, null, null);
        ToolCallRecord second = tracker.mcpRegister("read_file", a, null, null);

        ToolCallRecord found = tracker.findByMcpCall("read_file", argsReadFile("/dup.txt"));
        assertNotNull(found);
        assertEquals(second.getRecordId(), found.getRecordId());
    }

    // ── Additional edge cases ────────────────────────────────────────────────

    @Test
    void acpRegister_setsDisplayNameToAcpTitle() {
        ToolCallRecord rec = tracker.acpRegister(
            "acp-dn", null, "my_tool_title", argsReadFile("/t.txt"), null,
            ToolCallRecord.RoutingType.REGULAR, null);

        assertEquals("my_tool_title", rec.getDisplayName());
    }

    @Test
    void mcpRegister_overridesDisplayName() {
        // ACP first sets displayName from title
        JsonObject a = argsReadFile("/dn.txt");
        ToolCallRecord rec = tracker.acpRegister(
            "acp-dn2", null, "acp_title", a, null,
            ToolCallRecord.RoutingType.REGULAR, null);
        assertEquals("acp_title", rec.getDisplayName());

        // MCP correlates → displayName should be overridden to mcpToolName
        tracker.mcpRegister("actual_mcp_name", argsReadFile("/dn.txt"), null, null);
        assertEquals("actual_mcp_name", rec.getDisplayName());
    }

    @Test
    void effectiveToolName_prefersMcp() {
        JsonObject a = argsReadFile("/eff.txt");
        ToolCallRecord rec = tracker.acpRegister(
            "acp-eff", null, "acp_name", a, null,
            ToolCallRecord.RoutingType.REGULAR, null);
        assertEquals("acp_name", rec.getEffectiveToolName());

        tracker.mcpRegister("mcp_name", argsReadFile("/eff.txt"), null, null);
        assertEquals("mcp_name", rec.getEffectiveToolName());
    }

    @Test
    void acpSequence_incrementsWithEachAcpRegistration() {
        ToolCallRecord r1 = tracker.acpRegister("a1", null, "t1", argsReadFile("/1"), null,
            ToolCallRecord.RoutingType.REGULAR, null);
        ToolCallRecord r2 = tracker.acpRegister("a2", null, "t2", argsReadFile("/2"), null,
            ToolCallRecord.RoutingType.REGULAR, null);
        ToolCallRecord r3 = tracker.acpRegister("a3", null, "t3", argsReadFile("/3"), null,
            ToolCallRecord.RoutingType.REGULAR, null);

        assertEquals(1, r1.getAcpSequence());
        assertEquals(2, r2.getAcpSequence());
        assertEquals(3, r3.getAcpSequence());
    }

    @Test
    void liveCount_reflectsCurrentState() {
        assertEquals(0, tracker.liveCount());

        tracker.acpRegister("a", null, "t", argsReadFile("/a"), null,
            ToolCallRecord.RoutingType.REGULAR, null);
        assertEquals(1, tracker.liveCount());

        tracker.mcpRegister("t2", argsReadFile("/b"), null, null);
        assertEquals(2, tracker.liveCount());

        tracker.clear();
        assertEquals(0, tracker.liveCount());
    }

    @Test
    void mcpComplete_setsResultBytes() {
        JsonObject a = argsReadFile("/bytes.txt");
        ToolCallRecord rec = tracker.mcpRegister("read_file", a, null, null);

        String result = "Hello, world!";
        tracker.mcpComplete(rec.getRecordId(), result, true);

        assertEquals(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, rec.getResultBytes());
    }

    @Test
    void acpProvideArgs_carriesOverMcpResult_whenMcpAlreadyCompleted() {
        // ACP without args
        ToolCallRecord acpRec = tracker.acpRegister(
            "acp-late", null, "tool", null, null,
            ToolCallRecord.RoutingType.REGULAR, null);

        // MCP registers and completes
        JsonObject mcpArgs = args("key", "val");
        ToolCallRecord mcpOnly = tracker.mcpRegister("tool", mcpArgs, null, null);
        tracker.mcpComplete(mcpOnly.getRecordId(), "the-result", true);

        assertEquals(2, tracker.liveCount());

        // Late args arrive → correlates and merges MCP result
        tracker.acpProvideArgs("acp-late", args("key", "val"));

        assertTrue(acpRec.isCorrelated());
        assertEquals("the-result", acpRec.getMcpResult());
        assertTrue(acpRec.isMcpSuccess());
        assertEquals(1, tracker.liveCount());
    }

    @Test
    void correlation_viaToolUseId_matchesAcpClientIdAsFallback() {
        // ACP registers with acpClientId = "tool-use-fallback", no toolUseId
        JsonObject a = argsReadFile("/fallback.txt");
        tracker.acpRegister("tool-use-fallback", null, "read_file", a, null,
            ToolCallRecord.RoutingType.REGULAR, null);

        // MCP registers with toolUseId matching the acpClientId
        JsonObject mcpArgs = argsReadFile("/different-args.txt");
        ToolCallRecord mcpRec = tracker.mcpRegister("read_file", mcpArgs, null, "tool-use-fallback");

        // Should correlate via the acpClientId == toolUseId fallback in mcpRegister
        assertTrue(mcpRec.isCorrelated());
        assertEquals("tool-use-fallback", mcpRec.getAcpClientId());
    }

    @Test
    void kind_setFromAcpRegister() {
        JsonObject a = argsReadFile("/k.txt");
        ToolCallRecord rec = tracker.acpRegister("acp-k", null, "t", a, "git",
            ToolCallRecord.RoutingType.REGULAR, null);
        assertEquals("git", rec.getKind());
    }

    @Test
    void kind_nullFromAcpRegister_doesNotOverwrite() {
        JsonObject a = argsReadFile("/k2.txt");
        ToolCallRecord rec = tracker.acpRegister("acp-k2", null, "t", a, null,
            ToolCallRecord.RoutingType.REGULAR, null);
        assertNull(rec.getKind());
    }
}
