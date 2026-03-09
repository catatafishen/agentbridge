package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic {@link AgentConfig} implementation driven entirely by an {@link AgentProfile}.
 * Replaces all agent-specific config classes (CopilotAgentConfig, ClaudeAgentConfig,
 * OpenCodeAgentConfig, etc.) with a single data-driven implementation.
 */
public final class ProfileBasedAgentConfig implements AgentConfig {

    private static final Logger LOG = Logger.getInstance(ProfileBasedAgentConfig.class);

    private final AgentProfile profile;
    private String resolvedBinaryPath;
    private JsonArray authMethods;

    public ProfileBasedAgentConfig(@NotNull AgentProfile profile) {
        this.profile = profile;
    }

    @Override
    public @NotNull String getDisplayName() {
        return profile.getDisplayName();
    }

    @Override
    public @NotNull String getNotificationGroupId() {
        return "Copilot Notifications";
    }

    @Override
    public void prepareForLaunch(@Nullable String projectBasePath) {
        if (profile.isEnsureCopilotInstructions()) {
            CopilotInstructionsManager.ensureInstructions(projectBasePath);
        }
        if (profile.isEnsureCopilotAgents()) {
            CopilotAgentsManager.ensureAgents(projectBasePath);
        }
        if (profile.isEnsureClaudeInstructions()) {
            ClaudeInstructionsManager.ensureInstructions(projectBasePath);
        }
    }

    @Override
    public @NotNull String findAgentBinary() throws AcpException {
        // 1. User-provided custom path takes priority
        String customPath = profile.getCustomBinaryPath();
        if (!customPath.isEmpty()) {
            File custom = new File(customPath);
            if (custom.exists()) {
                resolvedBinaryPath = customPath;
                return customPath;
            }
            throw new AcpException(profile.getDisplayName() + " binary not found at: " + customPath,
                null, false);
        }

        // 2. Search PATH and common locations for the primary binary name
        String binaryName = profile.getBinaryName();
        if (!binaryName.isEmpty()) {
            String found = searchForBinary(binaryName);
            if (found != null) {
                resolvedBinaryPath = found;
                return found;
            }
        }

        // 3. Try alternate names
        for (String altName : profile.getAlternateNames()) {
            String found = searchForBinary(altName);
            if (found != null) {
                resolvedBinaryPath = found;
                return found;
            }
        }

        String hint = profile.getInstallHint().isEmpty()
            ? "Ensure it is installed and available on your PATH."
            : profile.getInstallHint();
        throw new AcpException(profile.getDisplayName() + " CLI not found. " + hint, null, false);
    }

