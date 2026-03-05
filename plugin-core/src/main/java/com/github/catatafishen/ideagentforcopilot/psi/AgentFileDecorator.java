package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.*;

/**
 * Decorates files in the Project View with agent activity markers.
 * <p>
 * Background tint persists for the duration of the turn (write color takes
 * priority over read). A transient location label ("Agent reading" /
 * "Agent editing") appears for {@link FileAccessTracker#LABEL_DURATION_MS} after each access.
 */
public final class AgentFileDecorator implements ProjectViewNodeDecorator {

    private static final Color BG_READ = new Color(100, 140, 200, 25);
    private static final Color BG_WRITE = new Color(100, 180, 100, 30);

    @Override
    public void decorate(@org.jetbrains.annotations.NotNull ProjectViewNode<?> node,
                         @org.jetbrains.annotations.NotNull PresentationData data) {
        VirtualFile vf = node.getVirtualFile();
        if (vf == null || vf.isDirectory()) return;

        FileAccessTracker.AccessType access = FileAccessTracker.getAccess(vf);
        if (access == null) return;

        // Background: write-priority (WRITE and READ_WRITE both get green)
        boolean written = access == FileAccessTracker.AccessType.WRITE
                || access == FileAccessTracker.AccessType.READ_WRITE;
        data.setBackground(written ? BG_WRITE : BG_READ);
        data.setTooltip(written ? "Edited by agent" : "Read by agent");

        // Transient label (auto-expires after 2.5s in FileAccessTracker)
        String label = FileAccessTracker.getActiveLabel(vf);
        if (label != null) {
            data.setLocationString(label);
        }
    }
}
