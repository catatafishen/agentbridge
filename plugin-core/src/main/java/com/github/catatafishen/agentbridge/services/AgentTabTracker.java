package com.github.catatafishen.agentbridge.services;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks tool window tabs created by the agent and closes them at turn boundaries.
 *
 * <p>Tools call {@link #trackTab(String, String)} when they create a tab.
 * At the start of a new turn, {@link #closeTrackedTabs()} closes all tracked
 * tabs from previous turns, respecting {@link CleanupSettings} for terminal
 * and running-process behavior.</p>
 */
public final class AgentTabTracker implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentTabTracker.class);
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    private static final String RUN_TOOL_WINDOW_ID = "Run";

    private record TabRef(String toolWindowId, String tabName) {
    }

    private final Project project;
    private final List<TabRef> trackedTabs = new ArrayList<>();

    public AgentTabTracker(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull AgentTabTracker getInstance(@NotNull Project project) {
        return project.getService(AgentTabTracker.class);
    }

    /**
     * Registers a tab as agent-created. Call this after the tool creates the tab.
     */
    public synchronized void trackTab(@NotNull String toolWindowId, @NotNull String tabName) {
        trackedTabs.add(new TabRef(toolWindowId, tabName));
    }

    public int countOpenTerminalTabs() {
        List<String> trackedTerminalTabNames;
        synchronized (this) {
            trackedTerminalTabNames = new ArrayList<>();
            for (TabRef ref : trackedTabs) {
                if (TERMINAL_TOOL_WINDOW_ID.equals(ref.toolWindowId())) {
                    trackedTerminalTabNames.add(ref.tabName());
                }
            }
        }
        if (trackedTerminalTabNames.isEmpty()) return 0;

        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
        if (tw == null) return 0;

        List<String> openDisplayNames = new ArrayList<>();
        for (Content content : tw.getContentManager().getContents()) {
            openDisplayNames.add(content.getDisplayName());
        }
        return countMatchingTerminalTabs(trackedTerminalTabNames, openDisplayNames);
    }

    /**
     * Counts how many of the currently open tab display names correspond to a tracked agent
     * terminal tab. A display name matches if it contains a tracked tab name (the IDE may append
     * suffixes such as {@code " (1)"} for duplicate titles). Each open tab is counted at most once.
     *
     * <p>Pure predicate — no I/O, no IntelliJ API dependency.</p>
     */
    static int countMatchingTerminalTabs(List<String> trackedTerminalTabNames, List<String> openDisplayNames) {
        int count = 0;
        for (String displayName : openDisplayNames) {
            if (displayName == null) continue;
            for (String tabName : trackedTerminalTabNames) {
                if (displayName.contains(tabName)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Closes all tracked tabs from previous turns. Must be called at the start
     * of a new turn. Respects cleanup settings.
     */
    public void closeTrackedTabs() {
        CleanupSettings settings = CleanupSettings.getInstance(project);
        if (!settings.isAutoCloseAgentTabs()) return;

        List<TabRef> toClose;
        synchronized (this) {
            toClose = new ArrayList<>(trackedTabs);
            trackedTabs.clear();
        }

        if (toClose.isEmpty()) return;

        boolean closeRunningTerminals = settings.isAutoCloseRunningTerminals();
        ApplicationManager.getApplication().invokeLater(() -> {
            for (TabRef ref : toClose) {
                try {
                    closeTab(ref, closeRunningTerminals);
                } catch (Exception e) {
                    LOG.debug("Failed to close tab " + ref.toolWindowId + "/" + ref.tabName, e);
                }
            }
        });
    }

    /**
     * Determines whether a tab should be skipped during cleanup.
     *
     * <p>A tab should be skipped if:
     * <ul>
     *   <li>It is a Terminal tab and {@code closeRunningTerminals} is false.</li>
     *   <li>It is a Run tab and its process is still active.</li>
     * </ul>
     *
     * <p>Pure predicate — no I/O, no IntelliJ API dependency.</p>
     */
    static boolean shouldSkipClose(String toolWindowId, boolean closeRunningTerminals,
                                   boolean isProcessActive) {
        if (TERMINAL_TOOL_WINDOW_ID.equals(toolWindowId) && !closeRunningTerminals) {
            return true;
        }
        return RUN_TOOL_WINDOW_ID.equals(toolWindowId) && isProcessActive;
    }

    private void closeTab(TabRef ref, boolean closeRunningTerminals) {
        boolean processActive = "Run".equals(ref.toolWindowId)
            && isRunProcessStillActive(ref.tabName);
        if (shouldSkipClose(ref.toolWindowId, closeRunningTerminals, processActive)) {
            return;
        }

        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(ref.toolWindowId);
        if (tw == null) return;

        ContentManager cm = tw.getContentManager();
        for (Content content : cm.getContents()) {
            String displayName = content.getDisplayName();
            if (displayName != null && displayName.equals(ref.tabName)) {
                cm.removeContent(content, true);
                LOG.debug("Closed agent tab: " + ref.toolWindowId + "/" + ref.tabName);
                break;
            }
        }
    }

    private boolean isRunProcessStillActive(String tabName) {
        for (RunContentDescriptor desc : RunContentManager.getInstance(project).getAllDescriptors()) {
            if (tabName.equals(desc.getDisplayName())) {
                ProcessHandler handler = desc.getProcessHandler();
                return handler != null && !handler.isProcessTerminated();
            }
        }
        return false;
    }

    @Override
    public void dispose() {
        synchronized (this) {
            trackedTabs.clear();
        }
    }
}
