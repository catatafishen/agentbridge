package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

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
        assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> SleepTool.awaitDeadline(alreadyExpired(), new CompletableFuture<>()));
    }

    @Test
    void skipEndsALongSleepAtOnce() {
        CompletableFuture<String> skip = new CompletableFuture<>();
        skip.complete("skipped");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SleepTool.awaitDeadline(farFuture(), skip));
    }

    @Test
    void skipFromAnotherThreadWakesTheWaiter() {
        CompletableFuture<String> skip = new CompletableFuture<>();
        Thread skipper = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            skip.complete("skipped");
        });
        skipper.start();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> SleepTool.awaitDeadline(farFuture(), skip));
    }

    @Test
    void cancellationSurfacesWithItsReason() {
        CompletableFuture<String> stopped = new CompletableFuture<>();
        stopped.completeExceptionally(new CancellationException("agent stopped"));

        CancellationException thrown = assertThrows(CancellationException.class,
            () -> SleepTool.awaitDeadline(farFuture(), stopped));

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
    void isASelfTimedReadOnlyTool() {
        SleepTool tool = new SleepTool(null);

        assertEquals("sleep", tool.id());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.managesOwnTimeout(), "must not trigger the generic 'still running' dialog");
        assertFalse(tool.needsWriteLock(), "a sleep must never hold the global write semaphore");
    }
}
