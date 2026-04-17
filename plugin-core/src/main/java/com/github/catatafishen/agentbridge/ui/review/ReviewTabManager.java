package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/**
 * Manages the lifecycle of the Review tab in the AgentBridge tool window.
 * <p>
 * Subscribes to {@link ReviewSessionTopic} events and adds/removes the
 * {@link ReviewChangesPanel} as a content tab. The tab is shown when a review session
 * is active with items to review, and automatically selected when a git tool is blocked.
 */
public final class ReviewTabManager implements Disposable {

    private static final String TAB_DISPLAY_NAME = "Review";

    private final Project project;
    private ReviewChangesPanel panel;
    private Content reviewContent;

    public ReviewTabManager(@NotNull Project project) {
        this.project = project;

        project.getMessageBus().connect(this)
            .subscribe(ReviewSessionTopic.TOPIC, this::onReviewStateChanged);
    }

    public static ReviewTabManager getInstance(@NotNull Project project) {
        return project.getService(ReviewTabManager.class);
    }

    private void onReviewStateChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;

            AgentEditSession session = AgentEditSession.getInstance(project);
            boolean showTab = session.isActive() && session.hasChanges();

            ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("AgentBridge");
            if (toolWindow == null) return;
            ContentManager contentManager = toolWindow.getContentManager();

            if (showTab) {
                if (reviewContent == null) {
                    panel = new ReviewChangesPanel(project);
                    reviewContent = ContentFactory.getInstance()
                        .createContent(panel, TAB_DISPLAY_NAME, false);
                    reviewContent.setCloseable(false);
                    contentManager.addContent(reviewContent, 0);
                } else {
                    panel.refresh();
                }
                // Select the Review tab so the user sees it
                contentManager.setSelectedContent(reviewContent);
            } else if (reviewContent != null) {
                panel.refresh();
                if (!session.isActive()) {
                    removeTab(contentManager);
                }
            }
        });
    }

    /**
     * Selects the Review tab in the AgentBridge tool window if it exists.
     * Called from {@link AgentEditSession#notifyReviewRequired} to bring the tab into focus
     * when a git tool is blocked.
     */
    public void selectReviewTab() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (reviewContent == null || project.isDisposed()) return;
            ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("AgentBridge");
            if (toolWindow == null) return;
            toolWindow.getContentManager().setSelectedContent(reviewContent);
            toolWindow.activate(null);
        });
    }

    private void removeTab(@NotNull ContentManager contentManager) {
        if (reviewContent != null) {
            contentManager.removeContent(reviewContent, true);
            reviewContent = null;
            panel = null;
            // Select the remaining content (the chat tab)
            Content[] contents = contentManager.getContents();
            if (contents.length > 0) {
                contentManager.setSelectedContent(contents[0]);
            }
        }
    }

    @Override
    public void dispose() {
        panel = null;
        reviewContent = null;
    }
}
