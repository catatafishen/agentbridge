package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction over AI agent communication transports.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link AcpClient} — JSON-RPC over stdio to a CLI subprocess (Copilot, OpenCode, etc.)</li>
 *   <li>{@link AnthropicDirectClient} — direct HTTPS to api.anthropic.com (Claude Code)</li>
 *   <li>{@link ClaudeCliClient} — subprocess via {@code claude --print --output-format stream-json} (Claude subscription)</li>
 * </ul>
 */
public interface AgentClient extends Closeable {

    /**
     * Start the client (launch process or verify API connectivity).
     * Must be called before any other method.
     */
    void start() throws AcpException;

    /**
     * Create a new conversation session. Returns a session ID used for subsequent calls.
     *
     * @param cwd the working directory for the session (may be null)
     */
    @NotNull
    String createSession(@Nullable String cwd) throws AcpException;

    /**
     * Switch the model for an existing session.
     */
    void setModel(@NotNull String sessionId, @NotNull String modelId) throws AcpException;

    /**
     * Send a prompt and run the full agentic loop until completion.
     * Streaming text chunks are delivered via {@code onChunk}; structured update events
     * (tool calls, plans) are delivered via {@code onUpdate} as JSON objects matching
     * the {@code sessionUpdate} shape consumed by the UI layer.
     *
     * @param sessionId  session ID from {@link #createSession}
     * @param prompt     the user prompt text
     * @param model      model ID to use (null = use session default)
     * @param references optional file/selection context references
     * @param onChunk    receives text chunks for streaming display (nullable)
     * @param onUpdate   receives structured update events (tool calls, plans, etc.) (nullable)
     * @param onRequest  called each time a request is sent (nullable)
     * @return the stop reason (e.g. "end_turn", "max_tokens")
     */
    @NotNull
    String sendPrompt(@NotNull String sessionId,
                      @NotNull String prompt,
                      @Nullable String model,
                      @Nullable List<ResourceReference> references,
                      @Nullable Consumer<String> onChunk,
                      @Nullable Consumer<SessionUpdate> onUpdate,
                      @Nullable Runnable onRequest) throws AcpException;

    /**
     * List models available for this agent/account.
     */
    @NotNull
    List<Model> listModels() throws AcpException;

    /**
     * Cancel an in-progress prompt turn for the given session.
     */
    void cancelSession(@NotNull String sessionId);

    /**
     * Returns true if the client is healthy and ready to accept requests.
     */
    boolean isHealthy();

    /**
     * Whether file/selection resource references need their content duplicated as plain text
     * in the prompt (some agents don't inline resource content natively).
     * Default: {@code false} — override for agents that require it (e.g. GitHub Copilot CLI).
     */
    default boolean requiresResourceContentDuplication() {
        return false;
    }

    /**
     * Register a listener that is called when a tool requires user permission.
     * Default: no-op — override for agents that support interactive permission prompts.
     */
    default void setPermissionRequestListener(@Nullable Consumer<PermissionRequest> listener) {
        // no-op
    }

    /**
     * Notify the client that a sub-agent (Task tool call) is active or has ended.
     * Default: no-op — override for clients that gate tool calls during sub-agent execution.
     */
    default void setSubAgentActive(boolean active) {
        // no-op
    }

    /**
     * Returns the authentication method info for this agent, or null if not applicable.
     * Default: {@code null}.
     */
    @Nullable
    default AuthMethod getAuthMethod() {
        return null;
    }

