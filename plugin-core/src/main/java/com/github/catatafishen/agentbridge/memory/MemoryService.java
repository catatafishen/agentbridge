package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Project-level lifecycle service for semantic memory.
 * Initializes and manages the MemoryStore, EmbeddingService, and WriteAheadLog.
 *
 * <p>Only active when {@link MemorySettings#isEnabled()} is true.
 * Lazy-initializes on first access.
 */
@Service(Service.Level.PROJECT)
public final class MemoryService implements Disposable {

    private static final Logger LOG = Logger.getInstance(MemoryService.class);

    private static final String MEMORY_DIR = "memory";
    private static final String LUCENE_INDEX_DIR = "lucene-index";
    private static final String WAL_DIR = "wal";

    private final Project project;

    private volatile MemoryStore store;
    private volatile EmbeddingService embeddingService;
    private volatile WriteAheadLog wal;
    private volatile boolean initialized;
    private final Object initLock = new Object();

    public MemoryService(@NotNull Project project) {
        this.project = project;
    }

    public static MemoryService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, MemoryService.class);
    }

    /**
     * Get the memory store. Initializes if needed.
     * Returns null if memory is disabled or initialization fails.
     */
    public @Nullable MemoryStore getStore() {
        if (!MemorySettings.getInstance(project).isEnabled()) return null;
        ensureInitialized();
        return store;
    }

    /**
     * Get the embedding service. Initializes if needed.
     * Returns null if memory is disabled or initialization fails.
     */
    public @Nullable EmbeddingService getEmbeddingService() {
        if (!MemorySettings.getInstance(project).isEnabled()) return null;
        ensureInitialized();
        return embeddingService;
    }

    /**
     * Get the write-ahead log. Initializes if needed.
     * Returns null if memory is disabled or initialization fails.
     */
    public @Nullable WriteAheadLog getWriteAheadLog() {
        if (!MemorySettings.getInstance(project).isEnabled()) return null;
        ensureInitialized();
        return wal;
    }

    /**
     * Get the effective palace wing name (from settings or auto-detected from project name).
     */
    public @NotNull String getEffectiveWing() {
        String wing = MemorySettings.getInstance(project).getPalaceWing();
        if (wing == null || wing.isEmpty()) {
            wing = project.getName()
                .toLowerCase()
                .replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("-+", "-");
        }
        return wing;
    }

    /**
     * Check if memory is enabled and the system is initialized.
     */
    public boolean isActive() {
        return MemorySettings.getInstance(project).isEnabled() && initialized;
    }

    private void ensureInitialized() {
        if (initialized) return;
        synchronized (initLock) {
            if (initialized) return;
            try {
                Path memoryDir = getMemoryBasePath();

                // Initialize WAL
                wal = new WriteAheadLog(memoryDir.resolve(WAL_DIR));
                wal.initialize();

                // Initialize Lucene store
                store = new MemoryStore(memoryDir.resolve(LUCENE_INDEX_DIR), wal);
                store.initialize();
                Disposer.register(this, store);

                // Initialize embedding service
                embeddingService = new EmbeddingService(project);
                Disposer.register(this, embeddingService);

                initialized = true;
                LOG.info("MemoryService initialized for project: " + project.getName());
            } catch (IOException e) {
                LOG.error("Failed to initialize MemoryService", e);
            }
        }
    }

    private @NotNull Path getMemoryBasePath() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            basePath = System.getProperty("user.home");
        }
        return Path.of(basePath, ".agent-work", MEMORY_DIR);
    }

    @Override
    public void dispose() {
        initialized = false;
        // Children (store, embeddingService) are disposed via Disposer tree
    }
}
