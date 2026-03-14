package com.github.catatafishen.ideagentforcopilot.bridge;

import com.google.gson.JsonObject;
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
     * @param onUpdate   receives raw update JSON (tool_call, plan, etc.) (nullable)
     * @param onRequest  called each time a request is sent (nullable)
     * @return the stop reason (e.g. "end_turn", "max_tokens")
     */
    @NotNull
    String sendPrompt(@NotNull String sessionId,
                      @NotNull String prompt,
                      @Nullable String model,
                      @Nullable List<ResourceReference> references,
                      @Nullable Consumer<String> onChunk,
                      @Nullable Consumer<JsonObject> onUpdate,
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
     * {@code sessionUpdate} event type emitted by clients that support turn-level usage tracking.
     * The event carries {@code inputTokens} (int), {@code outputTokens} (int), and
     * {@code costUsd} (double) fields. Clients that do not track usage simply never emit
     * this event type. Defined here so both emitters and consumers share the same constant
     * and neither layer hard-codes a client-specific string.
     */
    String SESSION_UPDATE_TURN_USAGE = "turn_usage";

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
     * Closes and releases all resources.
     */
    @Override
    void close();
}
