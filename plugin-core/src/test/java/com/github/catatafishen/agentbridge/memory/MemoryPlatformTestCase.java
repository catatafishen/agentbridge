package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.embedding.TestEmbeddingFactory;
import com.github.catatafishen.agentbridge.memory.kg.KnowledgeGraph;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base class for platform tests that need a real IntelliJ project with memory services.
 *
 * <p>Extends {@link BasePlatformTestCase} (JUnit 3 style: test methods named {@code testXxx()}).
 * Provides utility methods for:
 * <ul>
 *   <li>Enabling/disabling memory in settings</li>
 *   <li>Replacing the {@link MemoryService} with a test instance backed by controlled components</li>
 *   <li>Creating {@link MemoryStore}, {@link WriteAheadLog}, and {@link EmbeddingService} for tests</li>
 * </ul>
 *
 * <p>All replaced services are automatically restored after each test via
 * {@code getTestRootDisposable()}.
 *
 * <p><b>Why JUnit 3 style?</b> {@code BasePlatformTestCase} extends {@code junit.framework.TestCase}.
 * The Vintage engine bridges these to JUnit Platform. Test methods must be public void and
 * start with "test" — no {@code @Test} annotation.
 */
public abstract class MemoryPlatformTestCase extends BasePlatformTestCase {

    private Path tempMemoryDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempMemoryDir = Files.createTempDirectory("memory-platform-test");
        // Reset settings to defaults so test state doesn't leak between tests
        memorySettings().loadState(new MemorySettings.State());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            deleteRecursively(tempMemoryDir);
        } finally {
            super.tearDown();
        }
    }

    /**
     * Enable memory in the project's settings.
     */
    protected void enableMemory() {
        memorySettings().setEnabled(true);
    }

    /**
     * Disable memory in the project's settings.
     */
    protected void disableMemory() {
        memorySettings().setEnabled(false);
    }

    /**
     * Get the project's {@link MemorySettings}.
     */
    protected @NotNull MemorySettings memorySettings() {
        return MemorySettings.getInstance(getProject());
    }

    /**
     * Replace the project's {@link MemoryService} with one backed by the given components.
     * The replacement is scoped to the test lifecycle (auto-restored on tearDown).
     */
    protected void replaceMemoryService(@Nullable MemoryStore store,
                                        @Nullable EmbeddingService embeddingService,
                                        @Nullable WriteAheadLog wal,
                                        @Nullable KnowledgeGraph knowledgeGraph) {
        MemoryService testService = new MemoryService(
            getProject(), store, embeddingService, wal, knowledgeGraph);
        ServiceContainerUtil.replaceService(
            (ComponentManager) getProject(), MemoryService.class, testService, getTestRootDisposable());
    }

    /**
     * Replace the project's {@link MemoryService} with one backed by a real
     * {@link MemoryStore} and a fake {@link EmbeddingService}.
     *
     * @return the real MemoryStore used (for assertions)
     */
    protected MemoryStore replaceMemoryServiceWithTestComponents() throws IOException {
        Path indexDir = tempMemoryDir.resolve("lucene-index");
        Path walDir = tempMemoryDir.resolve("wal");
        WriteAheadLog wal = new WriteAheadLog(walDir);
        wal.initialize();
        MemoryStore store = new MemoryStore(indexDir, wal);
        store.initialize();
        float[] zeroVector = new float[EmbeddingService.EMBEDDING_DIM];
        EmbeddingService embedding = TestEmbeddingFactory.constant(tempMemoryDir, zeroVector);
        replaceMemoryService(store, embedding, wal, null);
        return store;
    }

    /**
     * Replace the given service in the project's service container.
     * Scoped to this test's lifecycle.
     */
    protected <T> void replaceProjectService(Class<T> serviceClass, T instance) {
        ServiceContainerUtil.replaceService(
            (ComponentManager) getProject(), serviceClass, instance, getTestRootDisposable());
    }

    /**
     * Get the temp directory for memory test artifacts.
     */
    protected @NotNull Path getTempMemoryDir() {
        return tempMemoryDir;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
