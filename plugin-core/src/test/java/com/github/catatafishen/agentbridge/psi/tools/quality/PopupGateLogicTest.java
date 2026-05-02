package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupGateLogicTest {

    private static final String OWNING = "session-A";
    private static final String FOREIGN = "session-B";
    private static final Instant T0 = Instant.parse("2026-04-30T12:00:00Z");

    private static PendingPopupService.Pending pending(int unrelatedCalls) {
        return pending(unrelatedCalls, T0);
    }

    private static PendingPopupService.Pending pending(int unrelatedCalls, Instant createdAt) {
        PopupChoice c = new PopupChoice(PopupChoice.buildValueId("foo", 0), 0,
            "foo", null, true, false);
        PopupSnapshot snap = new PopupSnapshot("Choose Foo", List.of(c),
            PopupSnapshot.KIND_LIST_STEP);
        ContextFingerprint fp = new ContextFingerprint("proj", "/x", 1L, "act|insp");
        return new PendingPopupService.Pending(
            "popup-1", "apply_action", new JsonObject(), null, fp, snap,
            createdAt, OWNING, unrelatedCalls, null
        );
    }

    @Test
    void popupRespondAlwaysAllowed() {
        PendingPopupService.Pending p = pending(0);
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(
            p, PopupGateLogic.POPUP_RESPOND_TOOL, FOREIGN, T0);
        assertInstanceOf(PopupGateLogic.Allow.class, d);
    }

    @Test
    void noPendingAllowsAnyTool() {
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(
            null, "search_text", OWNING, T0);
        assertInstanceOf(PopupGateLogic.Allow.class, d);
    }

    @Test
    void blocksWhenPendingFromOwningSession() {
        PendingPopupService.Pending p = pending(0);
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(p, "search_text", OWNING, T0);
        PopupGateLogic.Block block = assertInstanceOf(PopupGateLogic.Block.class, d);
        assertTrue(block.message().startsWith("Error:"));
        assertTrue(block.message().contains("popup-1"));
        assertTrue(block.message().contains("Auto-cancels"));
    }

    @Test
    void blocksCrossSessionWithDifferentMessage() {
        PendingPopupService.Pending p = pending(0);
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(p, "search_text", FOREIGN, T0);
        PopupGateLogic.Block block = assertInstanceOf(PopupGateLogic.Block.class, d);
        assertTrue(block.message().contains("different MCP session"));
    }

    @Test
    void allowsWithCancelNoteOnFinalUnrelatedCall() {
        // unrelated calls so far = MAX-1 (4); the next (5th) call exhausts the budget
        PendingPopupService.Pending p = pending(PendingPopupService.MAX_UNRELATED_CALLS - 1);
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(p, "search_text", OWNING, T0);
        PopupGateLogic.AllowWithCancelNote awcn =
            assertInstanceOf(PopupGateLogic.AllowWithCancelNote.class, d);
        assertSame(p, awcn.cancelled());
        assertTrue(awcn.note().contains("auto-cancelled"));
        assertTrue(awcn.note().contains("popup-1"));
    }

    @Test
    void crossSessionDoesNotTriggerAutoCancel() {
        PendingPopupService.Pending p = pending(PendingPopupService.MAX_UNRELATED_CALLS - 1);
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(p, "search_text", FOREIGN, T0);
        // Foreign sessions get blocked even at the final-call boundary — they don't progress.
        assertInstanceOf(PopupGateLogic.Block.class, d);
    }

    @Test
    void allowsWithCancelNoteOnTimeExpiry() {
        PendingPopupService.Pending p = pending(0, T0);
        Instant later = T0.plus(PendingPopupService.MAX_AGE).plus(Duration.ofSeconds(1));
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(p, "search_text", FOREIGN, later);
        PopupGateLogic.AllowWithCancelNote awcn =
            assertInstanceOf(PopupGateLogic.AllowWithCancelNote.class, d);
        assertTrue(awcn.note().contains("older than"));
    }

    @Test
    void blockMessageRemainingCounterDecrements() {
        PendingPopupService.Pending p0 = pending(0);
        PendingPopupService.Pending p1 = pending(1);
        String m0 = ((PopupGateLogic.Block) PopupGateLogic.evaluate(p0, "x", OWNING, T0)).message();
        String m1 = ((PopupGateLogic.Block) PopupGateLogic.evaluate(p1, "x", OWNING, T0)).message();
        // First block reports MAX-1 = 4 remaining; second reports MAX-2 = 3
        assertTrue(m0.contains("4 more"), () -> m0);
        assertTrue(m1.contains("3 more"), () -> m1);
    }

    @Test
    void timeoutPathReportsAge() {
        PendingPopupService.Pending p = pending(0, T0);
        Instant later = T0.plus(PendingPopupService.MAX_AGE).plus(Duration.ofSeconds(42));
        PopupGateLogic.AllowWithCancelNote awcn = (PopupGateLogic.AllowWithCancelNote)
            PopupGateLogic.evaluate(p, "search_text", OWNING, later);
        // age should mention seconds count
        long expected = PendingPopupService.MAX_AGE.toSeconds() + 42;
        assertTrue(awcn.note().contains(expected + "s"), awcn::note);
    }

    @Test
    void allowedAtBoundaryMinusOne() {
        // unrelatedCalls = MAX-2 → next call would be MAX-1 → should still be Block (not last)
        PendingPopupService.Pending p = pending(PendingPopupService.MAX_UNRELATED_CALLS - 2);
        PopupGateLogic.Decision d = PopupGateLogic.evaluate(p, "search_text", OWNING, T0);
        assertInstanceOf(PopupGateLogic.Block.class, d);
    }

    @Test
    void evaluateIsPureNoMutation() {
        PendingPopupService.Pending p = pending(0);
        int before = p.unrelatedCallsSinceCreated();
        PopupGateLogic.evaluate(p, "search_text", OWNING, T0);
        assertEquals(before, p.unrelatedCallsSinceCreated());
    }
}
