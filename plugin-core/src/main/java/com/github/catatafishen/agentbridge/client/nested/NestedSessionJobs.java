package com.github.catatafishen.agentbridge.client.nested;

import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Nested sessions that run in the background, so that one may take longer than an MCP client waits for a
 * single tool call. The caller starts a job, gets its id at once, and polls it with {@link Job#await}.
 *
 * <p>It also owns the rule that only one nested session runs per project at a time: {@link #tryAcquire}
 * before starting, and the slot is given back when the session ends (a background job does that itself).
 * That rule also stops a nested session from starting another one.
 *
 * <p>Finished jobs are kept for {@link #KEEP_FINISHED_MS} so the result can still be read, and dropped
 * when the next job starts.
 */
public final class NestedSessionJobs {

    private static final Logger LOG = Logger.getInstance(NestedSessionJobs.class);
    private static final String LOG_PREFIX = "Nested session job ";

    /**
     * How long a finished job's result stays readable.
     */
    static final long KEEP_FINISHED_MS = TimeUnit.HOURS.toMillis(1);

    private final Supplier<ExecutorService> executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    /**
     * @param executor supplies the pool that runs the sessions; asked when a job starts, not before
     */
    public NestedSessionJobs(@NotNull Supplier<ExecutorService> executor) {
        this.executor = executor;
    }

    /**
     * Takes the one slot for a nested session.
     *
     * @return {@code false} if a nested session is already running
     */
    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }

    /**
     * Gives the slot back. A job started with {@link #start} does this itself.
     */
    public void release() {
        busy.set(false);
    }

    /**
     * Starts {@code request} in the background and returns at once. The caller must hold the slot
     * ({@link #tryAcquire}); it is released when the session ends, whatever the outcome.
     *
     * @param view       shows the progress, or {@code null}
     * @param onFinished called on the session's thread with the outcome, before the job counts as done
     */
    public @NotNull Job start(@NotNull AbstractClient client, @NotNull NestedSessionRunner.Request request,
                              @Nullable NestedSessionRunner.Listener view,
                              @Nullable Consumer<NestedSessionRunner.Result> onFinished) {
        evictOld(System.currentTimeMillis());
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8), view);
        jobs.put(job.id(), job);
        ExecutorService pool = executor.get();
        NestedSessionRunner runner = new NestedSessionRunner(pool);
        try {
            pool.execute(() -> runJob(runner, client, request, job, onFinished));
        } catch (RuntimeException e) {
            jobs.remove(job.id());
            throw e;
        }
        return job;
    }

    private void runJob(@NotNull NestedSessionRunner runner, @NotNull AbstractClient client,
                        @NotNull NestedSessionRunner.Request request, @NotNull Job job,
                        @Nullable Consumer<NestedSessionRunner.Result> onFinished) {
        NestedSessionRunner.Result result;
        try {
            if (job.begin()) {
                result = runner.run(client, request, job);
            } else {
                closeUnused(client);
                result = new NestedSessionRunner.Result(NestedSessionRunner.Status.CANCELLED, "",
                    "The nested session was cancelled before it started.");
            }
        } catch (RuntimeException e) {
            LOG.warn(LOG_PREFIX + job.id() + " failed", e);
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            result = new NestedSessionRunner.Result(NestedSessionRunner.Status.FAILED, job.text(), message);
        } finally {
            job.detach();
        }
        try {
            if (onFinished != null) {
                onFinished.accept(result);
            }
        } catch (RuntimeException e) {
            LOG.warn(LOG_PREFIX + job.id() + ": could not report the result", e);
        } finally {
            release();
            job.complete(result);
        }
    }

    private static void closeUnused(@NotNull AbstractClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            LOG.warn("Could not close the client of a cancelled nested session", e);
        }
    }

    /**
     * The job with {@code id}, or {@code null} if there is none (never started, or its result was dropped).
     */
    public @Nullable Job get(@NotNull String id) {
        return jobs.get(id.trim());
    }

    /**
     * The ids of the jobs that have not finished.
     */
    public @NotNull List<String> runningIds() {
        return jobs.values().stream().filter(j -> !j.isDone()).map(Job::id).sorted().toList();
    }

    void evictOld(long now) {
        jobs.values().removeIf(j -> j.isDone() && now - j.finishedAtMs() > KEEP_FINISHED_MS);
    }

    /**
     * One background nested session. It collects the answer as it streams, so a caller polling a running job
     * can see what there is so far.
     */
    public static final class Job implements NestedSessionRunner.Listener {
        private final String id;
        private final @Nullable NestedSessionRunner.Listener delegate;
        private final StringBuilder text = new StringBuilder();
        private final CompletableFuture<NestedSessionRunner.Result> done = new CompletableFuture<>();
        private volatile long finishedAtMs;
        private @Nullable Thread thread;
        private boolean cancelled;

        private Job(@NotNull String id, @Nullable NestedSessionRunner.Listener delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        public @NotNull String id() {
            return id;
        }

        /**
         * The answer streamed so far.
         */
        public synchronized @NotNull String text() {
            return text.toString().trim();
        }

        public boolean isDone() {
            return done.isDone();
        }

        long finishedAtMs() {
            return finishedAtMs;
        }

        /**
         * Waits up to {@code seconds} for the session to end.
         *
         * @return the outcome, or {@code null} if it is still running
         */
        public @Nullable NestedSessionRunner.Result await(long seconds) throws InterruptedException {
            try {
                return done.get(Math.max(0, seconds), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return null;
            } catch (ExecutionException e) {
                // complete() is the only writer and never completes exceptionally.
                throw new IllegalStateException(e.getCause());
            }
        }

        /**
         * Asks the session to stop. It ends as {@link NestedSessionRunner.Status#CANCELLED}, which
         * {@link #await} then reports.
         *
         * @return {@code false} if it had already ended
         */
        public synchronized boolean cancel() {
            if (done.isDone()) {
                return false;
            }
            cancelled = true;
            if (thread != null) {
                thread.interrupt();
            }
            return true;
        }

        /**
         * Called by the session's thread when it starts.
         *
         * @return {@code false} if the job was cancelled before it got going
         */
        synchronized boolean begin() {
            thread = Thread.currentThread();
            return !cancelled;
        }

        /**
         * Called by the session's thread when it ends, so that a late {@link #cancel} cannot interrupt a
         * pool thread that has moved on to other work.
         */
        synchronized void detach() {
            thread = null;
            // The runner re-sets the flag after a cancel; clear it so the pool thread is clean for its next task.
            if (Thread.interrupted()) {
                LOG.debug(LOG_PREFIX + id + " ended interrupted");
            }
        }

        void complete(@NotNull NestedSessionRunner.Result result) {
            finishedAtMs = System.currentTimeMillis();
            done.complete(result);
        }

        @Override
        public void onText(@NotNull String part) {
            synchronized (this) {
                text.append(part);
            }
            if (delegate != null) delegate.onText(part);
        }

        @Override
        public void onToolCall(@NotNull SessionUpdate.ToolCall call) {
            if (delegate != null) delegate.onToolCall(call);
        }

        @Override
        public void onToolCallUpdate(@NotNull SessionUpdate.ToolCallUpdate update) {
            if (delegate != null) delegate.onToolCallUpdate(update);
        }
    }
}
