package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks agent-created scratch files and deletes expired ones.
 *
 * <p>Persists tracked files across IDE restarts so that cleanup can happen
 * even after the IDE is restarted.</p>
 */
@Service(Service.Level.PROJECT)
@State(name = "AgentScratchTracker", storages = @Storage("ideAgentScratchTracker.xml"))
public final class AgentScratchTracker implements PersistentStateComponent<AgentScratchTracker.State>, Disposable {

    private static final Logger LOG = Logger.getInstance(AgentScratchTracker.class);
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    public static final class State {
        /**
         * Map of scratch file path → creation epoch millis.
         */
        public Map<String, Long> trackedFiles = new LinkedHashMap<>();
    }

    private final Project project;
    private State state = new State();

    public AgentScratchTracker(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull AgentScratchTracker getInstance(@NotNull Project project) {
        return project.getService(AgentScratchTracker.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = loaded;
    }

    /**
     * Registers a scratch file as agent-created.
     */
    public void trackScratchFile(@NotNull String path) {
        state.trackedFiles.put(path, System.currentTimeMillis());
    }

    /**
     * Returns paths whose creation timestamp is older than the cutoff.
     * Pure function — no IDE dependencies.
     */
    static List<String> findExpiredEntries(Map<String, Long> trackedFiles, long cutoffMillis) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : trackedFiles.entrySet()) {
            if (entry.getValue() < cutoffMillis) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    /**
     * Deletes tracked scratch files older than the configured retention period.
     * Files that no longer exist on disk are silently removed from tracking.
     */
    public void cleanupExpired() {
        int retentionHours = CleanupSettings.getInstance(project).getScratchRetentionHours();
        if (retentionHours <= 0) return;

        long cutoffMillis = System.currentTimeMillis() - (retentionHours * MILLIS_PER_HOUR);
        List<String> toRemove = findExpiredEntries(state.trackedFiles, cutoffMillis);

        for (String path : toRemove) {
            state.trackedFiles.remove(path);
            deleteScratchFile(path);
        }
    }

    private void deleteScratchFile(String path) {
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
            if (file == null || !file.exists()) return;
            try {
                WriteAction.compute(() -> {
                    file.delete(this);
                    return null;
                });
                LOG.info("Deleted expired scratch file: " + path);
            } catch (IOException e) {
                LOG.warn("Failed to delete scratch file: " + path, e);
            }
        });
    }

    /**
     * Returns the number of currently tracked scratch files.
     */
    public int getTrackedCount() {
        return state.trackedFiles.size();
    }

    @Override
    public void dispose() {
        // State is persisted by the platform; nothing to clean up
    }
}
