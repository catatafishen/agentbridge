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
 * Hosts four tabs:
 * <ol>
 *   <li><b>Review</b> — the existing {@link ReviewChangesPanel} with pending agent edits.</li>
 *   <li><b>Files</b> — configuration/agent-definition files for this project.</li>
 *   <li><b>Todos</b> — rendered view of the active agent session's {@code plan.md},
 *       with a {@code (done/total)} badge in the tab title when checkbox-style todos exist.</li>
 *   <li><b>Prompts</b> — searchable list of user prompts in the current chat, click to scroll.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    private static final int TAB_REVIEW = 0;
    private static final int TAB_FILES = 1;
    private static final int TAB_TODOS = 2;
    private static final int TAB_PROMPTS = 3;

    private final transient PlatformApiCompat.JBTabsPanel tabsPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole) {
        super(new BorderLayout());
        ReviewChangesPanel reviewPanel = new ReviewChangesPanel(project);
        Disposer.register(this, reviewPanel);

        ProjectFilesPanel projectFilesPanel = new ProjectFilesPanel(project);
        TodoPanel todoPanel = new TodoPanel(project);
        Disposer.register(this, todoPanel);
        PromptsPanel promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        tabsPanel = PlatformApiCompat.createJBTabsPanel(project, this);
        tabsPanel.addTab(reviewPanel, "Review");
        tabsPanel.addTab(projectFilesPanel, "Files");
        tabsPanel.addTab(todoPanel, "Todos");
        tabsPanel.addTab(promptsPanel, "Prompts");

        todoPanel.setOnProgressChanged(() -> {
            int total = todoPanel.getTotal();
            int done = todoPanel.getDone();
            String title = total > 0 ? "Todos (" + done + "/" + total + ")" : "Todos";
            tabsPanel.setTabTitle(TAB_TODOS, title);
        });

        // Refresh contextual tabs each time they are selected so changes made elsewhere
        // (new files on disk, the agent editing plan.md) appear without manual refresh.
        tabsPanel.addSelectionListener(idx -> {
            if (idx == TAB_FILES) projectFilesPanel.refresh();
            else if (idx == TAB_TODOS) todoPanel.refresh();
        }, this);

        add(tabsPanel.getComponent(), BorderLayout.CENTER);
    }

    /**
     * Switches to the Review tab. Safe to call from the EDT.
     */
    public void selectReviewTab() {
        tabsPanel.selectTab(TAB_REVIEW);
    }

    @Override
    public void dispose() {
        // Children registered with Disposer are disposed automatically.
    }
}