    /**
     * All recognised {@code sessionUpdate} event types.
     *
     * <p>Every {@code sessionUpdate} JSON object carries a {@code sessionUpdate} field whose
     * value matches one of these entries.  Using an enum prevents string literals from
     * drifting out of sync between emitters and consumers.
     */
    enum SessionUpdateType {
        /**
         * A new agent tool call has started.
         */
        TOOL_CALL("tool_call"),
        /**
         * An in-progress tool call has completed or failed.
         */
        TOOL_CALL_UPDATE("tool_call_update"),
        /**
         * A streaming text chunk from the ACP agent message.
         */
        AGENT_MESSAGE_CHUNK("agent_message_chunk"),
        /**
         * A streaming text chunk (synonym for agent_message_chunk).
         */
        MESSAGE_CHUNK("message_chunk"),
        /**
         * A streaming text chunk (synonym for agent_message_chunk).
         */
        TEXT_CHUNK("text_chunk"),
        /**
         * A streaming reasoning/thinking chunk from the model.
         */
        AGENT_THOUGHT("agent_thought_chunk"),
        /**
         * Turn-level token/cost usage.  Carries {@code inputTokens} (int),
         * {@code outputTokens} (int), and {@code costUsd} (double).
         */
        TURN_USAGE("turn_usage"),
        /**
         * Agent-initiated banner notification.  Carries:
         * <ul>
         *   <li>{@code message} — human-readable text</li>
         *   <li>{@code level} — {@code "warning"} (yellow) or {@code "error"} (red)</li>
         *   <li>{@code clearOn} — {@code "next_success"} to re-show until a successful
         *       turn clears it, or {@code "manual"} to show once (default)</li>
         * </ul>
         */
        BANNER("banner"),
        /**
         * ACP plan update.  Carries a list of plan entries via {@link SessionUpdate.Plan}.
         */
        PLAN("plan");

        private final String wireValue;

        SessionUpdateType(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the string value used in the {@code sessionUpdate} JSON field.
         */
        public String value() {
            return wireValue;
        }

        /**
         * Returns the enum constant for the given wire value, or {@code null} if unrecognised.
         */
        @Nullable
        public static SessionUpdateType fromString(@Nullable String value) {
            if (value == null) return null;
            for (SessionUpdateType t : values()) {
                if (t.wireValue.equals(value)) return t;
            }
            return null;
        }
    }

    /**
     * Whether this client supports per-model premium-request multipliers.
     *
     * <p>When {@code true} (e.g. GitHub Copilot), a multiplier chip is shown on each
     * prompt and used in billing calculations. When {@code false} (e.g. Claude CLI),
     * token counts and cost are shown instead.
     * Default: {@code false}.</p>
     */
    default boolean supportsMultiplier() {
        return false;
    }

    /**
     * Returns the pricing multiplier label for the given model ID (e.g. "1x", "2x").
     * Only called when {@link #supportsMultiplier()} returns {@code true}.
     * Default: {@code "1x"} — override for agents with tiered model pricing.
     */
    @NotNull
    default String getModelMultiplier(@NotNull String modelId) {
        return "1x";
    }

    /**
     * Returns session option descriptors that this client supports beyond model selection.
     * Each descriptor results in a dropdown rendered in the chat toolbar.
     * Default: empty — override for clients that support additional options (e.g. effort level).
     */
    @NotNull
    default List<SessionOption> listSessionOptions() {
        return List.of();
    }

    /**
     * Apply a session option value for the given session.
     * Implementations store the value so it is used on the next prompt invocation.
     * Default: no-op.
     */
    default void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        // no-op
    }

    /**
     * Checks whether this agent is authenticated and ready to accept prompts.
     *
     * <p>The default implementation calls {@link #listModels()} as a connectivity/auth probe,
     * which works for ACP-based transports. Implementations whose auth check differs
     * (e.g. credential-file transports, API-key transports) should override this.</p>
     *
     * @return {@code null} if authenticated and ready, or a human-readable error message otherwise
     */
    @Nullable
    default String checkAuthentication() {
        try {
            listModels();
            return null;
        } catch (AcpException e) {
            return e.getMessage() != null ? e.getMessage() : "Failed to connect to agent";
        }
    }

    /**
     * Returns the default project file shortcuts for this agent.
     * Each entry describes a file path and display label that appears in the Project Files dropdown menu.
     * Implementations should organize files under their agent's group name.
     *
     * <p>Example:
     * <pre>
     *   new FileEntry("Settings", ".agent-work/claude/settings.json", false, "Claude"),
     *   new FileEntry("Instructions", ".github/copilot-instructions.md", false, "Claude"),
     * </pre>
     * <p>
     * Default: empty — override for agents that define default project files.
     *
     * @return list of default file entries for this agent
     */
    @NotNull
    default List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        return List.of();
    }

    /**
     * Closes and releases all resources.
     */
    @Override
    void close();
}
