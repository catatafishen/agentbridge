package com.github.catatafishen.ideagentforcopilot.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent configuration for Claude Code CLI.
 * Handles Claude-specific binary discovery, authentication parsing,
 * and model metadata.
 */
public class ClaudeAgentConfig implements AgentConfig {

    private String resolvedBinaryPath;
    private JsonArray authMethods;

    @Override
    public @NotNull String getDisplayName() {
        return "Claude";
    }

    @Override
    public @NotNull String getNotificationGroupId() {
        return "Copilot Notifications";
    }

    @Override
    public void prepareForLaunch(@Nullable String projectBasePath) {
        ClaudeInstructionsManager.ensureInstructions(projectBasePath);
    }

    @Override
    public @NotNull String findAgentBinary() throws AcpException {
        String path = ClaudeCliLocator.findClaudeCli();
        resolvedBinaryPath = path;
        return path;
    }

    @Override
    public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                   @Nullable String projectBasePath) throws AcpException {
        resolvedBinaryPath = binaryPath;
        return ClaudeCliLocator.buildAcpCommand(binaryPath, projectBasePath);
    }

    @Override
    public void parseInitializeResponse(@NotNull JsonObject result) {
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;
    }

    @Override
    public @Nullable String parseModelUsage(@Nullable JsonObject modelMeta) {
        // Claude does not expose a usage multiplier in model metadata (yet).
        // Return null to indicate no usage data available.
        return null;
    }

    @Override
    public @Nullable AuthMethod getAuthMethod() {
        if (authMethods == null || authMethods.isEmpty()) return null;
        JsonObject first = authMethods.get(0).getAsJsonObject();
        AuthMethod method = new AuthMethod();
        method.setId(first.has("id") ? first.get("id").getAsString() : "");
        method.setName(first.has("name") ? first.get("name").getAsString() : "");
        method.setDescription(first.has("description") ? first.get("description").getAsString() : "");
        parseTerminalAuth(first, method);
        return method;
    }

    @Override
    public @Nullable String getAgentBinaryPath() {
        return resolvedBinaryPath;
    }

    /**
     * Claude Code honours structured resource references natively —
     * no need to duplicate content as plain text.
     */
    @Override
    public boolean requiresResourceContentDuplication() {
        return false;
    }

    private void parseTerminalAuth(JsonObject jsonObject, AuthMethod method) {
        if (jsonObject.has("_meta")) {
            JsonObject meta = jsonObject.getAsJsonObject("_meta");
            if (meta.has("terminal-auth")) {
                JsonObject termAuth = meta.getAsJsonObject("terminal-auth");
                method.setCommand(termAuth.has("command") ? termAuth.get("command").getAsString() : null);
                if (termAuth.has("args")) {
                    List<String> args = new ArrayList<>();
                    for (JsonElement a : termAuth.getAsJsonArray("args")) {
                        args.add(a.getAsString());
                    }
                    method.setArgs(args);
                }
            }
        }
    }
}
