package com.github.catatafishen.agentbridge.psi.tools.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GitJobRegistryTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void startReturnsRunningJobWithoutWaitingForTask() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        GitJobRegistry registry = registry(executor, 10);

        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () -> {
            taskStarted.countDown();
            releaseTask.await();
            return GitJobRegistry.JobResult.success("done");
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
        assertEquals(GitJobRegistry.JobState.RUNNING, job.state());
        assertTrue(registry.describe(job.id()).contains("Status: running"));
        releaseTask.countDown();
        awaitStatus(registry, job.id(), "succeeded");
    }

    @Test
    void explicitFailureRemainsFailedWhenOutputStartsWithFetchNote() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        GitJobRegistry registry = registry(executor, 10);

        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () ->
            GitJobRegistry.JobResult.failure("Fetched origin.\nError: pre-push hook failed")
        );

        String status = awaitStatus(registry, job.id(), "failed");
        assertTrue(status.contains("Fetched origin."));
        assertTrue(status.contains("Error: pre-push hook failed"));
    }

    @Test
    void completedJobsAreBoundedWhileRecentJobsRemainQueryable() {
        AtomicInteger ids = new AtomicInteger();
        GitJobRegistry registry = new GitJobRegistry(
            Runnable::run,
            Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
            2,
            () -> "job-" + ids.incrementAndGet()
        );

        GitJobRegistry.JobRecord first = registry.start("/repo", "push 1",
            () -> GitJobRegistry.JobResult.success("one"));
        GitJobRegistry.JobRecord second = registry.start("/repo", "push 2",
            () -> GitJobRegistry.JobResult.success("two"));
        GitJobRegistry.JobRecord third = registry.start("/repo", "push 3",
            () -> GitJobRegistry.JobResult.success("three"));

        assertTrue(registry.describe(first.id()).contains("not found"));
        assertTrue(registry.describe(second.id()).contains("Status: succeeded"));
        assertTrue(registry.describe(third.id()).contains("Status: succeeded"));
    }

    @Test
    void blankIdDescribesLatestJob() {
        GitJobRegistry registry = registry(Runnable::run, 10);
        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD",
            () -> GitJobRegistry.JobResult.success("done"));

        assertTrue(registry.describe(" ").contains("Job: " + job.id()));
    }

    @Test
    void emptyRegistryReportsThatNoBackgroundJobsExist() {
        GitJobRegistry registry = registry(Runnable::run, 10);

        assertEquals("No background Git jobs.", registry.describe(null));
    }

    @Test
    void rejectsNonPositiveCompletedJobLimit() {
        assertThrows(IllegalArgumentException.class, () -> registry(Runnable::run, 0));
    }

    @Test
    void nullTaskResultIsReportedAsFailure() {
        GitJobRegistry registry = registry(Runnable::run, 10);
        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () -> null);

        String status = registry.describe(job.id());

        assertTrue(status.contains("Status: failed"));
        assertTrue(status.contains("returned no result"));
    }

    @Test
    void unexpectedTaskExceptionIsReportedAsFailure() {
        GitJobRegistry registry = registry(Runnable::run, 10);
        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () -> {
            throw new IllegalStateException("boom");
        });

        String status = registry.describe(job.id());

        assertTrue(status.contains("Status: failed"));
        assertTrue(status.contains("IllegalStateException: boom"));
    }

    @Test
    void interruptedTaskIsReportedAsFailure() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        GitJobRegistry registry = registry(executor, 10);
        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () -> {
            throw new InterruptedException("stop");
        });

        String status = awaitStatus(registry, job.id(), "failed");

        assertTrue(status.contains("was interrupted"));
    }

    @Test
    void longTaskOutputIsTruncated() {
        GitJobRegistry registry = registry(Runnable::run, 10);
        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () ->
            GitJobRegistry.JobResult.success("x".repeat(40_001))
        );

        String status = registry.describe(job.id());

        assertTrue(status.contains("truncated Git job output after 40000 chars"));
    }

    @Test
    void runningJobFormatsElapsedMinutesAndSeconds() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T00:00:00Z"));
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        GitJobRegistry registry = new GitJobRegistry(executor, clock, 10, () -> "job-1");
        GitJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () -> {
            taskStarted.countDown();
            releaseTask.await();
            return GitJobRegistry.JobResult.success("done");
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
        clock.set(Instant.parse("2026-07-12T00:01:05Z"));

        assertTrue(registry.describe(job.id()).contains("Elapsed: 1m 5s"));
        releaseTask.countDown();
        awaitStatus(registry, job.id(), "succeeded");
    }

    @Test
    void disposeRemovesRunningJobs() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        GitJobRegistry registry = registry(executor, 10);
        registry.start("/repo", "git push origin HEAD", () -> {
            taskStarted.countDown();
            releaseTask.await();
            return GitJobRegistry.JobResult.success("done");
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
        registry.dispose();

        String status = registry.describe(null);
        assertTrue(status.contains("not found"));
        assertTrue(status.contains("No background Git jobs."));
        releaseTask.countDown();
    }

    private static GitJobRegistry registry(Executor executor, int maxCompletedJobs) {
        AtomicInteger ids = new AtomicInteger();
        return new GitJobRegistry(
            executor,
            Clock.systemUTC(),
            maxCompletedJobs,
            () -> "job-" + ids.incrementAndGet()
        );
    }

    private static String awaitStatus(
        GitJobRegistry registry,
        String jobId,
        String expectedStatus
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String status;
        do {
            status = registry.describe(jobId);
            if (status.contains("Status: " + expectedStatus)) {
                return status;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for status " + expectedStatus + ":\n" + status);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void set(Instant instant) {
            this.instant.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
