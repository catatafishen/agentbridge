package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service(Service.Level.PROJECT)
public final class GitPushJobRegistry implements Disposable {

    private static final int MAX_RESULT_CHARS = 40_000;
    private static final int MAX_COMPLETED_JOBS = 50;
    private static final int RECENT_LIMIT = 10;

    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestJobId = new AtomicReference<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Executor executor;
    private final Clock clock;
    private final int maxCompletedJobs;
    private final Supplier<String> idSupplier;

    @SuppressWarnings("unused") // IntelliJ service container
    public GitPushJobRegistry(@NotNull Project project) {
        this(
            AppExecutorUtil.getAppExecutorService(),
            Clock.systemUTC(),
            MAX_COMPLETED_JOBS,
            () -> "git-push-" + UUID.randomUUID().toString().substring(0, 8)
        );
    }

    GitPushJobRegistry(
        @NotNull Executor executor,
        @NotNull Clock clock,
        int maxCompletedJobs,
        @NotNull Supplier<String> idSupplier
    ) {
        if (maxCompletedJobs < 1) {
            throw new IllegalArgumentException("maxCompletedJobs must be positive");
        }
        this.executor = executor;
        this.clock = clock;
        this.maxCompletedJobs = maxCompletedJobs;
        this.idSupplier = idSupplier;
    }

    public static @NotNull GitPushJobRegistry getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, GitPushJobRegistry.class);
    }

    public @NotNull JobRecord start(
        @NotNull String root,
        @NotNull String displayCommand,
        @NotNull Callable<JobResult> task
    ) {
        String id = idSupplier.get();
        JobRecord job = new JobRecord(
            id,
            root,
            displayCommand,
            clock.instant(),
            sequence.incrementAndGet()
        );
        jobs.put(id, job);
        latestJobId.set(id);
        job.future = CompletableFuture.supplyAsync(() -> runTask(job, task), executor);
        return job;
    }

    public @NotNull String describe(@Nullable String jobId) {
        String resolvedId = jobId != null && !jobId.isBlank() ? jobId : latestJobId.get();
        if (resolvedId == null) return listRecent();

        JobRecord job = jobs.get(resolvedId);
        if (job == null) {
            return "Error: git_push job '" + resolvedId + "' not found.\n\n" + listRecent();
        }
        return formatJob(job);
    }

    @Override
    public void dispose() {
        for (JobRecord job : jobs.values()) {
            CompletableFuture<JobResult> future = job.future;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        jobs.clear();
    }

    private @NotNull JobResult runTask(@NotNull JobRecord job, @NotNull Callable<JobResult> task) {
        JobResult result;
        try {
            result = task.call();
            if (result == null) {
                result = JobResult.failure("Error: background git_push job returned no result");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = JobResult.failure("Error: background git_push job was interrupted");
        } catch (Exception e) {
            result = JobResult.failure(
                "Error: background git_push job failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage()
            );
        }
        complete(job, result);
        return result;
    }

    private void complete(@NotNull JobRecord job, @NotNull JobResult result) {
        job.result = truncate(result.output());
        job.completedAt = clock.instant();
        job.state.set(result.success() ? JobState.SUCCEEDED : JobState.FAILED);
        pruneCompletedJobs();
    }

    private void pruneCompletedJobs() {
        long completedCount = jobs.values().stream()
            .filter(JobRecord::isCompleted)
            .count();
        long excess = completedCount - maxCompletedJobs;
        if (excess <= 0) return;

        jobs.values().stream()
            .filter(JobRecord::isCompleted)
            .sorted(Comparator.comparingLong(JobRecord::sequence))
            .limit(excess)
            .forEach(job -> jobs.remove(job.id(), job));
    }

    private @NotNull String listRecent() {
        if (jobs.isEmpty()) return "No background git_push jobs.";

        StringBuilder sb = new StringBuilder("Recent background git_push jobs:\n");
        jobs.values().stream()
            .sorted(Comparator.comparingLong(JobRecord::sequence).reversed())
            .limit(RECENT_LIMIT)
            .forEach(job -> sb.append("- ")
                .append(job.id())
                .append(" [")
                .append(job.state().wireValue())
                .append("] ")
                .append(job.displayCommand())
                .append('\n'));
        return sb.toString().stripTrailing();
    }

    private @NotNull String formatJob(@NotNull JobRecord job) {
        Instant completedAt = job.completedAt;
        Instant end = completedAt != null ? completedAt : clock.instant();

        StringBuilder sb = new StringBuilder();
        sb.append("Job: ").append(job.id()).append('\n');
        sb.append("Status: ").append(job.state().wireValue()).append('\n');
        sb.append("Repository: ").append(job.root()).append('\n');
        sb.append("Command: ").append(job.displayCommand()).append('\n');
        sb.append("Started: ").append(job.startedAt()).append('\n');
        if (completedAt != null) {
            sb.append("Completed: ").append(completedAt).append('\n');
        }
        sb.append("Elapsed: ").append(formatDuration(Duration.between(job.startedAt(), end))).append('\n');

        if (job.result != null) {
            sb.append("\n--- Result ---\n").append(job.result.stripTrailing());
        } else {
            sb.append("\nResult: still running; call git_push_status again with job_id='")
                .append(job.id())
                .append("'.");
        }
        return sb.toString();
    }

    private static @NotNull String truncate(@NotNull String result) {
        if (result.length() <= MAX_RESULT_CHARS) return result;
        return result.substring(0, MAX_RESULT_CHARS)
            + "\n... [truncated git_push job output after " + MAX_RESULT_CHARS + " chars]";
    }

    private static @NotNull String formatDuration(@NotNull Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return minutes + "m " + remainder + "s";
    }

    public record JobResult(boolean success, @NotNull String output) {

        public static @NotNull JobResult success(@NotNull String output) {
            return new JobResult(true, output);
        }

        public static @NotNull JobResult failure(@NotNull String output) {
            return new JobResult(false, output);
        }
    }

    public enum JobState {
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        FAILED("failed");

        private final String wireValue;

        JobState(@NotNull String wireValue) {
            this.wireValue = wireValue;
        }

        public @NotNull String wireValue() {
            return wireValue;
        }
    }

    public static final class JobRecord {
        private final String id;
        private final String root;
        private final String displayCommand;
        private final Instant startedAt;
        private final long sequence;
        private final AtomicReference<JobState> state = new AtomicReference<>(JobState.RUNNING);
        private volatile CompletableFuture<JobResult> future;
        private volatile Instant completedAt;
        private volatile String result;

        private JobRecord(
            @NotNull String id,
            @NotNull String root,
            @NotNull String displayCommand,
            @NotNull Instant startedAt,
            long sequence
        ) {
            this.id = id;
            this.root = root;
            this.displayCommand = displayCommand;
            this.startedAt = startedAt;
            this.sequence = sequence;
        }

        public @NotNull String id() {
            return id;
        }

        public @NotNull String root() {
            return root;
        }

        public @NotNull String displayCommand() {
            return displayCommand;
        }

        public @NotNull Instant startedAt() {
            return startedAt;
        }

        public @NotNull JobState state() {
            return state.get();
        }

        private long sequence() {
            return sequence;
        }

        private boolean isCompleted() {
            return state.get() != JobState.RUNNING;
        }
    }
}
