package com.github.catatafishen.ideagentforcopilot.agent;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
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

    /** Mode slug to activate by default when sending a prompt, or null to use the agent's own default. */
    default @Nullable String defaultModeSlug() {
        return null;
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
