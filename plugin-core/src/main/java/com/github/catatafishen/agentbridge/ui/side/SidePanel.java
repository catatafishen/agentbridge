package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.github.catatafishen.agentbridge.ui.review.ReviewChangesPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Tabbed container for the left-hand tool-window pane.
 * Uses {@link PlatformApiCompat#createJBTabsPanel} for native IntelliJ flat tab styling.
 * Hosts three tabs:
 * <ol>
 *   <li><b>Review</b> — the existing {@link ReviewChangesPanel} with pending agent edits.</li>
 *   <li><b>Project Files</b> — configuration/agent-definition files for this project.</li>
 *   <li><b>Prompts</b> — searchable list of user prompts in the current chat, click to scroll.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    private static final int TAB_FILES = 1;

    private final transient PlatformApiCompat.JBTabsPanel tabsPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole) {
        super(new BorderLayout());
        ReviewChangesPanel reviewPanel = new ReviewChangesPanel(project);
        Disposer.register(this, reviewPanel);

        ProjectFilesPanel projectFilesPanel = new ProjectFilesPanel(project);
        PromptsPanel promptsPanel = new PromptsPanel(chatConsole);
        Disposer.register(this, promptsPanel);

        tabsPanel = PlatformApiCompat.createJBTabsPanel(project, this);
        tabsPanel.addTab(reviewPanel, "Review");
        tabsPanel.addTab(projectFilesPanel, "Files");
        tabsPanel.addTab(promptsPanel, "Prompts");

        // Refresh the project-files list each time that tab is selected so newly-created
        // files appear without requiring the user to reopen the tool window.
        tabsPanel.addSelectionListener(idx -> {
            if (idx == TAB_FILES) projectFilesPanel.refresh();
        }, this);

        add(tabsPanel.getComponent(), BorderLayout.CENTER);
    }

    /**
     * Switches to the Review tab. Safe to call from the EDT.
     */
    public void selectReviewTab() {
        tabsPanel.selectTab(0);
    }

    @Override
    public void dispose() {
        // Children registered with Disposer are disposed automatically.
    }
}
