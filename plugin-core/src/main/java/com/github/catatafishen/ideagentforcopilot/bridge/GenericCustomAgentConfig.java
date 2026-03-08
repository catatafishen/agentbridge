package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent configuration for a user-provided generic ACP command.
 * The user specifies the full CLI invocation (e.g., {@code my-agent --acp --stdio})
 * and we parse it into a ProcessBuilder.
 */
public class GenericCustomAgentConfig implements AgentConfig {

    private final GenericSettings settings;
    private final String rawCommand;
    private String resolvedBinaryPath;
    private JsonArray authMethods;

    public GenericCustomAgentConfig(@NotNull GenericSettings settings, @NotNull String rawCommand) {
        this.settings = settings;
        this.rawCommand = rawCommand;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Generic ACP";
    }

    @Override
    public @NotNull String getNotificationGroupId() {
        return "Copilot Notifications";
    }

    @Override
    public void prepareForLaunch(@Nullable String projectBasePath) {
        // No pre-launch setup for generic commands.
    }

    @Override
    public @NotNull String findAgentBinary() throws AcpException {
        List<String> tokens = parseCommand();
        if (tokens.isEmpty()) {
            throw new AcpException("No ACP command configured. Enter a command like: my-agent --acp --stdio",
                null, false);
        }
        String binary = tokens.get(0);
        File file = new File(binary);
        if (file.isAbsolute()) {
            if (!file.exists()) {
                throw new AcpException("Binary not found: " + binary, null, false);
            }
            resolvedBinaryPath = binary;
            return binary;
        }
        // Try to find in PATH
        try {
            String cmd = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            Process check = new ProcessBuilder(cmd, binary).start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) {
                    resolvedBinaryPath = path;
                    return path;
                }
            }
        } catch (Exception ignored) {
            // Fall through
        }
        // Just use the name as-is and let ProcessBuilder resolve it
        resolvedBinaryPath = binary;
        return binary;
    }

    @Override
    public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                   @Nullable String projectBasePath) {
        List<String> cmd = parseCommand();
        if (!cmd.isEmpty()) {
            cmd.set(0, binaryPath);
        }
        return new ProcessBuilder(cmd);
    }

    @Override
    public void parseInitializeResponse(@NotNull JsonObject result) {
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;
    }

    @Override
    public @Nullable String parseModelUsage(@Nullable JsonObject modelMeta) {
        return null;
    }

    @Override
    public @Nullable AuthMethod getAuthMethod() {
        return KiroAgentConfig.parseStandardAuthMethod(authMethods);
    }

    @Override
    public @Nullable String getAgentBinaryPath() {
        return resolvedBinaryPath;
    }

    private List<String> parseCommand() {
        if (rawCommand == null || rawCommand.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(rawCommand.trim().split("\\s+")));
    }
}
