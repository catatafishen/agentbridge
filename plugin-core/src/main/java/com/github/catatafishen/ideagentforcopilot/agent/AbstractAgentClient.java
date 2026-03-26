package com.github.catatafishen.ideagentforcopilot.agent;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.bridge.SessionOption;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Unified abstract base class for all agent types: ACP-based (Copilot, Junie, Kiro, OpenCode)
 * and non-ACP (Claude CLI, Anthropic Direct).
 * <p>
 * Replaces the {@link AgentConnector} interface and {@code bridge.AgentClient} interface.
 * Extended by {@code AcpClient} and {@code AbstractClaudeAgentClient}.
 */
public abstract class AbstractAgentClient {

    // ─── Identity ────────────────────────────────────

    /**
     * Unique agent ID. e.g. "copilot", "junie", "claude-cli"
     */
    public abstract String agentId();

    /**
     * Display name for UI. e.g. "GitHub Copilot", "Claude (CLI)"
     */
    public abstract String displayName();

    // ─── Lifecycle ───────────────────────────────────

    /**
     * Start the agent process and perform handshake.
     */
    public abstract void start() throws Exception;

    /**
     * Gracefully stop the agent process.
     */
    public abstract void stop();

    /**
     * Clear any persisted session/thread ID so the next session starts fresh.
     * Called when the user explicitly starts a new conversation. Default: no-op.
     */
    public void clearPersistedSession() {
    }

    /**
     * Whether the agent process is alive and initialized.
     */
    public abstract boolean isConnected();

    /**
     * Whether this client is alive and usable. Defaults to {@link #isConnected()}.
     */
    public boolean isHealthy() {
        return isConnected();
    }

    /**
     * Close/stop the agent. Alias for {@link #stop()}.
     */
    public void close() {
        stop();
    }

    // ─── Sessions ────────────────────────────────────

    /**
     * Create a new conversation session.
     *
     * @param cwd working directory for the session
     * @return session ID
     */
    public abstract String createSession(String cwd) throws Exception;

    /**
     * Cancel an in-progress prompt turn.
     */
    public abstract void cancelSession(String sessionId);

    // ─── Prompts ─────────────────────────────────────

    /**
     * Send a prompt and receive streaming updates.
     *
     * @param request  the prompt content and metadata
     * @param onUpdate callback for each streamed session update
     * @return the final prompt response when the turn completes
     */
    public abstract PromptResponse sendPrompt(PromptRequest request,
                                              Consumer<SessionUpdate> onUpdate) throws Exception;

    // ─── Modes (built-in interaction modes, e.g. default/agent/plan/autopilot) ──

    /**
     * Built-in mode slug to activate by default, or null for the agent's own default.
     */
    public @Nullable String defaultModeSlug() {
        return null;
    }

    /**
     * Available built-in modes (populated from session/new after connection).
     */
    public List<AgentMode> getAvailableModes() {
        return List.of();
    }

    /**
     * Returns the currently selected mode slug (user override or default).
     */
    public @Nullable String getCurrentModeSlug() {
        return defaultModeSlug();
    }

    /**
     * Sets the current mode slug. No-op for agents that don't support mode selection.
     */
    public void setCurrentModeSlug(@Nullable String slug) {
        // no-op
    }

    // ─── Agents (custom agent definitions, e.g. intellij-default/intellij-explore) ──

    /**
     * Custom agent slug to activate by default, or null to skip custom agent selection.
     */
    public @Nullable String defaultAgentSlug() {
        return null;
    }

    /**
     * Available custom agents for this connector. Override to expose agent selection.
     */
    public List<AgentMode> getAvailableAgents() {
        return List.of();
    }

    /**
     * Returns the currently selected custom agent slug (user override or default).
     */
    public @Nullable String getCurrentAgentSlug() {
        return defaultAgentSlug();
    }

    /**
     * Sets the current custom agent slug. No-op for agents that don't support agent selection.
     */
    public void setCurrentAgentSlug(@Nullable String slug) {
        // no-op
    }

    /**
     * Returns the effective slug to send as {@code modeSlug} in session/prompt.
     * Custom agent slug takes priority over built-in mode slug.
     */
    public @Nullable String getEffectiveModeSlug() {
        String agent = getCurrentAgentSlug();
        if (agent != null && !agent.isEmpty()) return agent;
        return getCurrentModeSlug();
    }

    // ─── Config options (e.g. effort/thinking level, returned by session/new) ───

    /**
     * Available config options for the current session (populated after session creation).
     */
    public List<AgentConfigOption> getAvailableConfigOptions() {
        return List.of();
    }

    /**
     * Sets a config option value for the session.
     */
    public void setConfigOption(@NotNull String sessionId, @NotNull String configId, @NotNull String valueId) {
        // no-op
    }