    @Override
    public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                   @Nullable String projectBasePath,
                                                   int mcpPort) throws AcpException {
        resolvedBinaryPath = binaryPath;
        List<String> cmd = new ArrayList<>();

        // Resolve NVM-managed node for the binary
        addNodeAndCommand(cmd, binaryPath);

        // ACP activation args
        cmd.addAll(profile.getAcpArgs());

        // Model flag
        if (profile.isSupportsModelFlag()) {
            String savedModel = getSettingsPrefix() != null
                ? new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(profile.getId()).getSelectedModel()
                : null;
            if (savedModel != null && !savedModel.isEmpty()) {
                cmd.add("--model");
                cmd.add(savedModel);
                LOG.info(profile.getDisplayName() + " model set to: " + savedModel);
            }
        }

        // Config dir
        if (profile.isSupportsConfigDir() && projectBasePath != null) {
            Path agentWorkPath = Path.of(projectBasePath, ".agent-work");
            cmd.add("--config-dir");
            cmd.add(agentWorkPath.toString());
        }

        // MCP injection via --additional-mcp-config flag
        if (profile.isSupportsMcpConfigFlag() && profile.getMcpMethod() == McpInjectionMethod.CONFIG_FLAG) {
            addMcpConfigFlag(cmd, mcpPort);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);

        // MCP injection via environment variable
        if (profile.getMcpMethod() == McpInjectionMethod.ENV_VAR && mcpPort > 0) {
            String envVarName = profile.getMcpEnvVarName();
            if (!envVarName.isEmpty()) {
                String resolved = resolveMcpTemplate(mcpPort);
                if (resolved != null) {
                    pb.environment().put(envVarName, resolved);
                    LOG.info(profile.getDisplayName() + " MCP config injected via env var " + envVarName);
                }
            }
        }

        return pb;
    }

    @Override
    public void parseInitializeResponse(@NotNull JsonObject result) {
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;
    }

    @Override
    public @Nullable String parseModelUsage(@Nullable JsonObject modelMeta) {
        String field = profile.getModelUsageField();
        if (field != null && !field.isEmpty() && modelMeta != null && modelMeta.has(field)) {
            return modelMeta.get(field).getAsString();
        }
        return null;
    }

    @Override
    public @Nullable AuthMethod getAuthMethod() {
        return parseStandardAuthMethod(authMethods);
    }

    @Override
    public @Nullable String getAgentBinaryPath() {
        return resolvedBinaryPath;
    }

    @Override
    public @NotNull List<AgentMode> getSupportedModes() {
        return profile.getSupportedModes();
    }

    @Override
    public boolean requiresResourceContentDuplication() {
        return profile.isRequiresResourceDuplication();
    }

    @Override
    public boolean shouldExcludeBuiltInTools() {
        return profile.isExcludeAgentBuiltInTools();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Nullable
    private String getSettingsPrefix() {
        return profile.getId();
    }

    /**
     * Search for a binary by name on PATH and common installation locations.
     */
    @Nullable
    private String searchForBinary(@NotNull String binaryName) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Check PATH
        try {
            String cmd = isWindows ? "where" : "which";
            Process check = new ProcessBuilder(cmd, binaryName).start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) {
                    LOG.info("Found " + binaryName + " in PATH: " + path);
                    return path;
                }
            }
        } catch (IOException e) {
            LOG.debug("Failed to check for " + binaryName + " in PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check common Unix locations
        if (!isWindows) {
            String found = checkUnixLocations(binaryName);
            if (found != null) return found;
        }

        return null;
    }

    @Nullable
    private static String checkUnixLocations(@NotNull String binaryName) {
        String home = System.getProperty("user.home");
        List<String> candidates = new ArrayList<>();

        // NVM-managed node installations
        addNvmCandidates(home, binaryName, candidates);

        candidates.add(home + "/.local/bin/" + binaryName);
        candidates.add("/usr/local/bin/" + binaryName);
        candidates.add(home + "/.npm-global/bin/" + binaryName);
        candidates.add(home + "/.yarn/bin/" + binaryName);
        candidates.add("/opt/homebrew/bin/" + binaryName);
        candidates.add(home + "/go/bin/" + binaryName);

        for (String path : candidates) {
            if (new File(path).exists()) {
                LOG.info("Found " + binaryName + " at: " + path);
                return path;
            }
        }
        return null;
    }

    private static void addNvmCandidates(@NotNull String home, @NotNull String binaryName,
                                         @NotNull List<String> candidates) {
        File nvmDir = new File(home, ".nvm/versions/node");
        if (nvmDir.isDirectory()) {
            File[] nodeDirs = nvmDir.listFiles(File::isDirectory);
            if (nodeDirs != null) {
                java.util.Arrays.sort(nodeDirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File nodeDir : nodeDirs) {
                    candidates.add(new File(nodeDir, "bin/" + binaryName).getAbsolutePath());
                }
            }
        }
    }

    /**
     * If the binary is NVM-managed, prepend the corresponding node binary to the command.
     */
    private void addNodeAndCommand(@NotNull List<String> cmd, @NotNull String binaryPath) {
        String binaryName = profile.getBinaryName();
        if (binaryPath.contains("/.nvm/versions/node/") && binaryPath.contains("/bin/")) {
            String nodeDir = binaryPath.substring(0, binaryPath.lastIndexOf("/bin/"));
            String nodePath = nodeDir + "/bin/node";
            if (new File(nodePath).exists()) {
                cmd.add(nodePath);
            }
        }
        cmd.add(binaryPath);
    }

    /**
     * Writes the MCP config template to a temp file and adds --additional-mcp-config flag.
     */
    private void addMcpConfigFlag(@NotNull List<String> cmd, int mcpPort) {
        if (mcpPort <= 0) {
            LOG.info("MCP port is " + mcpPort + " — skipping MCP config");
            return;
        }

        String template = profile.getMcpConfigTemplate();
        if (template.isEmpty()) {
            LOG.info("No MCP config template — skipping MCP config for " + profile.getDisplayName());
            return;
        }

        String resolved = resolveMcpTemplate(mcpPort);
        if (resolved == null) return;

        try {
            File configFile = File.createTempFile("acp-mcp-", ".json");
            configFile.deleteOnExit();
            try (FileWriter fw = new FileWriter(configFile)) {
                fw.write(resolved);
            }
            cmd.add("--additional-mcp-config");
            cmd.add("@" + configFile.getAbsolutePath());
            LOG.info("MCP config written to " + configFile.getAbsolutePath());
        } catch (IOException e) {
            LOG.warn("Failed to write MCP config file", e);
        }
    }

    /**
     * Resolves placeholders in the MCP config template.
     * Placeholders: {mcpPort}, {mcpJarPath}, {javaPath}
     */
    @Nullable
    private String resolveMcpTemplate(int mcpPort) {
        String template = profile.getMcpConfigTemplate();
        if (template.isEmpty()) return null;

        String resolved = template.replace("{mcpPort}", String.valueOf(mcpPort));

        if (resolved.contains("{mcpJarPath}")) {
            String jarPath = McpServerJarLocator.findMcpServerJar();
            if (jarPath == null) {
                LOG.warn("MCP server JAR not found — IntelliJ tools will be unavailable for "
                    + profile.getDisplayName());
                return null;
            }
            resolved = resolved.replace("{mcpJarPath}", jarPath);
        }

        if (resolved.contains("{javaPath}")) {
            String javaPath = resolveJavaPath();
            if (javaPath == null) {
                LOG.warn("Java binary not found — IntelliJ tools will be unavailable for "
                    + profile.getDisplayName());
                return null;
            }
            resolved = resolved.replace("{javaPath}", javaPath);
        }

        return resolved;
    }

    @Nullable
    private static String resolveJavaPath() {
        String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
        return new File(javaPath).exists() ? javaPath : null;
    }

    /**
     * Parses auth method from the standard ACP authMethods array.
     * Works for all known agents (Copilot, Claude, Kiro, OpenCode, etc.).
     */
    @Nullable
    static AuthMethod parseStandardAuthMethod(@Nullable JsonArray authMethods) {
        if (authMethods == null || authMethods.isEmpty()) return null;
        JsonObject first = authMethods.get(0).getAsJsonObject();
        AuthMethod method = new AuthMethod();
        method.setId(first.has("id") ? first.get("id").getAsString() : "");
        method.setName(first.has("name") ? first.get("name").getAsString() : "");
        method.setDescription(first.has("description") ? first.get("description").getAsString() : "");
        if (first.has("_meta")) {
            JsonObject meta = first.getAsJsonObject("_meta");
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
        return method;
    }
}
