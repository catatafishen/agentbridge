package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.DefaultToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

/**
 * Base class for IDE compatibility tests.
 * Registers plugin-core project services that are normally registered by the
 * full plugin but are absent when plugin-core is on the classpath as a plain JAR.
 */
public abstract class IdeCompatBaseTest extends BasePlatformTestCase {

    /**
     * JVM system property set by Gradle to indicate which IDE is under test (IU, CL, etc.).
     */
    protected static final String PLATFORM_TYPE = System.getProperty("testPlatformType", "IU");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // ToolLayerSettings: required by SearchSymbolsTool.showSearchFeedback() and
        // GetHighlightsTool.ensureDaemonAnalyzed(). DefaultToolLayerSettings allows
        // all tool permissions and returns null for agent-specific metadata.
        ServiceContainerUtil.registerServiceInstance(
            getProject(),
            ToolLayerSettings.class,
            new DefaultToolLayerSettings(getProject())
        );
        // DiagnosticFilterSettings: required by GetHighlightsTool.collectFileHighlights().
        // Registering a fresh instance uses its default state (all severities enabled).
        ServiceContainerUtil.registerServiceInstance(
            getProject(),
            DiagnosticFilterSettings.class,
            new DiagnosticFilterSettings()
        );
    }

    /**
     * Runs {@code action} on a pooled thread while pumping the EDT event queue.
     * Required for tools that dispatch back to the EDT via {@code EdtUtil.invokeLater}:
     * calling them directly from the EDT would block the queue and deadlock.
     */
    protected String executeSync(Callable<String> action) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(action.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 30_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("executeSync timed out after 30 seconds");
            }
            if (!future.isDone()) {
                //noinspection BusyWait
                Thread.sleep(10);
            }
        }
        return future.get();
    }
}
