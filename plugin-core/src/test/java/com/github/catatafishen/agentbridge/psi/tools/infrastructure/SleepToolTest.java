package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.github.catatafishen.agentbridge.ui.SleepControls;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepToolTest {

    private static final int MAX = McpRequestDeadline.MAX_TIMEOUT_SECONDS;

    private static SleepDeadline alreadyExpired() {
        return new SleepDeadline(System.currentTimeMillis() - 10_000L, 1, MAX);
    }

    private static SleepDeadline farFuture() {
        return new SleepDeadline(System.currentTimeMillis(), MAX, MAX);
    }

    @Test
    void returnsImmediatelyWhenTheDeadlineHasAlreadyPassed() {
        SleepDeadline expired = alreadyExpired();
        CompletableFuture<String> neverSkipped = new CompletableFuture<>();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SleepTool.awaitDeadline(expired, neverSkipped));
    }

    @Test
    void skipEndsALongSleepAtOnce() {
        SleepDeadline deadline = farFuture();
        CompletableFuture<String> skip = new CompletableFuture<>();
        skip.complete("skipped");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SleepTool.awaitDeadline(deadline, skip));
    }

    @Test
    void skipFromAnotherThreadWakesTheWaiter() {
        SleepDeadline deadline = farFuture();
        CompletableFuture<String> skip = new CompletableFuture<>();
        // Completes the signal 100ms from now on another thread, i.e. while the waiter is parked.
        skip.completeAsync(() -> "skipped", CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> SleepTool.awaitDeadline(deadline, skip));
    }

    @Test
    void cancellationSurfacesWithItsReason() {
        SleepDeadline deadline = farFuture();
        CompletableFuture<String> stopped = new CompletableFuture<>();
        stopped.completeExceptionally(new CancellationException("agent stopped"));

        CancellationException thrown = assertThrows(CancellationException.class,
            () -> SleepTool.awaitDeadline(deadline, stopped));

        assertEquals("agent stopped", thrown.getMessage());
    }

    @Test
    void completionMessageDoesNotRevealHowTheSleepEnded() {
        String message = SleepTool.completionMessage(60);

        assertEquals("Sleep complete (requested 60s).", message);
        assertFalse(message.toLowerCase().contains("skip"));
        assertFalse(message.toLowerCase().contains("user"));
        assertFalse(message.toLowerCase().contains("extend"));
    }

    @Test
    void noNoticeWhenTheRequestFitsTheCap() {
        assertNull(SleepTool.clampNotice(1));
        assertNull(SleepTool.clampNotice(MAX));
    }

    @Test
    void noticeExplainsAClampedRequestAndHowToKeepWaiting() {
        String notice = SleepTool.clampNotice(MAX + 1);

        assertNotNull(notice);
        assertTrue(notice.contains("requested " + (MAX + 1) + "s was reduced to " + MAX + "s"), notice);
        assertTrue(notice.contains("Call sleep again"), notice);
    }

    @Test
    void rejectsNonPositiveSeconds() throws Exception {
        for (int bad : new int[]{0, -5}) {
            JsonObject args = new JsonObject();
            args.addProperty("seconds", bad);

            String result = new SleepTool(null).execute(args);

            assertTrue(result.startsWith("Error"), result);
            assertTrue(result.contains("positive"), result);
        }
    }

    @Test
    void rejectsNonNumericSeconds() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("seconds", "soon");

        String result = new SleepTool(null).execute(args);

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("whole number"), result);
    }

    @Test
    void rejectsFractionalSecondsInsteadOfTruncatingThem() throws Exception {
        JsonObject args = JsonParser.parseString("{\"seconds\":1.5}").getAsJsonObject();

        String result = new SleepTool(null).execute(args);

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("whole number"), result);
    }

    @Test
    void parseSecondsAcceptsWholeNumbers() {
        assertEquals(5, SleepTool.parseSeconds(new JsonPrimitive(5)));
        assertEquals(5, SleepTool.parseSeconds(new JsonPrimitive(5.0)));
        assertEquals(-3, SleepTool.parseSeconds(new JsonPrimitive(-3)));
    }

    @Test
    void parseSecondsRejectsFractionalOutOfRangeAndNonNumeric() {
        assertNull(SleepTool.parseSeconds(new JsonPrimitive(1.5)));
        assertNull(SleepTool.parseSeconds(new JsonPrimitive(3_000_000_000L)));
        assertNull(SleepTool.parseSeconds(new JsonPrimitive("soon")));
        assertNull(SleepTool.parseSeconds(new JsonObject()));
        assertNull(SleepTool.parseSeconds(JsonNull.INSTANCE));
        assertNull(SleepTool.parseSeconds(null));
    }

    @Test
    void finishedMessageQuotesTheRequestedLengthEvenWhenItWasClamped() {
        int requested = MAX + 330;

        String message = SleepTool.finishedMessage(requested);

        assertTrue(message.contains("requested " + requested + "s was reduced to " + MAX + "s"), message);
        assertTrue(message.contains("Sleep complete (requested " + requested + "s)."), message);
        assertFalse(message.contains("Sleep complete (requested " + MAX + "s)"), message);
    }

    @Test
    void finishedMessageIsJustTheCompletionLineWhenNothingWasClamped() {
        assertEquals("Sleep complete (requested 30s).", SleepTool.finishedMessage(30));
    }

    @Test
    void controlsExtendTheSharedDeadlineAndReportItsCap() {
        SleepDeadline deadline = farFuture();
        SleepControls controls = SleepTool.controlsFor(deadline, new CompletableFuture<>());
        long before = deadline.deadlineMs();

        assertEquals(deadline.maxDeadlineMs(), controls.getMaxDeadlineEpochMs());
        assertEquals(before, controls.extend(10), "already at the cap: extension is a no-op");
        assertEquals(before, deadline.deadlineMs());
    }

    @Test
    void controlsExtendMovesAShortSleepLater() {
        SleepDeadline deadline = new SleepDeadline(System.currentTimeMillis(), 20, MAX);
        SleepControls controls = SleepTool.controlsFor(deadline, new CompletableFuture<>());
        long before = deadline.deadlineMs();

        assertEquals(before + 30_000L, controls.extend(30));
        assertEquals(before + 30_000L, deadline.deadlineMs());
    }

    @Test
    void controlsSkipCompletesTheSignalTheWaiterIsParkedOn() {
        CompletableFuture<String> skipSignal = new CompletableFuture<>();
        SleepControls controls = SleepTool.controlsFor(farFuture(), skipSignal);

        controls.skip();

        assertTrue(skipSignal.isDone());
        assertFalse(skipSignal.isCompletedExceptionally());
    }

    @Test
    void isASelfTimedReadOnlyTool() {
        SleepTool tool = new SleepTool(null);

        assertEquals("sleep", tool.id());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.managesOwnTimeout(), "must not trigger the generic 'still running' dialog");
        assertFalse(tool.needsWriteLock(), "a sleep must never hold the global write semaphore");
    }
}
