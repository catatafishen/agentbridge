package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AcpException;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.AuthMethod;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decorator that overrides binary discovery and process building with a user-provided
 * command string, while delegating all other agent-specific concerns (display name,
 * notification group, init parsing, auth, modes, tool config) to the wrapped config.
 *
 * <p>Used when the user edits the start command in the connect panel away from
 * the agent's default.</p>
 */
final class CommandOverrideAgentConfig implements AgentConfig {

    private final AgentConfig delegate;
    private final String rawCommand;
    private String resolvedBinaryPath;

    CommandOverrideAgentConfig(@NotNull AgentConfig delegate, @NotNull String rawCommand) {
        this.delegate = delegate;
        this.rawCommand = rawCommand;
    }

    @Override
    public @NotNull String getDisplayName() {
        return delegate.getDisplayName();
    }

    @Override
    public @NotNull String getNotificationGroupId() {
        return delegate.getNotificationGroupId();
    }

    @Override
    public void prepareForLaunch(@Nullable String projectBasePath) {
        delegate.prepareForLaunch(projectBasePath);
    }

    @Override
    public @NotNull String findAgentBinary() throws AcpException {
        List<String> tokens = parseCommand();
        if (tokens.isEmpty()) {
            throw new AcpException("No start command configured.", null, false);
        }
        String binary = tokens.getFirst();
        if (new File(binary).isAbsolute() && !new File(binary).exists()) {
            throw new AcpException("Binary not found: " + binary, null, false);
        }
        resolvedBinaryPath = binary;
        return binary;
    }

    @Override
    public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                   @Nullable String projectBasePath,
                                                   int mcpPort) {
        List<String> cmd = parseCommand();
        if (!cmd.isEmpty()) {
            cmd.set(0, binaryPath);
        }
        return new ProcessBuilder(cmd);
    }

    @Override
    public void parseInitializeResponse(@NotNull JsonObject result) {
        delegate.parseInitializeResponse(result);
    }

    @Override
    public @Nullable String parseModelUsage(@Nullable JsonObject modelMeta) {
        return delegate.parseModelUsage(modelMeta);
    }

    @Override
    public @Nullable AuthMethod getAuthMethod() {
        return delegate.getAuthMethod();
    }

    @Override
    public @Nullable String getAgentBinaryPath() {
        return resolvedBinaryPath;
    }

    @Override
    public @Nullable String getAgentsDirectory() {
        return delegate.getAgentsDirectory();
    }

    @Override
    public boolean requiresResourceContentDuplication() {
        return delegate.requiresResourceContentDuplication();
    }

    @Override
    public @NotNull com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod getPermissionInjectionMethod() {
        return delegate.getPermissionInjectionMethod();
    }

    @Override
    public @NotNull String getEffectiveMcpServerName() {
        return delegate.getEffectiveMcpServerName();
    }

    @Override
    public @Nullable String getToolNameRegex() {
        return delegate.getToolNameRegex();
    }

    @Override
    public @Nullable String getToolNameReplacement() {
        return delegate.getToolNameReplacement();
    }

    @Override
    public boolean requiresResourceDuplication() {
        return delegate.requiresResourceDuplication();
    }

    @Override
    public boolean sendResourceReferences() {
        return delegate.sendResourceReferences();
    }

    @Override
    public boolean supportsSessionMessage() {
        return delegate.supportsSessionMessage();
    }

    @Override
    public @Nullable String getSessionInstructions() {
        return delegate.getSessionInstructions();
    }

    @Override
    public void clearSavedModel() {
        delegate.clearSavedModel();
    }

    @Override
    public @Nullable String getMcpConfigTemplate() {
        return delegate.getMcpConfigTemplate();
    }

    @Override
    public @NotNull String getMcpServerName() {
        return delegate.getMcpServerName();
    }

    @Override
    public boolean requiresMcpInSessionNew() {
        return delegate.requiresMcpInSessionNew();
    }

    private List<String> parseCommand() {
        if (rawCommand.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(rawCommand.trim().split("\\s+")));
    }
}
