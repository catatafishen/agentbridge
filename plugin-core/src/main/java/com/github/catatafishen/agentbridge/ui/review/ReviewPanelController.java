package com.github.catatafishen.agentbridge.ui.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Project service that brokers expand requests for the inline Review panel hosted by
 * {@link com.github.catatafishen.agentbridge.ui.ChatToolWindowContent}.
 * <p>
 * Non-UI code (e.g. {@code AgentEditSession.notifyReviewRequired}) calls
 * {@link #expandReviewPanel()} without reaching into the tool-window UI directly and
 * without caring whether the content has been constructed yet. When the tool window
 * is not yet visible, the panel is activated first so the expanded review is
 * immediately visible to the user.
 * <p>
 * The title-bar toggle in the tool window manipulates the splitter directly and does
 * not go through this service.
 */
public final class ReviewPanelController {

    private final Project project;
    private volatile Runnable expandHandler;

    public ReviewPanelController(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull ReviewPanelController getInstance(@NotNull Project project) {
        return project.getService(ReviewPanelController.class);
    }

    /**
     * Registers the expand callback from {@link com.github.catatafishen.agentbridge.ui.ChatToolWindowContent}
     * once the splitter is built. Must be called on the EDT.
     */
    public void registerExpandHandler(@NotNull Runnable expand) {
        this.expandHandler = expand;
    }

    /**
     * Brings the AgentBridge tool window forward and expands the Review panel.
     * No-op if the panel has not been built yet and the tool window is not openable.
     * Safe to call from any thread.
     */
    public void expandReviewPanel() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("AgentBridge");
            if (tw != null && !tw.isVisible()) {
                tw.activate(this::runExpand, false);
            } else {
                runExpand();
            }
        });
    }

    private void runExpand() {
        Runnable h = expandHandler;
        if (h != null) h.run();
    }
}
