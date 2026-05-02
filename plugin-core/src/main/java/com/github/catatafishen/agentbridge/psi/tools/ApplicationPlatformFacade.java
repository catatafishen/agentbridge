package com.github.catatafishen.agentbridge.psi.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.ThrowableComputable;
import java.util.function.Supplier;

/**
 * Production implementation of {@link PlatformFacade} that delegates every
 * call to the IntelliJ Platform APIs.
 *
 * <p>Use {@link PlatformFacade#application()} to obtain the singleton rather
 * than constructing directly.
 *
 * @see PlatformFacade
 */
// S6548: Intentional stateless singleton — testability seam for PlatformFacade interface.
// Only one IntelliJ Application per process; one facade for it is semantically correct.
public final class ApplicationPlatformFacade implements PlatformFacade { // NOSONAR java:S6548

    static final ApplicationPlatformFacade INSTANCE = new ApplicationPlatformFacade();

    private ApplicationPlatformFacade() {}

    @Override
    public void invokeLater(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }

    @Override
    public <T> T runReadAction(Supplier<T> computation) {
        return ReadAction.compute((ThrowableComputable<T, RuntimeException>) computation::get);
    }

    @Override
    public void invokeAndWait(Runnable action) {
        ApplicationManager.getApplication().invokeAndWait(action);
    }

    @Override
    public void invokeAndWaitWriteAction(Runnable action) {
        ApplicationManager.getApplication().invokeAndWait(
            () -> ApplicationManager.getApplication().runWriteAction(action)
        );
    }
}
