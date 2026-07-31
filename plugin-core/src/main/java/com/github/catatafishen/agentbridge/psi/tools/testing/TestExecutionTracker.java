package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Correlates a run launched by {@code run_tests} with the platform execution event that carries its
 * {@link ProcessHandler}, so the tool can wait for the tests instead of returning as soon as they
 * are launched.
 *
 * <p>Matching on the run configuration <em>name</em> alone is unreliable: {@code RunManager} may
 * rename a temporary configuration when one of the same name already exists, so re-running the same
 * target produced an execution event the tool did not recognise — it then gave up waiting and
 * reported "could not capture process handle" while the tests were still running. The launched
 * {@link RunProfile} is therefore compared by identity, which cannot collide, with the name kept as
 * a fallback for launches that never publish their profile.</p>
 */
final class TestExecutionTracker {

    private final String configName;
    private final CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
    private final AtomicReference<RunProfile> expectedProfile = new AtomicReference<>();
    private final AtomicReference<Runnable> disconnect = new AtomicReference<>(() -> {
    });

    /**
     * Subscribes to execution events immediately, so that a run which starts before the caller
     * begins waiting is still observed.
     */
    TestExecutionTracker(@NotNull Project project, @NotNull String configName) {
        this.configName = configName;
        disconnect.set(PlatformApiCompat.subscribeExecutionListener(project, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId,
                                       @NotNull ExecutionEnvironment env,
                                       @NotNull ProcessHandler handler) {
                if (!matches(env)) return;
                handlerFuture.complete(handler);
                disconnect();
            }

            @Override
            public void processNotStarted(@NotNull String executorId,
                                          @NotNull ExecutionEnvironment env) {
                if (!matches(env)) return;
                handlerFuture.complete(null);
                disconnect();
            }
        }));
    }

    /**
     * Publishes the profile that was actually launched so its execution event can be matched by
     * identity. Call this before starting the run — afterwards the event may already have fired.
     */
    void expect(@Nullable RunProfile profile) {
        expectedProfile.set(profile);
    }

    boolean matches(@NotNull ExecutionEnvironment env) {
        RunnerAndConfigurationSettings settings = env.getRunnerAndConfigurationSettings();
        return matchesLaunch(expectedProfile.get(), env.getRunProfile(), configName,
            settings == null ? null : settings.getName());
    }

    /**
     * Decides whether an execution event belongs to the run this tracker launched.
     *
     * <p>Identity of the launched profile is authoritative. The configuration name is only a
     * fallback for launches that never published a profile, because {@code RunManager} may rename
     * a temporary configuration when one of the same name already exists — the original cause of
     * runs being missed and reported as "could not capture process handle".</p>
     */
    static boolean matchesLaunch(@Nullable RunProfile expected,
                                 @Nullable RunProfile actual,
                                 @NotNull String configName,
                                 @Nullable String actualConfigName) {
        if (expected != null && actual == expected) return true;
        return configName.equals(actualConfigName);
    }

    /**
     * Waits for the launched run to report its process handle.
     *
     * @param timeoutSeconds how long to wait; callers derive this from the caller's own timeout so
     *                       a slow starter (a cold Gradle daemon, for instance) is not mistaken for
     *                       a run that will never report
     * @return the handle, or {@code null} if the platform reported that the process never started
     * @throws TimeoutException     if no matching execution event arrived in time
     * @throws InterruptedException if the wait was interrupted
     */
    @Nullable ProcessHandler awaitHandler(long timeoutSeconds)
        throws TimeoutException, InterruptedException {
        try {
            return handlerFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Execution tracker for " + configName + " failed", e);
        }
    }

    void disconnect() {
        disconnect.get().run();
    }
}
