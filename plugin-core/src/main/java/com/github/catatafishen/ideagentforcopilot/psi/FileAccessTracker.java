package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks which files the agent has read/written during the current session.
 * Used by {@link AgentFileDecorator} to annotate files in the Project View.
 * <p>
 * Background tints persist until {@link #clear(Project)} (end of turn).
 * Active labels ("Agent reading" / "Agent editing") auto-expire after
 * {@link #LABEL_DURATION_MS} from the <em>last</em> access to that file.
 * <p>
 * A per-file generation counter ensures that only the most recently scheduled
 * expiry task actually removes the label — earlier tasks become no-ops.
 */
final class FileAccessTracker {

    enum AccessType {
        READ, WRITE, READ_WRITE
    }

    static final long LABEL_DURATION_MS = 4000;

    private static final Map<String, AccessType> accessMap = new ConcurrentHashMap<>();
    private static final Map<String, String> activeLabels = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> labelGenerations = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-file-label-expiry");
                t.setDaemon(true);
                return t;
            });

    private FileAccessTracker() {
    }

    static void recordRead(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        String key = vf.getPath();
        accessMap.merge(key, AccessType.READ, FileAccessTracker::merge);
        activeLabels.put(key, "Agent reading");
        long gen = generationFor(key).incrementAndGet();
        scheduleProjectViewRefresh(project);
        scheduleLabelExpiry(project, key, gen);
    }

    static void recordWrite(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        String key = vf.getPath();
        accessMap.merge(key, AccessType.WRITE, FileAccessTracker::merge);
        activeLabels.put(key, "Agent editing");
        long gen = generationFor(key).incrementAndGet();
        scheduleProjectViewRefresh(project);
        scheduleLabelExpiry(project, key, gen);
    }

    /**
     * Returns the cumulative access type for the given file, or null if untouched.
     */
    static AccessType getAccess(VirtualFile vf) {
        return vf != null ? accessMap.get(vf.getPath()) : null;
    }

    /**
     * Returns the active label for the given file (e.g. "Agent reading"),
     * or null if the label has expired.
     */
    static String getActiveLabel(VirtualFile vf) {
        return vf != null ? activeLabels.get(vf.getPath()) : null;
    }

    static void clear(Project project) {
        accessMap.clear();
        activeLabels.clear();
        labelGenerations.clear();
        scheduleProjectViewRefresh(project);
    }

    private static AtomicLong generationFor(String key) {
        return labelGenerations.computeIfAbsent(key, k -> new AtomicLong());
    }

    private static AccessType merge(AccessType existing, AccessType incoming) {
        if (existing == AccessType.READ_WRITE || incoming == AccessType.READ_WRITE) {
            return AccessType.READ_WRITE;
        }
        if (existing != incoming) {
            return AccessType.READ_WRITE;
        }
        return existing;
    }

    private static void scheduleLabelExpiry(Project project, String key, long generation) {
        scheduler.schedule(() -> {
            AtomicLong current = labelGenerations.get(key);
            if (current == null || current.get() != generation) {
                return; // a newer access superseded this one
            }
            activeLabels.remove(key);
            scheduleProjectViewRefresh(project);
        }, LABEL_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Debounced project view refresh.
     * <p>
     * Uses {@code updateFromRoot(true)} which reliably triggers decorator
     * re-evaluation for all visible nodes (unlike {@code updateFrom()} which
     * only updates the currently-selected node). The flag ensures rapid bursts
     * of file access events coalesce into a single tree rebuild. IntelliJ's
     * {@code StructureTreeModel} further batches multiple {@code updateFromRoot}
     * calls internally.
     */
    private static final AtomicBoolean refreshPending = new AtomicBoolean(false);

    private static void scheduleProjectViewRefresh(Project project) {
        if (refreshPending.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater(() -> {
                refreshPending.set(false);
                if (project.isDisposed()) return;
                try {
                    var pane = ProjectView.getInstance(project).getCurrentProjectViewPane();
                    if (pane != null) {
                        pane.updateFromRoot(true);
                    }
                } catch (Exception ignored) {
                    // Project view may not be available
                }
            });
        }
    }
}
