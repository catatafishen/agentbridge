package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AcpClient;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Project service that manages which ACP agent is currently active.
 * The UI layer uses this instead of referencing concrete agent services directly,
 * keeping it agent-agnostic.
 *
 * <p>Also hosts shared UI preferences that apply regardless of which agent is active
 * (attach trigger character, follow-agent-files).</p>
 */
@Service(Service.Level.PROJECT)
public final class ActiveAgentManager {

    private static final Logger LOG = Logger.getInstance(ActiveAgentManager.class);

    private static final String KEY_ACTIVE_AGENT = "agent.activeType";
    private static final String KEY_ATTACH_TRIGGER = "agent.attachTriggerChar";
    private static final String DEFAULT_ATTACH_TRIGGER = "#";
    private static final String KEY_FOLLOW_AGENT_FILES = "agent.followAgentFiles";
    private static final String KEY_AUTO_CONNECT = "agent.autoConnect";
    private static final String KEY_CUSTOM_ACP_COMMAND = "agent.customAcpCommand";

    private final Project project;
    private volatile boolean acpConnected;

    public ActiveAgentManager(@NotNull Project project) {
        this.project = project;
    }

    @NotNull
    public static ActiveAgentManager getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ActiveAgentManager.class);
    }

    // ── Agent type ───────────────────────────────────────────────────────────

    public enum AgentType {
        COPILOT("copilot", "GitHub Copilot"),
        CLAUDE("claude", "Claude Code"),
        KIRO("kiro", "Kiro"),
        GEMINI("gemini", "Gemini"),
        OPENCODE("opencode", "OpenCode"),
        CLINE("cline", "Cline"),
        GENERIC("generic", "Generic ACP");

        private final String id;
        private final String displayName;

        AgentType(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        @NotNull
        static AgentType fromId(@NotNull String id) {
            for (AgentType t : values()) {
                if (t.id.equals(id)) return t;
            }
            return COPILOT;
        }
    }

    private final java.util.List<Runnable> switchListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Register a callback invoked after the active agent changes.
     * Typically used by the UI to reset sessions and reload models.
     */
    public void addSwitchListener(@NotNull Runnable listener) {
        switchListeners.add(listener);
    }

    public void removeSwitchListener(@NotNull Runnable listener) {
        switchListeners.remove(listener);
    }

    @NotNull
    public AgentType getActiveType() {
        String stored = PropertiesComponent.getInstance(project).getValue(KEY_ACTIVE_AGENT);
        return stored != null ? AgentType.fromId(stored) : AgentType.COPILOT;
    }

    public void switchAgent(@NotNull AgentType type) {
        AgentType previous = getActiveType();
        if (previous == type) return;

        LOG.info("Switching active agent from " + previous.id() + " to " + type.id());
        PropertiesComponent.getInstance(project).setValue(KEY_ACTIVE_AGENT, type.id());

        for (Runnable listener : switchListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("Agent switch listener failed", e);
            }
        }
    }

    // ── Active service shortcuts ─────────────────────────────────────────────

    @NotNull
    public AgentService getService() {
        return switch (getActiveType()) {
            case CLAUDE -> ClaudeService.getInstance(project);
            case KIRO -> KiroService.getInstance(project);
            case GEMINI -> GeminiService.getInstance(project);
            case OPENCODE -> OpenCodeService.getInstance(project);
            case CLINE -> ClineService.getInstance(project);
            case GENERIC -> GenericCustomService.getInstance(project);
            default -> CopilotService.getInstance(project);
        };
    }

    @NotNull
    public AcpClient getClient() {
        return getService().getClient();
    }

    @NotNull
    public AgentUiSettings getSettings() {
        return getService().getUiSettings();
    }

    // ── Shared UI preferences (agent-agnostic) ──────────────────────────────

    /**
     * Trigger character for file search in chat input.
     * "#" (VS Code Copilot style, default), "@" (JetBrains AI style), or "" (disabled).
     */
    @NotNull
    public static String getAttachTriggerChar() {
        return PropertiesComponent.getInstance().getValue(KEY_ATTACH_TRIGGER, DEFAULT_ATTACH_TRIGGER);
    }

    public static void setAttachTriggerChar(@NotNull String trigger) {
        PropertiesComponent.getInstance().setValue(KEY_ATTACH_TRIGGER, trigger, DEFAULT_ATTACH_TRIGGER);
    }

    /**
     * Whether to open files in the editor when the agent reads/writes them.
     * Project-scoped so each open project can have its own setting.
     */
    public static boolean getFollowAgentFiles(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_FOLLOW_AGENT_FILES, true);
    }

    public static void setFollowAgentFiles(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_FOLLOW_AGENT_FILES, enabled, true);
    }

    // ── ACP connection state ─────────────────────────────────────────────────

    public boolean isAcpConnected() {
        return acpConnected;
    }

    public void setAcpConnected(boolean connected) {
        this.acpConnected = connected;
    }

    // ── Auto-connect on startup ──────────────────────────────────────────────

    public boolean isAutoConnect() {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_AUTO_CONNECT, false);
    }

    public void setAutoConnect(boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_AUTO_CONNECT, enabled, false);
    }

    // ── Generic ACP custom command ───────────────────────────────────────────

    @NotNull
    public String getCustomAcpCommand() {
        return PropertiesComponent.getInstance(project).getValue(KEY_CUSTOM_ACP_COMMAND, "");
    }

    public void setCustomAcpCommand(@NotNull String command) {
        PropertiesComponent.getInstance(project).setValue(KEY_CUSTOM_ACP_COMMAND, command, "");
    }
}
