package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tracks IDE tabs created by agents.
 *
 * <p>Ordinary tool-window tabs are cleaned at turn boundaries. Integrated terminal tabs are
 * different: they are long-lived resources owned by one MCP transport session. They use stable
 * terminal IDs and are released only by their owner or when that transport session ends. This
 * prevents one concurrent agent from reusing, writing to, or closing another agent's terminal.</p>
 */
public final class AgentTabTracker implements Disposable {

    /**
     * Maximum open terminal resources owned by one MCP session.
     */
    public static final int MAX_OPEN_AGENT_TERMINALS = 3;

    /**
     * Project-wide safety cap across all MCP sessions.
     */
    public static final int MAX_OPEN_AGENT_TERMINALS_GLOBAL = 12;

    private static final Logger LOG = Logger.getInstance(AgentTabTracker.class);
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";
    private static final String RUN_TOOL_WINDOW_ID = "Run";

    private record TabRef(String toolWindowId, String tabName) {
    }

    private record TerminalRef(
        String ownerId,
        String terminalId,
        Content content,
        long trackedSequence
    ) {
    }

    private record OpenTerminalSnapshot(
        List<Content> contents,
        long pruneThroughSequence
    ) {
    }

    /**
     * Public immutable view returned to terminal tools. The {@link Content} reference is the stable
     * IDE identity; display names are deliberately not used as resource identities.
     */
    public record AgentTerminal(@NotNull String terminalId, @NotNull Content content) {
        public @NotNull String displayName() {
            String name = content.getDisplayName();
            return name != null ? name : "(unnamed)";
        }
    }

    private final Project project;
    private final Supplier<List<Content>> openTerminalContents;
    private final List<TabRef> trackedTabs = new ArrayList<>();
    private final List<TerminalRef> trackedTerminals = new ArrayList<>();
    private long terminalSequence;

    public AgentTabTracker(@NotNull Project project) {
        this(project, () -> readOpenTerminalContents(project));
    }

    AgentTabTracker(@NotNull Project project, @NotNull Supplier<List<Content>> openTerminalContents) {
        this.project = project;
        this.openTerminalContents = openTerminalContents;
    }

    public static @NotNull AgentTabTracker getInstance(@NotNull Project project) {
        return project.getService(AgentTabTracker.class);
    }

    /**
     * Registers a non-terminal tool-window tab as agent-created.
     */
    public synchronized void trackTab(@NotNull String toolWindowId, @NotNull String tabName) {
        if (TERMINAL_TOOL_WINDOW_ID.equals(toolWindowId)) {
            throw new IllegalArgumentException(
                "Terminal tabs require trackTerminal(ownerId, content) so ownership is preserved");
        }
        trackedTabs.add(new TabRef(toolWindowId, tabName));
    }

    /**
     * Registers an exact terminal content object for one MCP owner and returns its stable handle.
     */
    public synchronized @NotNull String trackTerminal(
        @NotNull String ownerId,
        @NotNull Content content
    ) {
        for (TerminalRef ref : trackedTerminals) {
            if (ref.content() != content) continue;
            if (ref.ownerId().equals(ownerId)) {
                return ref.terminalId();
            }
            throw new IllegalStateException(
                "Terminal content is already owned by another MCP session");
        }
        String terminalId = UUID.randomUUID().toString();
        trackedTerminals.add(new TerminalRef(
            ownerId, terminalId, content, ++terminalSequence));
        return terminalId;
    }

    /**
     * Resolves a terminal only within {@code ownerId}. Stable ID takes precedence; a supplied
     * display name is additionally checked when both selectors are present. With neither selector,
     * the most recently created open terminal owned by the caller is returned.
     */
    public @Nullable AgentTerminal findOwnedTerminal(
        @NotNull String ownerId,
        @Nullable String terminalId,
        @Nullable String tabName
    ) {
        OpenTerminalSnapshot snapshot = snapshotOpenTerminals();
        synchronized (this) {
            pruneClosedTerminals(snapshot);
            if (terminalId != null) {
                for (TerminalRef ref : trackedTerminals) {
                    if (ref.ownerId().equals(ownerId) && ref.terminalId().equals(terminalId)) {
                        if (tabName != null
                            && !terminalTabNameMatches(tabName, ref.content().getDisplayName())) {
                            return null;
                        }
                        return view(ref);
                    }
                }
                return null;
            }

            for (int i = trackedTerminals.size() - 1; i >= 0; i--) {
                TerminalRef ref = trackedTerminals.get(i);
                if (!ref.ownerId().equals(ownerId)) continue;
                if (tabName == null
                    || terminalTabNameMatches(tabName, ref.content().getDisplayName())) {
                    return view(ref);
                }
            }
            return null;
        }
    }

