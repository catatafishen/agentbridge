package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.DefaultToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

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

    // -------------------------------------------------------------------------
    // Language IDs for createInMemoryPsiFile — expandable as new IDEs are added
    // -------------------------------------------------------------------------

    /**
     * Java — available in all IntelliJ-based IDE builds.
     */
    protected static final String LANGUAGE_JAVA = "JAVA";

    /**
     * C/C++ via the classic CIDR engine ({@code com.intellij.cidr.lang}).
     * Available in CLion when {@code bundledPlugin("com.intellij.cidr.lang")} is declared.
     * Note: this is the classic-engine language ID ("ObjectiveC") — CLion Nova's Radler
     * backend uses a different internal language registration not accessible headlessly.
     */
    protected static final String LANGUAGE_CPP = "ObjectiveC";

    /**
     * Creates an in-memory {@link PsiFile} for the given language by calling
     * {@link PsiFileFactory#createFileFromText} directly with the {@link Language} object.
     *
     * <p>This bypasses the {@code FileTypeManager} extension-point lookup that maps file
     * extensions to language parsers. As a result, the file is parsed by the correct language
     * even when file-type registration fails in headless test environments — which is the case
     * for C++ in CLion CI where {@code bundledPlugin("com.intellij.cidr.lang")} loads the JARs
     * but does not register the {@code .cpp} extension.</p>
     *
     * <p>Returns {@code null} when the language ID is not registered (e.g. when the required
     * language plugin is not loaded). Tests should call
     * {@code Assume.assumeTrue("reason", result != null)} to skip gracefully rather than fail.</p>
     *
     * @param filename   file name with extension (used by the parser for language hints)
     * @param languageId IntelliJ language ID — use the {@code LANGUAGE_*} constants
     * @param content    source text for the file
     */
    @Nullable
    protected PsiFile createInMemoryPsiFile(String filename, String languageId, String content) {
        Language language = Language.findLanguageByID(languageId);
        if (language == null || language == PlainTextLanguage.INSTANCE) return null;
        return PsiFileFactory.getInstance(getProject())
            .createFileFromText(filename, language, content);
    }

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