    // ─── Models ──────────────────────────────────────

    /**
     * Available models for this agent. Empty list if not supported.
     */
    public abstract List<Model> getAvailableModels();

    /**
     * The agent-reported currently selected model ID from session/new, or {@code null} if not provided.
     */
    public @Nullable String getCurrentModelId() {
        return null;
    }

    /**
     * Set the model for a session. No-op if agent doesn't support model selection.
     */
    public abstract void setModel(String sessionId, String modelId);

    /**
     * How models are displayed in the UI.
     */
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.NONE;
    }

    /**
     * Extract a multiplier/tier string from model metadata (e.g. "2x").
     */
    public @Nullable String getModelMultiplier(@NotNull Model model) {
        return null;
    }

    /**
     * Returns the pricing multiplier label for the given model ID (e.g. "1x", "2x"),
     * or {@code null} if the multiplier is not available for this model.
     * Default implementation looks up the model in {@link #getAvailableModels()} and calls
     * {@link #getModelMultiplier(Model)}.
     */
    @Nullable
    public String getModelMultiplier(@NotNull String modelId) {
        for (Model m : getAvailableModels()) {
            if (modelId.equals(m.id())) {
                return getModelMultiplier(m);
            }
        }
        return null;
    }

    /**
     * Whether this client supports per-model premium-request multipliers.
     * Default: {@code false}.
     */
    public boolean supportsMultiplier() {
        return false;
    }

    // ─── Session Options ─────────────────────────────

    /**
     * Returns session option descriptors that this client supports beyond model selection.
     * Default: empty.
     */
    @NotNull
    public List<SessionOption> listSessionOptions() {
        return List.of();
    }

    /**
     * Apply a session option value for the given session.
     * Default: no-op.
     */
    public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        // no-op
    }

    // ─── Permission Handling ─────────────────────────

    /**
     * Register a listener for tool permission prompts.
     * Default: no-op for clients that handle permissions internally.
     */
    public void setPermissionRequestListener(Consumer<PermissionPrompt> listener) {
        // no-op by default
    }

    /**
     * Notify that a sub-agent task is now active or has completed. No-op by default.
     */
    public void setSubAgentActive(boolean active) {
        // no-op by default
    }

    // ─── Project files configuration ─────────────────

    /**
     * Returns the default project file shortcuts for this agent.
     * Default: empty.
     */
    @NotNull
    public List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        return List.of();
    }

    /**
     * Checks whether this agent is authenticated and ready to accept prompts.
     * Default implementation uses {@link #isHealthy()}.
     *
     * @return {@code null} if authenticated and ready, or a human-readable error message otherwise
     */
    @Nullable
    public String checkAuthentication() {
        return isHealthy() ? null : "Agent not started";
    }

    /**
     * Checks authentication state without requiring the agent process to be started.
     * Override in subclasses that can determine auth state from local credential files.
     * Default returns {@code null} (no pre-start check available).
     *
     * @return {@code null} if credentials look valid (or unknown), or a human-readable error message
     */
    @Nullable
    public String checkAuthenticationPreStart() {
        return null;
    }

    // ─── Auth ────────────────────────────────────────

    /**
     * Authentication method info for sign-in commands, or null if not applicable.
     */
    @Nullable
    public com.github.catatafishen.ideagentforcopilot.bridge.AuthMethod getAuthMethod() {
        return null;
    }

    // ─── Display ─────────────────────────────────────

    /**
     * A mode (agent persona) available for this connector.
     *
     * @param slug        the mode identifier used in protocol requests
     * @param name        human-readable display name
     * @param description optional description of what this mode does
     */
    public record AgentMode(@NotNull String slug, @NotNull String name, @Nullable String description) {
    }

    public record AgentConfigOption(
        @NotNull String id,
        @NotNull String label,
        @Nullable String description,
        @NotNull List<AgentConfigOptionValue> values,
        @Nullable String selectedValueId
    ) {
    }

    public record AgentConfigOptionValue(@NotNull String id, @NotNull String label) {
    }

    /**
     * How model information is shown in the UI.
     */
    public enum ModelDisplayMode {
        /**
         * Don't show model info.
         */
        NONE,
        /**
         * Show model name.
         */
        NAME,
        /**
         * Show token count per turn.
         */
        TOKEN_COUNT,
        /**
         * Show multiplier (1x, 2x, 10x).
         */
        MULTIPLIER
    }

    /**
     * Callback for permission prompts.
     */
    public interface PermissionPrompt {
        String toolCallId();

        String toolName();

        @Nullable String arguments();

        List<String> options();

        void allow(String optionId);

        void deny(String reason);
    }
}