    public @NotNull List<AgentTerminal> listOpenTerminals(@NotNull String ownerId) {
        OpenTerminalSnapshot snapshot = snapshotOpenTerminals();
        synchronized (this) {
            pruneClosedTerminals(snapshot);
            List<AgentTerminal> result = new ArrayList<>();
            for (TerminalRef ref : trackedTerminals) {
                if (ref.ownerId().equals(ownerId)) {
                    result.add(view(ref));
                }
            }
            return List.copyOf(result);
        }
    }

    public int countOpenTerminalTabs(@NotNull String ownerId) {
        return listOpenTerminals(ownerId).size();
    }

    public int countOpenTerminalTabs() {
        OpenTerminalSnapshot snapshot = snapshotOpenTerminals();
        synchronized (this) {
            pruneClosedTerminals(snapshot);
            return trackedTerminals.size();
        }
    }

    /**
     * Enforces both the per-owner reuse policy and a project-wide resource safety cap.
     */
    public boolean hasOpenTerminalCapacity(@NotNull String ownerId) {
        OpenTerminalSnapshot snapshot = snapshotOpenTerminals();
        synchronized (this) {
            pruneClosedTerminals(snapshot);
            int ownerCount = 0;
            for (TerminalRef ref : trackedTerminals) {
                if (ref.ownerId().equals(ownerId)) ownerCount++;
            }
            return ownerCount < MAX_OPEN_AGENT_TERMINALS
                && trackedTerminals.size() < resolveGlobalCap();
        }
    }

    /**
     * @return the currently effective project-wide terminal cap, sourced from settings when
     * available and falling back to {@link #MAX_OPEN_AGENT_TERMINALS_GLOBAL}. Exposed so
     * user-facing error messages can report the actual limit the user has configured.
     */
    public int getGlobalCap() {
        return resolveGlobalCap();
    }

    /**
     * Reads the current project-wide terminal cap. Falls back to the shipped default when the
     * settings service is unavailable (e.g. tests using a mock Project) so unit tests that
     * exercise this method continue to work without wiring a real service.
     */
    private int resolveGlobalCap() {
        try {
            McpServerSettings settings = McpServerSettings.getInstance(project);
            if (settings != null) return settings.getMaxAgentTerminalsGlobal();
        } catch (RuntimeException ignored) {
            // fall through to default
        }
        return MAX_OPEN_AGENT_TERMINALS_GLOBAL;
    }

    public synchronized void untrackTerminal(
        @NotNull String ownerId,
        @NotNull String terminalId
    ) {
        trackedTerminals.removeIf(ref ->
            ref.ownerId().equals(ownerId) && ref.terminalId().equals(terminalId));
    }

    /**
     * Releases the exact terminal contents belonging to one ended transport session.
     */
    public void closeOwnedTerminalTabs(@NotNull String ownerId) {
        List<TerminalRef> candidates;
        synchronized (this) {
            candidates = trackedTerminals.stream()
                .filter(ref -> ref.ownerId().equals(ownerId))
                .toList();
        }
        closeTerminalRefs(candidates);
    }

    /**
     * Releases all agent-owned terminal resources, used when the MCP server itself stops.
     */
    public void closeAllOwnedTerminalTabs() {
        List<TerminalRef> candidates;
        synchronized (this) {
            candidates = List.copyOf(trackedTerminals);
        }
        closeTerminalRefs(candidates);
    }

    private void closeTerminalRefs(@NotNull List<TerminalRef> candidates) {
        if (candidates.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            ContentManager manager = toolWindow != null ? toolWindow.getContentManager() : null;

            for (TerminalRef ref : candidates) {
                try {
                    boolean open = manager != null && containsIdentity(manager.getContents(), ref.content());
                    if (!open || manager.removeContent(ref.content(), true)) {
                        removeTerminalRef(ref);
                        LOG.debug("Released agent terminal " + ref.terminalId()
                            + " for owner " + ref.ownerId());
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to release agent terminal " + ref.terminalId(), e);
                }
            }
        });
    }

    private static @NotNull AgentTerminal view(@NotNull TerminalRef ref) {
        return new AgentTerminal(ref.terminalId(), ref.content());
    }

