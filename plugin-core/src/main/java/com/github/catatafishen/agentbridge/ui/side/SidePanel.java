package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.PromptDbService;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.github.catatafishen.agentbridge.ui.review.DiffPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Container for the left-hand tool-window pane.
 * <p>
 * Tab selection is driven from outside (via the title-bar tab header in
 * {@link com.github.catatafishen.agentbridge.ui.ChatToolWindowContent}). This panel uses a
 * plain {@link CardLayout} so the tab strip appears in the IDE title bar rather than as a
 * separate row inside the panel itself.
 * <p>
 * Hosts five tabs:
 * <ol>
 *   <li><b>Diff</b> — pending agent edits ({@link DiffPanel}).</li>
 *   <li><b>Plan</b> — rendered view of the active agent's {@code plan.md} with a
 *       {@code (done/total)} badge when task items exist.</li>
 *   <li><b>MCP</b> — live list of MCP tool calls with timestamps and expandable I/O.</li>
 *   <li><b>Prompt DB</b> — searchable conversation history, click to scroll.</li>
 *   <li><b>Stats</b> — session statistics and billing info.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    public static final int TAB_REVIEW = 0;
    public static final int TAB_TODOS = 1;
    public static final int TAB_MCP = 2;
    public static final int TAB_PROMPT_DB = 3;
    public static final int TAB_STATS = 4;

    /**
     * Display names for each tab, in index order. Unmodifiable.
     */
    public static final java.util.List<String> TAB_NAMES =
        java.util.List.of("Diff", "Plan", "MCP", "Prompt DB", "Stats");

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentContainer = new JPanel(cardLayout);
    private int selectedTab = TAB_REVIEW;
    private String planBadge = "";
    private transient @Nullable Consumer<String> onPlanTitleChanged;

    private final transient Project project;
    private final TodoPanel todoPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole,
                     @NotNull SessionStatsPanel sessionStatsPanel) {
        super(new BorderLayout());
        this.project = project;

        DiffPanel reviewPanel = new DiffPanel(project);
        Disposer.register(this, reviewPanel);
        Disposer.register(this, sessionStatsPanel);

        todoPanel = new TodoPanel(project);
        Disposer.register(this, todoPanel);
        PromptsPanel promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        JPanel mcpTab = buildMcpTab(project);

        contentContainer.add(reviewPanel, String.valueOf(TAB_REVIEW));
        contentContainer.add(todoPanel, String.valueOf(TAB_TODOS));
        contentContainer.add(mcpTab, String.valueOf(TAB_MCP));
        contentContainer.add(promptsPanel, String.valueOf(TAB_PROMPT_DB));
        contentContainer.add(sessionStatsPanel, String.valueOf(TAB_STATS));
        cardLayout.show(contentContainer, String.valueOf(TAB_REVIEW));

        todoPanel.setOnProgressChanged(() -> {
            int total = todoPanel.getTotal();
            int done = todoPanel.getDone();
            planBadge = total > 0 ? " (" + done + "/" + total + ")" : "";
            if (onPlanTitleChanged != null) onPlanTitleChanged.accept(getPlanTitle());
        });

        PromptDbService.getInstance(project).registerNavigateCallback(params -> {
            selectTab(TAB_PROMPT_DB);
            promptsPanel.applySearchParams(params);
        });

        add(contentContainer, BorderLayout.CENTER);
    }

    /**
     * Switches to the given tab index and refreshes it if needed.
     */
    public void selectTab(int index) {
        if (index == selectedTab) return;
        selectedTab = index;
        cardLayout.show(contentContainer, String.valueOf(index));
        if (index == TAB_TODOS) todoPanel.refresh();
    }

    /**
     * Returns the currently selected tab index.
     */
    public int getSelectedTab() {
        return selectedTab;
    }

    /**
     * Returns the Plan tab title, including the {@code (done/total)} badge if tasks exist.
     */
    public @NotNull String getPlanTitle() {
        return "Plan" + planBadge;
    }

    /**
     * Registers a callback that fires whenever the Plan tab title changes (badge update).
     * The callback receives the new title string.
     */
    public void setOnPlanTitleChanged(@Nullable Consumer<String> callback) {
        this.onPlanTitleChanged = callback;
    }

    /**
     * Switches to the Diff tab. Safe to call from the EDT.
     */
    public void selectReviewTab() {
        selectTab(TAB_REVIEW);
    }

    /**
     * Builds the MCP tab with a resizable vertical split: JCEF tool calls on top,
     * hooks file browser on the bottom.
     */
    private @NotNull JPanel buildMcpTab(@NotNull Project project) {
        ToolCallsWebPanel toolCallsPanel = new ToolCallsWebPanel(project);
        Disposer.register(this, toolCallsPanel);

        Path hooksDir = project.getBasePath() != null
            ? Path.of(project.getBasePath(), ".agentbridge", "hooks")
            : null;

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JBLabel hooksLabel = new JBLabel("Hooks");
        hooksLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f));
        hooksLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottomPanel.add(hooksLabel, BorderLayout.NORTH);

        if (hooksDir != null) {
            ProjectFilesPanel hooksPanel = new ProjectFilesPanel(project, hooksDir);
            bottomPanel.add(hooksPanel, BorderLayout.CENTER);
        }

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.65f);
        splitter.setFirstComponent(toolCallsPanel);
        splitter.setSecondComponent(bottomPanel);

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(splitter, BorderLayout.CENTER);
        return tab;
    }

    @Override
    public void dispose() {
        PromptDbService.getInstance(project).registerNavigateCallback(null);
    }
}
