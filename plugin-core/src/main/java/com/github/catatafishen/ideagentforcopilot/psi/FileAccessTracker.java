package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks which files the agent has read/written during the current session.
 * Used by {@link AgentFileDecorator} to annotate files in the Project View.
 * <p>
 * Background tints persist until {@link #clear()} (end of turn).
 * Active labels ("Agent reading" / "Agent editing") auto-expire after 2.5 seconds.
 */
final class FileAccessTracker {

    enum AccessType {
        READ, WRITE, READ_WRITE
    }

    private static final long LABEL_DURATION_MS = 2500;

    private static final Map<String, AccessType> accessMap = new ConcurrentHashMap<>();
    private static final Map<String, String> activeLabels = new ConcurrentHashMap<>();
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
        refreshProjectView(project);
        scheduleLabelExpiry(project, key);
    }

    static void recordWrite(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        String key = vf.getPath();
        accessMap.merge(key, AccessType.WRITE, FileAccessTracker::merge);
        activeLabels.put(key, "Agent editing");
        refreshProjectView(project);
        scheduleLabelExpiry(project, key);
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

    static void clear() {
        accessMap.clear();
        activeLabels.clear();
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

    private static void scheduleLabelExpiry(Project project, String key) {
        scheduler.schedule(() -> {
            activeLabels.remove(key);
            refreshProjectView(project);
        }, LABEL_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    private static void refreshProjectView(Project project) {
        SwingUtilities.invokeLater(() -> {
            try {
                var pane = ProjectView.getInstance(project).getCurrentProjectViewPane();
                if (pane != null && pane.getTree() != null) {
                    // Repaint the tree directly to re-trigger decorators on visible nodes.
                    // ProjectView.refresh() rebuilds tree structure but may skip
                    // re-decoration of nodes whose structure hasn't changed.
                    pane.getTree().repaint();
                }
            } catch (Exception ignored) {
                // Project view may not be available
            }
        });
    }
}