    /**
     * Captures the tracker generation before the potentially blocking EDT snapshot. Terminals
     * registered while that snapshot is in flight must not be pruned against stale contents.
     */
    private @NotNull OpenTerminalSnapshot snapshotOpenTerminals() {
        long pruneThroughSequence;
        synchronized (this) {
            pruneThroughSequence = terminalSequence;
        }
        return new OpenTerminalSnapshot(openTerminalContents.get(), pruneThroughSequence);
    }

    private synchronized void pruneClosedTerminals(@NotNull OpenTerminalSnapshot snapshot) {
        trackedTerminals.removeIf(ref ->
            ref.trackedSequence() <= snapshot.pruneThroughSequence()
                && !containsIdentity(snapshot.contents(), ref.content()));
    }

    private static boolean containsIdentity(@NotNull Content[] contents, @NotNull Content target) {
        for (Content content : contents) {
            if (content == target) return true;
        }
        return false;
    }

    private static boolean containsIdentity(@NotNull List<Content> contents, @NotNull Content target) {
        for (Content content : contents) {
            if (content == target) return true;
        }
        return false;
    }

    private static @NotNull List<Content> readOpenTerminalContents(@NotNull Project project) {
        List<Content> contents = new ArrayList<>();
        EdtUtil.invokeAndWait(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (toolWindow != null) {
                contents.addAll(List.of(toolWindow.getContentManager().getContents()));
            }
        });
        return contents;
    }

    /**
     * Matches an agent-provided base name against the IDE display name, which may include a
     * numeric {@code (N)} suffix.
     */
    public static boolean terminalTabNameMatches(
        @Nullable String trackedName,
        @Nullable String displayName
    ) {
        if (trackedName == null || displayName == null) return false;
        return displayName.equals(trackedName) || displayName.matches(
            java.util.regex.Pattern.quote(trackedName) + " \\(\\d+\\)"
        );
    }

    /**
     * Closes non-terminal tabs from previous turns. Terminals are session resources and must never
     * be closed by another prompt's global turn-boundary cleanup.
     */
    public void closeTrackedTabs() {
        CleanupSettings settings = CleanupSettings.getInstance(project);
        if (!settings.isAutoCloseAgentTabs()) return;

        List<TabRef> candidates;
        synchronized (this) {
            candidates = new ArrayList<>(trackedTabs);
        }
        if (candidates.isEmpty()) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            for (TabRef ref : candidates) {
                try {
                    if (closeTab(ref)) {
                        removeTrackedRef(ref);
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to close tab " + ref.toolWindowId() + "/" + ref.tabName(), e);
                }
            }
        });
    }

    /**
     * Determines whether a non-terminal tab should be skipped during turn cleanup.
     * The terminal branch is retained for compatibility with existing settings tests; session-owned
     * terminals do not enter this cleanup path.
     */
    static boolean shouldSkipClose(
        String toolWindowId,
        boolean closeRunningTerminals,
        boolean isProcessActive
    ) {
        if (TERMINAL_TOOL_WINDOW_ID.equals(toolWindowId) && !closeRunningTerminals) {
            return true;
        }
        return RUN_TOOL_WINDOW_ID.equals(toolWindowId) && isProcessActive;
    }

    private boolean closeTab(TabRef ref) {
        boolean processActive = RUN_TOOL_WINDOW_ID.equals(ref.toolWindowId())
            && isRunProcessStillActive(ref.tabName());
        if (shouldSkipClose(ref.toolWindowId(), false, processActive)) {
            return false;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(ref.toolWindowId());
        if (toolWindow == null) return true;

        ContentManager contentManager = toolWindow.getContentManager();
        for (Content content : contentManager.getContents()) {
            if (ref.tabName().equals(content.getDisplayName())) {
                contentManager.removeContent(content, true);
                LOG.debug("Closed agent tab: " + ref.toolWindowId() + "/" + ref.tabName());
                return true;
            }
        }
        return true;
    }

    private synchronized void removeTrackedRef(TabRef ref) {
        trackedTabs.remove(ref);
    }

    private synchronized void removeTerminalRef(TerminalRef ref) {
        trackedTerminals.remove(ref);
    }

    private boolean isRunProcessStillActive(String tabName) {
        for (RunContentDescriptor descriptor :
            RunContentManager.getInstance(project).getAllDescriptors()) {
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
        trackedTerminals.clear();
    }
}
