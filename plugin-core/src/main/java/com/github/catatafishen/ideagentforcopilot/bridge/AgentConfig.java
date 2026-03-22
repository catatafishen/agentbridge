package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.agent.AgentException;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Strategy interface for agent-specific configuration and lifecycle.
 * Each ACP-compatible agent (Copilot CLI, Claude Code, Codex CLI, etc.)
 * provides an implementation that handles binary discovery, authentication,
 * model metadata parsing, and pre-launch setup.
 *
 * <p>The generic {@link AcpClient} delegates all agent-specific concerns
 * to this interface, keeping the JSON-RPC protocol layer agent-agnostic.</p>
 */
public interface AgentConfig {

    /**
     * Human-readable agent name for logs and notifications (e.g., "Copilot", "Claude Code").
     */
    @NotNull
    String getDisplayName();

    /**
     * IntelliJ notification group ID for status notifications.
     */
    @NotNull
    String getNotificationGroupId();

    /**
     * Pre-launch setup (e.g., ensure instruction files exist).
     * Called before the agent process is started.
     */
    void prepareForLaunch(@Nullable String projectBasePath);

    /**
     * Find the agent binary on the system.
     *
     * @return absolute path to the agent binary
     * @throws AgentException if the binary cannot be found
     */
    @NotNull
    String findAgentBinary() throws AgentException;

    /**
     * Build the ProcessBuilder for launching the agent in ACP mode.
     *
     * @param binaryPath      path returned by {@link #findAgentBinary()}
     * @param projectBasePath project root (for config-dir, working directory)
     * @param mcpPort         port the MCP HTTP server listens on (for stdio proxy)
     * @return configured ProcessBuilder ready to start
     * @throws AgentException if the command cannot be built
     */
    @NotNull
    ProcessBuilder buildAcpProcess(@NotNull String binaryPath, @Nullable String projectBasePath,
                                   int mcpPort) throws AgentException;

    /**
     * Extract agent-specific data from the ACP {@code initialize} response.
     * Called once after the handshake completes.
     */
    void parseInitializeResponse(@NotNull JsonObject result);

    /**
     * Extract a usage/cost multiplier string from model metadata (e.g., "1x", "3x").
     *
     * @param modelMeta the {@code _meta} object from a model entry, or null
     * @return usage string, or null if not available
     */
    @Nullable
    String parseModelUsage(@Nullable JsonObject modelMeta);

    /**
     * Get the authentication method info parsed from the last initialize response.
     *
     * @return auth method, or null if not available or not applicable
     */
    @Nullable
    AuthMethod getAuthMethod();

    /**
     * Get the resolved path to the agent binary (for external commands like login/logout).
     */
    @Nullable
    String getAgentBinaryPath();

    /**
     * Returns the path (relative to project root) where agent definition files ({@code *.md}) live.
     * When non-null, an agent selector dropdown is shown; selecting an agent prepends
     * {@code @agent-name } to the prompt. {@code null} = no dropdown shown.
     */
    @Nullable
    default String getAgentsDirectory() {
        return null;
    }

    /**
     * Whether ACP {@code ResourceReference} objects need their content duplicated as
     * plain text in the prompt. GitHub Copilot surfaces resource references as
     * metadata-only (path + line count) without inlining the content for the model,
     * so the plugin appends the text as a workaround. Agents that honour structured
     * resource references natively should return {@code false}.
     *
     * @return {@code true} if resource content must be appended as plain text
     */
    default boolean requiresResourceContentDuplication() {
        return false;
    }

    /**
     * Returns the permission injection method for this agent.
     * Controls how per-tool ALLOW/ASK/DENY settings are communicated to the agent process.
     */
    @NotNull
    default com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod getPermissionInjectionMethod() {
        return com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod.NONE;
    }

    /**
     * Returns the name under which the plugin's MCP server is registered for this agent session.
     * Normally {@code "agentbridge"} (the injected server name), but may differ if the
     * user has pre-registered the server under a different name in the agent's persistent config.
     * Used to strip the server-name prefix from incoming tool-call names when resolving tool IDs.
     */
    @NotNull
    default String getEffectiveMcpServerName() {
        return "agentbridge";
    }

    /**
     * Returns a regex pattern for mapping tool names before they reach the UI.
     * Useful for generic ACP clients where the tool name format is unknown.
     */
    @Nullable
    default String getToolNameRegex() {
        return null;
    }

    /**
     * Returns the replacement string for the tool name regex.
     */
    @Nullable
    default String getToolNameReplacement() {
        return null;
    }

    /**
     * Whether resource content (file references) must be duplicated in the text prompt.
     * Some agents (e.g. Copilot CLI, OpenCode) don't process ACP resource references natively,
     * so the plugin inlines the content directly into the prompt text.
     */
    default boolean requiresResourceDuplication() {
        return false;
    }

    /**
     * Whether to send resource references in the prompt array.
     * When {@code true}, file references are sent as structured ACP resource blocks.
     * When {@code false}, resource references are skipped (content is already inlined via
     * {@link #requiresResourceDuplication()}).
     * Defaults to {@code true} for backwards compatibility.
     */
    default boolean sendResourceReferences() {
        return true;
    }

    /**
     * Whether this agent supports {@code session/message} JSON-RPC notifications.
     * When {@code true}, startup instructions and retry guidance are sent via {@code session/message}.
     * When {@code false}, those messages are skipped (agent reads instructions from config files or MCP prompt).
     * Defaults to {@code true} for backwards compatibility (Junie, Copilot support it).
     */
    default boolean supportsSessionMessage() {
        return true;
    }

    /**
     * Returns startup instructions to inject into the conversation via {@code session/message}
     * after session creation. This is the preferred mechanism for agents that process
     * in-conversation messages (e.g. Junie). Return {@code null} to skip.
     *
     * <p>Agents that ignore {@code session/message} (e.g. Copilot CLI, Claude Code, OpenCode) use
     * file-prepend via {@link InstructionsManager} instead — controlled by
     * {@link com.github.catatafishen.ideagentforcopilot.services.AgentProfile#getPrependInstructionsTo()}.
     * The two mechanisms are mutually exclusive per profile.</p>
     */
    @Nullable
    default String getSessionInstructions() {
        return null;
    }

    /**
     * Clears the persisted model selection for this agent.
     * Called when the agent process rejects the saved {@code --model} flag on startup,
     * so the next restart attempt launches without a model flag and can connect successfully.
     * Default: no-op (agents that don't support a --model flag don't need this).
     */
    default void clearSavedModel() {
        // no-op
    }

    /**
     * Returns the MCP config template (JSON string) to use for injection.
     * Supported placeholders: {javaPath}, {mcpJarPath}, {mcpPort}.
     */
    @NotNull
    default String getMcpConfigTemplate() {
        return "";
    }

    /**
     * Returns the MCP server name to use in the injected config.
     */
    @NotNull
    default String getMcpServerName() {
        return "agentbridge";
    }

    /**
     * Returns whether the agent requires MCP server registration in mcpServers array of session/new.
     */
    default boolean requiresMcpInSessionNew() {
        return false;
    }
}
