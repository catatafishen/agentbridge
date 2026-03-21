package com.github.catatafishen.ideagentforcopilot.agent;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Unified interface for all agent types: ACP-based (Copilot, Junie, Kiro, OpenCode)
 * and non-ACP (Claude CLI, Anthropic Direct).
 * <p>
 * Implemented by {@code AcpClient} (abstract) and {@code AbstractClaudeAgentClient}.
 */
public interface AgentConnector {

    // ─── Identity ────────────────────────────────────

    /** Unique agent ID. e.g. "copilot", "junie", "claude-cli" */
    String agentId();

    /** Display name for UI. e.g. "GitHub Copilot", "Claude (CLI)" */
    String displayName();

    // ─── Lifecycle ───────────────────────────────────

    /** Start the agent process and perform handshake. */
    void start() throws AgentStartException;

    /** Gracefully stop the agent process. */
    void stop();

    /** Whether the agent process is alive and initialized. */
    boolean isConnected();

    // ─── Sessions ────────────────────────────────────

    /**
     * Create a new conversation session.
     *
     * @param cwd working directory for the session
     * @return session ID
     */
    String createSession(String cwd) throws AgentSessionException;

    /** Cancel an in-progress prompt turn. */
    void cancelSession(String sessionId);

    // ─── Prompts ─────────────────────────────────────

    /**
     * Send a prompt and receive streaming updates.
     *
     * @param request    the prompt content and metadata
     * @param onUpdate   callback for each streamed session update
     * @return the final prompt response when the turn completes
     */
    PromptResponse sendPrompt(PromptRequest request,
                              Consumer<SessionUpdate> onUpdate) throws AgentPromptException;

    // ─── Modes (built-in interaction modes, e.g. default/agent/plan/autopilot) ──

    /** Built-in mode slug to activate by default, or null for the agent's own default. */
    default @Nullable String defaultModeSlug() {
        return null;
    }

    /** Available built-in modes (populated from session/new after connection). */
    default List<AgentMode> getAvailableModes() {
        return List.of();
    }

    /** Returns the currently selected mode slug (user override or default). */
    default @Nullable String getCurrentModeSlug() {
        return defaultModeSlug();
    }

    /** Sets the current mode slug. No-op for agents that don't support mode selection. */
    default void setCurrentModeSlug(@Nullable String slug) {
        // no-op
    }

    // ─── Agents (custom agent definitions, e.g. intellij-default/intellij-explore) ──

    /** Custom agent slug to activate by default, or null to skip custom agent selection. */
    default @Nullable String defaultAgentSlug() {
        return null;
    }

    /** Available custom agents for this connector. Override to expose agent selection. */
    default List<AgentMode> getAvailableAgents() {
        return List.of();
    }

    /** Returns the currently selected custom agent slug (user override or default). */
    default @Nullable String getCurrentAgentSlug() {
        return defaultAgentSlug();
    }

    /** Sets the current custom agent slug. No-op for agents that don't support agent selection. */
    default void setCurrentAgentSlug(@Nullable String slug) {
        // no-op
    }

    /**
     * Returns the effective slug to send as {@code modeSlug} in session/prompt.
     * Custom agent slug takes priority over built-in mode slug.
     */
    default @Nullable String getEffectiveModeSlug() {
        String agent = getCurrentAgentSlug();
        if (agent != null && !agent.isEmpty()) return agent;
        return getCurrentModeSlug();
    }

    // ─── Config options (e.g. effort/thinking level, returned by session/new) ───

    /** Available config options for the current session (populated after session creation). */
    default List<AgentConfigOption> getAvailableConfigOptions() {
        return List.of();
    }

    /**
     * Sets a config option value for the session. Implementations should call
     * {@code session/set_config_option} per the ACP spec.
     */
    default void setConfigOption(@NotNull String sessionId, @NotNull String configId, @NotNull String valueId) {
        // no-op
    }

    // ─── Models ──────────────────────────────────────

    /** Available models for this agent. Empty list if not supported. */
    List<Model> getAvailableModels();

    /** Set the model for a session. No-op if agent doesn't support model selection. */
    void setModel(String sessionId, String modelId);

    /** How models are displayed in the UI. */
    default ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.NONE;
    }

    /** Extract a multiplier/tier string from model metadata (e.g. "2x"). */
    default @Nullable String getModelMultiplier(Model model) {
        return null;
    }

    // ─── Permission Handling ─────────────────────────

    /**
     * Called when the agent requests permission for a tool call.
     * The connector can handle it or delegate to the PermissionResolver.
     */
    default void onPermissionRequest(PermissionPrompt prompt) {
        prompt.deny("Not configured");
    }

    // ─── Display ─────────────────────────────────────

    /**
     * A mode (agent persona) available for this connector.
     *
     * @param slug        the mode identifier used in protocol requests
     * @param name        human-readable display name
     * @param description optional description of what this mode does
     */
    record AgentMode(@NotNull String slug, @NotNull String name, @Nullable String description) {}

    record AgentConfigOption(
        @NotNull String id,
        @NotNull String label,
        @Nullable String description,
        @NotNull List<AgentConfigOptionValue> values,
        @Nullable String selectedValueId
    ) {}

    record AgentConfigOptionValue(@NotNull String id, @NotNull String label) {}

    /**
     * How model information is shown in the UI.
     */
    enum ModelDisplayMode {
        /** Don't show model info. */
        NONE,
        /** Show model name. */
        NAME,
        /** Show token count per turn. */
        TOKEN_COUNT,
        /** Show multiplier (1x, 2x, 10x). */
        MULTIPLIER
    }

    /**
     * Callback for permission prompts.
     */
    interface PermissionPrompt {
        String toolCallId();
        String toolName();
        @Nullable String arguments();
        List<String> options();

        void allow(String optionId);
        void deny(String reason);
    }
}
