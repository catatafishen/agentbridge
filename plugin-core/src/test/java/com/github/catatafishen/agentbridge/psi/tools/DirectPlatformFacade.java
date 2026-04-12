package com.github.catatafishen.agentbridge.psi.tools;

import java.util.function.Supplier;

/**
 * Test implementation of {@link PlatformFacade} that runs every operation
 * synchronously on the calling thread, with no locking.
 *
 * <p>Use this in unit tests to avoid spinning up the IntelliJ Platform:
 * <pre>
 *     MyTool tool = new MyTool(project, new DirectPlatformFacade());
 *     String result = tool.execute(args);
 * </pre>
 *
 * <p>All four methods delegate directly to the provided {@link Runnable} or
 * {@link Supplier} — no EDT scheduling, no read/write locks are acquired.
 *
 * @see PlatformFacade
 */
public final class DirectPlatformFacade implements PlatformFacade {

    @Override
    public void invokeLater(Runnable action) {
        action.run();
    }

    @Override
    public <T> T runReadAction(Supplier<T> computation) {
        return computation.get();
    }

    @Override
    public void invokeAndWait(Runnable action) {
        action.run();
    }

    @Override
    public void invokeAndWaitWriteAction(Runnable action) {
        action.run();
    }
}
