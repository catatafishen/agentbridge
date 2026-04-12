package com.github.catatafishen.agentbridge.psi.tools;

import java.util.function.Supplier;

/**
 * Thin abstraction over IntelliJ Platform threading primitives.
 *
 * <p>All tool classes that schedule work on the EDT, acquire read locks, or
 * perform synchronous write-actions should do so via this interface rather than
 * calling {@code ApplicationManager.getApplication()} directly.  The default
 * production implementation delegates to the real IntelliJ Platform; the test
 * implementation runs everything synchronously on the calling thread so that
 * pure-unit tests can drive tool logic without a running IDE.
 *
 * <p>Inject via the package-private {@code Tool(Project, PlatformFacade)} constructor
 * when writing unit tests. Production code should use {@link ApplicationPlatformFacade}.
 *
 * @see ApplicationPlatformFacade
 */
public interface PlatformFacade {

    /**
     * Returns the default production instance backed by the IntelliJ Platform.
     */
    static PlatformFacade application() {
        return ApplicationPlatformFacade.INSTANCE;
    }

    /**
     * Schedules {@code action} to run on the Event Dispatch Thread.
     *
     * <p>In production this calls
     * {@code ApplicationManager.getApplication().invokeLater(action)}.
     * In tests the action is run synchronously on the calling thread.
     */
    void invokeLater(Runnable action);

    /**
     * Runs {@code computation} under IntelliJ's read lock and returns the result.
     *
     * <p>In production this calls {@code ReadAction.compute(computation::get)}.
     * In tests the supplier is called directly without acquiring any lock.
     *
     * @param <T> the result type
     * @param computation the computation to run inside the read lock
     * @return the value returned by {@code computation}
     */
    <T> T runReadAction(Supplier<T> computation);

    /**
     * Runs {@code action} synchronously on the EDT, blocking the calling thread
     * until the action completes.
     *
     * <p>In production this calls {@code ApplicationManager.getApplication().invokeAndWait(action)}.
     * In tests the action is run synchronously on the calling thread.
     *
     * <p>Must not be called from the EDT (would deadlock in production).
     */
    void invokeAndWait(Runnable action);

    /**
     * Runs {@code action} inside a write action, blocking the calling thread until
     * the action has been executed on the EDT under the write lock.
     *
     * <p>In production this calls
     * {@code ApplicationManager.getApplication().invokeAndWait(() ->
     *     ApplicationManager.getApplication().runWriteAction(action))}.
     * In tests the action is run synchronously on the calling thread.
     *
     * <p>Must not be called from the EDT (would deadlock in production).
     */
    void invokeAndWaitWriteAction(Runnable action);
}
