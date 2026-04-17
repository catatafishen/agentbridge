package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.github.catatafishen.agentbridge.ui.review.ReviewChangesPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBTabbedPane;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Tabbed container for the left-hand tool-window pane.
 * Hosts three tabs:
 * <ol>
 *   <li><b>Review</b> — the existing {@link ReviewChangesPanel} with pending agent edits.</li>
 *   <li><b>Project Files</b> — configuration/agent-definition files for this project.</li>
 *   <li><b>Prompts</b> — searchable list of user prompts in the current chat, click to scroll.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    public static final int TAB_REVIEW = 0;
    public static final int TAB_PROJECT_FILES = 1;
    public static final int TAB_PROMPTS = 2;

    private final JBTabbedPane tabs;
    private final transient ReviewChangesPanel reviewPanel;
    private final transient ProjectFilesPanel projectFilesPanel;
    private final transient PromptsPanel promptsPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole) {
        super(new BorderLayout());
        this.reviewPanel = new ReviewChangesPanel(project);
        Disposer.register(this, reviewPanel);

        this.projectFilesPanel = new ProjectFilesPanel(project);
        this.promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        tabs = new JBTabbedPane();
        tabs.addTab("Review", reviewPanel);
        tabs.addTab("Files", projectFilesPanel);
        tabs.addTab("Prompts", promptsPanel);

        // Refresh the project-files list each time that tab is selected so newly-created
        // files appear without requiring the user to reopen the tool window.
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == TAB_PROJECT_FILES) {
                projectFilesPanel.refresh();
            }
        });

        add(tabs, BorderLayout.CENTER);
    }

    /** Switches to the Review tab. Safe to call from the EDT. */
    public void selectReviewTab() {
        tabs.setSelectedIndex(TAB_REVIEW);
    }

    @Override
    public void dispose() {
        // Children registered with Disposer are disposed automatically.
    }
}
