package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks tool window tabs created by the agent and closes them at turn boundaries.
 *
 * <p>Tools call {@link #trackTab(String, String)} when they create a tab.
 * At the start of a new turn, {@link #closeTrackedTabs()} closes tracked tabs
 * that are safe to close and keeps skipped tabs tracked for later cleanup.</p>
 */
public final class AgentTabTracker implements Disposable {

    public static final int MAX_OPEN_AGENT_TERMINALS = 3;

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
        return countMatchingTerminalTabs(trackedTerminalTabNames(), openTerminalDisplayNames());
    }

    public boolean hasOpenTerminalCapacity() {
        return hasTerminalCapacity(countOpenTerminalTabs());
    }

    public @Nullable String findMostRecentOpenTerminalTabName() {
        return mostRecentOpenTerminalTabName(trackedTerminalTabNames(), openTerminalDisplayNames());
    }

    public synchronized boolean isTrackedTerminalTab(@NotNull String displayName) {
        for (TabRef ref : trackedTabs) {
            if (TERMINAL_TOOL_WINDOW_ID.equals(ref.toolWindowId())
                && terminalTabNameMatches(ref.tabName(), displayName)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void untrackTerminalTab(@NotNull String displayName) {
        trackedTabs.removeIf(ref -> TERMINAL_TOOL_WINDOW_ID.equals(ref.toolWindowId())
            && terminalTabNameMatches(ref.tabName(), displayName));
    }

    private synchronized List<String> trackedTerminalTabNames() {
        List<String> names = new ArrayList<>();
        for (TabRef ref : trackedTabs) {
            if (TERMINAL_TOOL_WINDOW_ID.equals(ref.toolWindowId())) {
                names.add(ref.tabName());
            }
        }
        return names;
    }

    private List<String> openTerminalDisplayNames() {
        List<String> names = new ArrayList<>();
        EdtUtil.invokeAndWait(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (toolWindow == null) return;
            for (Content content : toolWindow.getContentManager().getContents()) {
                names.add(content.getDisplayName());
            }
        });
        return names;
    }

    static boolean hasTerminalCapacity(int openAgentTerminalCount) {
        return openAgentTerminalCount < MAX_OPEN_AGENT_TERMINALS;
    }

    static @Nullable String mostRecentOpenTerminalTabName(
        List<String> trackedTerminalTabNames,
        List<String> openDisplayNames
    ) {
        for (int trackedIndex = trackedTerminalTabNames.size() - 1; trackedIndex >= 0; trackedIndex--) {
            String trackedName = trackedTerminalTabNames.get(trackedIndex);
            for (String displayName : openDisplayNames) {
                if (terminalTabNameMatches(trackedName, displayName)) {
                    return displayName;
                }
            }
        }
        return null;
    }

    /**
     * Counts currently open terminal tabs that correspond to tracked agent tabs.
     */
    static int countMatchingTerminalTabs(List<String> trackedTerminalTabNames, List<String> openDisplayNames) {
        int count = 0;
        for (String displayName : openDisplayNames) {
            if (displayName == null) continue;
            for (String tabName : trackedTerminalTabNames) {
                if (terminalTabNameMatches(tabName, displayName)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    public static boolean terminalTabNameMatches(@Nullable String trackedName, @Nullable String displayName) {
        if (trackedName == null || displayName == null) return false;
        return displayName.equals(trackedName) || displayName.matches(
            java.util.regex.Pattern.quote(trackedName) + " \\(\\d+\\)"
        );
    }

    /**
     * Closes tracked tabs from previous turns and retains tabs skipped by cleanup policy.
     */
    public void closeTrackedTabs() {
        CleanupSettings settings = CleanupSettings.getInstance(project);
        if (!settings.isAutoCloseAgentTabs()) return;

        List<TabRef> candidates;
        synchronized (this) {
            candidates = new ArrayList<>(trackedTabs);
        }
        if (candidates.isEmpty()) return;

        boolean closeRunningTerminals = settings.isAutoCloseRunningTerminals();
        ApplicationManager.getApplication().invokeLater(() -> {
            for (TabRef ref : candidates) {
                try {
                    if (closeTab(ref, closeRunningTerminals)) {
                        removeTrackedRef(ref);
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to close tab " + ref.toolWindowId() + "/" + ref.tabName(), e);
                }
            }
        });
    }

    /**
     * Determines whether a tab should be skipped during cleanup.
     */
    static boolean shouldSkipClose(String toolWindowId, boolean closeRunningTerminals,
                                   boolean isProcessActive) {
        if (TERMINAL_TOOL_WINDOW_ID.equals(toolWindowId) && !closeRunningTerminals) {
            return true;
        }
        return RUN_TOOL_WINDOW_ID.equals(toolWindowId) && isProcessActive;
    }

    /**
     * @return true when the reference can be forgotten; false when it must be retried later.
     */
    private boolean closeTab(TabRef ref, boolean closeRunningTerminals) {
        boolean processActive = RUN_TOOL_WINDOW_ID.equals(ref.toolWindowId())
            && isRunProcessStillActive(ref.tabName());
        if (shouldSkipClose(ref.toolWindowId(), closeRunningTerminals, processActive)) {
            return false;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ref.toolWindowId());
        if (toolWindow == null) return true;

        ContentManager contentManager = toolWindow.getContentManager();
        for (Content content : contentManager.getContents()) {
            String displayName = content.getDisplayName();
            if (tabDisplayNameMatches(ref, displayName)) {
                contentManager.removeContent(content, true);
                LOG.debug("Closed agent tab: " + ref.toolWindowId() + "/" + displayName);
                return true;
            }
        }
        return true;
    }

    private static boolean tabDisplayNameMatches(TabRef ref, @Nullable String displayName) {
        if (TERMINAL_TOOL_WINDOW_ID.equals(ref.toolWindowId())) {
            return terminalTabNameMatches(ref.tabName(), displayName);
        }
        return ref.tabName().equals(displayName);
    }

    private synchronized void removeTrackedRef(TabRef ref) {
        trackedTabs.remove(ref);
    }

    private boolean isRunProcessStillActive(String tabName) {
        for (RunContentDescriptor descriptor : RunContentManager.getInstance(project).getAllDescriptors()) {
            if (tabName.equals(descriptor.getDisplayName())) {
                ProcessHandler handler = descriptor.getProcessHandler();
                return handler != null && !handler.isProcessTerminated();
            }
        }
        return false;
    }

    @Override
    public synchronized void dispose() {
        trackedTabs.clear();
    }
}
