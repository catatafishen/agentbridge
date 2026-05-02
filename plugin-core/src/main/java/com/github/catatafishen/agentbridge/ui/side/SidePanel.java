package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.github.catatafishen.agentbridge.ui.review.DiffPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Tabbed container for the left-hand tool-window pane.
 * Uses {@link PlatformApiCompat#createJBTabsPanel} for native IntelliJ flat tab styling.
 * Hosts five tabs:
 * <ol>
 *   <li><b>Diff</b> — the existing {@link DiffPanel} with pending agent edits.</li>
 *   <li><b>Todo</b> — rendered view of the active agent session's {@code plan.md},
 *       with a {@code (done/total)} badge in the tab title when checkbox-style task items exist.</li>
 *   <li><b>Tools</b> — live list of MCP tool calls with timestamps, categories, and expandable I/O.</li>
 *   <li><b>Search</b> — searchable list of user prompts across the current conversation history, click to scroll.</li>
 *   <li><b>Session</b> — session statistics, billing info, and a project-files tree.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    private static final int TAB_REVIEW = 0;
    private static final int TAB_TODOS = 1;
    private static final int TAB_SESSION = 4;

    private final transient PlatformApiCompat.JBTabsPanel tabsPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole,
                     @NotNull SessionStatsPanel sessionStatsPanel) {
        super(new BorderLayout());
        DiffPanel reviewPanel = new DiffPanel(project);
        Disposer.register(this, reviewPanel);
        Disposer.register(this, sessionStatsPanel);

        TodoPanel todoPanel = new TodoPanel(project);
        Disposer.register(this, todoPanel);
        PromptsPanel promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        ToolCallListPanel toolCallPanel = new ToolCallListPanel(project);
        Disposer.register(this, toolCallPanel);

        tabsPanel = PlatformApiCompat.createJBTabsPanel(project, this);
        tabsPanel.addTab(reviewPanel, "Diff");
        tabsPanel.addTab(todoPanel, "Todo");
        tabsPanel.addTab(toolCallPanel, "Tools");
        tabsPanel.addTab(promptsPanel, "Search");
        tabsPanel.addTab(sessionStatsPanel, "Session");

        todoPanel.setOnProgressChanged(() -> {
            int total = todoPanel.getTotal();
            int done = todoPanel.getDone();
            String title = total > 0 ? "Todo (" + done + "/" + total + ")" : "Todo";
            tabsPanel.setTabTitle(TAB_TODOS, title);
        });

        // Refresh contextual tabs each time they are selected so changes made elsewhere
        // (new files on disk, the agent editing plan.md) appear without manual refresh.
        tabsPanel.addSelectionListener(idx -> {
            if (idx == TAB_TODOS) todoPanel.refresh();
            else if (idx == TAB_SESSION) sessionStatsPanel.refreshFiles();
        }, this);

        add(tabsPanel.getComponent(), BorderLayout.CENTER);
    }

    /**
     * Switches to the Diff tab. Safe to call from the EDT.
     */
    public void selectReviewTab() {
        tabsPanel.selectTab(TAB_REVIEW);
    }

    @Override
    public void dispose() {
        // Children registered with Disposer are disposed automatically.
    }
}
