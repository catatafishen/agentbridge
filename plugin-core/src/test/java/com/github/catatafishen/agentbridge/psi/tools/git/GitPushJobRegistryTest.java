package com.github.catatafishen.agentbridge.psi.tools.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitPushJobRegistryTest {

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
        GitPushJobRegistry registry = registry(executor, 10);

        GitPushJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () -> {
            taskStarted.countDown();
            releaseTask.await();
            return GitPushJobRegistry.JobResult.success("done");
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
        assertEquals(GitPushJobRegistry.JobState.RUNNING, job.state());
        assertTrue(registry.describe(job.id()).contains("Status: running"));
        releaseTask.countDown();
        awaitStatus(registry, job.id(), "succeeded");
    }

    @Test
    void explicitFailureRemainsFailedWhenOutputStartsWithFetchNote() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        GitPushJobRegistry registry = registry(executor, 10);

        GitPushJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD", () ->
            GitPushJobRegistry.JobResult.failure("Fetched origin.\nError: pre-push hook failed")
        );

        String status = awaitStatus(registry, job.id(), "failed");
        assertTrue(status.contains("Fetched origin."));
        assertTrue(status.contains("Error: pre-push hook failed"));
    }

    @Test
    void completedJobsAreBoundedWhileRecentJobsRemainQueryable() {
        AtomicInteger ids = new AtomicInteger();
        GitPushJobRegistry registry = new GitPushJobRegistry(
            Runnable::run,
            Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
            2,
            () -> "job-" + ids.incrementAndGet()
        );

        GitPushJobRegistry.JobRecord first = registry.start("/repo", "push 1",
            () -> GitPushJobRegistry.JobResult.success("one"));
        GitPushJobRegistry.JobRecord second = registry.start("/repo", "push 2",
            () -> GitPushJobRegistry.JobResult.success("two"));
        GitPushJobRegistry.JobRecord third = registry.start("/repo", "push 3",
            () -> GitPushJobRegistry.JobResult.success("three"));

        assertTrue(registry.describe(first.id()).contains("not found"));
        assertTrue(registry.describe(second.id()).contains("Status: succeeded"));
        assertTrue(registry.describe(third.id()).contains("Status: succeeded"));
    }

    @Test
    void blankIdDescribesLatestJob() {
        GitPushJobRegistry registry = registry(Runnable::run, 10);
        GitPushJobRegistry.JobRecord job = registry.start("/repo", "git push origin HEAD",
            () -> GitPushJobRegistry.JobResult.success("done"));

        assertTrue(registry.describe(" ").contains("Job: " + job.id()));
    }

    private static GitPushJobRegistry registry(Executor executor, int maxCompletedJobs) {
        AtomicInteger ids = new AtomicInteger();
        return new GitPushJobRegistry(
            executor,
            Clock.systemUTC(),
            maxCompletedJobs,
            () -> "job-" + ids.incrementAndGet()
        );
    }

    private static String awaitStatus(
        GitPushJobRegistry registry,
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
}
