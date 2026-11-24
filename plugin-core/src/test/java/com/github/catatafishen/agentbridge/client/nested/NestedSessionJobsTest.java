package com.github.catatafishen.agentbridge.client.nested;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.Model;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedSessionJobsTest {

    private ExecutorService executor;
    private NestedSessionJobs jobs;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        jobs = new NestedSessionJobs(() -> executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static NestedSessionRunner.Request request(int runtimeSeconds) {
        return new NestedSessionRunner.Request("task", "/tmp", null, runtimeSeconds);
    }

    private static SessionUpdate.AgentMessageChunk chunk(String text) {
        return new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(text)));
    }

    @Test
    void startReturnsAtOnceAndTheResultIsReadLater() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("working"));
            release.await();
            out.accept(chunk(" done"));
            return new PromptResponse("end_turn", null);
        });
        assertTrue(jobs.tryAcquire());

        NestedSessionJobs.Job job = jobs.start(client, request(30), null, null);

        assertNull(job.await(0), "the session is still running, so there is no result yet");
        assertFalse(job.isDone());
        release.countDown();
        NestedSessionRunner.Result result = job.await(10);
        assertNotNull(result);
        assertEquals(NestedSessionRunner.Status.COMPLETED, result.status());
        assertEquals("working done", result.text());
        assertSame(job, jobs.get(job.id()));
    }

    @Test
    void aRunningJobShowsTheAnswerSoFar() throws InterruptedException {
        CountDownLatch streamed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("first part"));
            streamed.countDown();
            release.await();
            return new PromptResponse("end_turn", null);
        });
        jobs.tryAcquire();

        NestedSessionJobs.Job job = jobs.start(client, request(30), null, null);

        assertTrue(streamed.await(10, TimeUnit.SECONDS));
        assertEquals("first part", job.text());
        release.countDown();
        job.await(10);
    }

    @Test
    void onlyOneSessionHoldsTheSlotAndItIsFreedWhenTheJobEnds() throws InterruptedException {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("ok"));
            return new PromptResponse("end_turn", null);
        });

        assertTrue(jobs.tryAcquire());
        assertFalse(jobs.tryAcquire(), "a second nested session must be refused");

        jobs.start(client, request(30), null, null).await(10);

        assertTrue(jobs.tryAcquire(), "the slot must be free again once the session ended");
    }

    @Test
    void cancelStopsTheSessionAndItEndsAsCancelled() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("partial"));
            started.countDown();
            new CountDownLatch(1).await();
            return new PromptResponse("end_turn", null);
        });
        jobs.tryAcquire();
        NestedSessionJobs.Job job = jobs.start(client, request(300), null, null);
        assertTrue(started.await(10, TimeUnit.SECONDS));

        assertTrue(job.cancel());

        NestedSessionRunner.Result result = job.await(10);
        assertNotNull(result);
        assertEquals(NestedSessionRunner.Status.CANCELLED, result.status());
        assertEquals("partial", result.text());
        assertTrue(client.cancelCalled, "the agent's turn must be stopped");
        assertTrue(client.closed, "the client must be closed");
        assertFalse(job.cancel(), "a finished job cannot be cancelled");
        assertTrue(jobs.tryAcquire(), "cancelling must free the slot");
    }

    @Test
    void theSessionsOwnLimitStillEndsAJobThatRunsTooLong() throws InterruptedException {
        FakeClient client = new FakeClient((req, out) -> {
            new CountDownLatch(1).await();
            return new PromptResponse("end_turn", null);
        });
        jobs.tryAcquire();

        NestedSessionRunner.Result result = jobs.start(client, request(1), null, null).await(10);

        assertNotNull(result);
        assertEquals(NestedSessionRunner.Status.TIMED_OUT, result.status());
    }

    @Test
    void theOutcomeIsReportedToTheCallbackBeforeTheJobCountsAsDone() throws InterruptedException {
        AtomicReference<NestedSessionRunner.Result> reported = new AtomicReference<>();
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("answer"));
            return new PromptResponse("end_turn", null);
        });
        jobs.tryAcquire();

        jobs.start(client, request(30), null, reported::set).await(10);

        assertNotNull(reported.get());
        assertEquals("answer", reported.get().text());
    }

    @Test
    void anUnknownIdIsNotFoundAndRunningIdsListOnlyUnfinishedJobs() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        FakeClient client = new FakeClient((req, out) -> {
            release.await();
            out.accept(chunk("x"));
            return new PromptResponse("end_turn", null);
        });
        jobs.tryAcquire();
        NestedSessionJobs.Job job = jobs.start(client, request(30), null, null);

        assertNull(jobs.get("nope"));
        assertEquals(List.of(job.id()), jobs.runningIds());
        release.countDown();
        job.await(10);
        assertTrue(jobs.runningIds().isEmpty());
    }

    @Test
    void finishedJobsAreDroppedAfterTheKeepTime() throws InterruptedException {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("x"));
            return new PromptResponse("end_turn", null);
        });
        jobs.tryAcquire();
        NestedSessionJobs.Job job = jobs.start(client, request(30), null, null);
        job.await(10);

        jobs.evictOld(System.currentTimeMillis() + NestedSessionJobs.KEEP_FINISHED_MS + 1000);

        assertNull(jobs.get(job.id()));
    }

    private interface Turn {
        PromptResponse run(PromptRequest request, Consumer<SessionUpdate> out) throws Exception;
    }

    private static final class FakeClient extends AbstractClient {
        private final Turn turn;
        volatile boolean closed;
        volatile boolean cancelCalled;

        FakeClient(Turn turn) {
            this.turn = turn;
        }

        @Override
        public String agentId() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public void start() {
            // Nothing to start.
        }

        @Override
        public void stop() {
            closed = true;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public void setPermissionRequestListener(Consumer<PermissionPrompt> listener) {
            // The fake never asks.
        }

        @Override
        public String createSession(String cwd) {
            return "s1";
        }

        @Override
        public void cancelSession(String sessionId) {
            cancelCalled = true;
        }

        @Override
        public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) throws Exception {
            return turn.run(request, onUpdate);
        }

        @Override
        public List<Model> getAvailableModels() {
            return List.of();
        }

        @Override
        public void setModel(String sessionId, String modelId) {
            // The fake has a single model.
        }
    }
}
