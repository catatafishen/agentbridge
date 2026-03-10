package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic {@link AgentConfig} implementation driven entirely by an {@link AgentProfile}.
 * Replaces all agent-specific config classes (CopilotAgentConfig, ClaudeAgentConfig,
 * OpenCodeAgentConfig, etc.) with a single data-driven implementation.
 */
public final class ProfileBasedAgentConfig implements AgentConfig {

    private static final Logger LOG = Logger.getInstance(ProfileBasedAgentConfig.class);
    private static final String MCP_SERVERS_KEY = "mcpServers";

    private final AgentProfile profile;
    private String resolvedBinaryPath;
    private JsonArray authMethods;
    /**
     * Effective MCP server name — either injected ("intellij-code-tools") or detected from existing config.
     */
    private String effectiveMcpServerName = "intellij-code-tools";

    public ProfileBasedAgentConfig(@NotNull AgentProfile profile) {
        this.profile = profile;
    }

    @Override
    public @NotNull String getDisplayName() {
        return profile.getDisplayName();
    }

    @Override
    public @NotNull String getNotificationGroupId() {
        return "AgentBridge Notifications";
    }

    @Override
    public void prepareForLaunch(@Nullable String projectBasePath) {
        String prependTarget = profile.getPrependInstructionsTo();
        if (prependTarget != null && !prependTarget.isEmpty()) {
            InstructionsManager.ensureInstructions(projectBasePath, prependTarget);
        }
        if (profile.isEnsureCopilotAgents()) {
            CopilotAgentsManager.ensureAgents(projectBasePath);
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

        // Permission injection via CLI flags (e.g., Copilot CLI --allow-tool / --deny-tool)
        if (profile.getPermissionInjectionMethod() == PermissionInjectionMethod.CLI_FLAGS) {
            addPermissionCliFlags(cmd);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);

        // MCP injection via environment variable
        if (profile.getMcpMethod() == McpInjectionMethod.ENV_VAR && mcpPort > 0) {
            String envVarName = profile.getMcpEnvVarName();
            if (!envVarName.isEmpty()) {
                String resolved = resolveMcpTemplate(mcpPort);
                if (resolved != null) {
                    // For CONFIG_JSON permission injection, merge permissions into the JSON
                    if (profile.getPermissionInjectionMethod() == PermissionInjectionMethod.CONFIG_JSON) {
                        resolved = mergePermissionsIntoConfig(resolved);
                    }
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
    public @Nullable String getAgentsDirectory() {
        return profile.getAgentsDirectory();
    }

    @Override
    public boolean requiresResourceContentDuplication() {
        return profile.isRequiresResourceDuplication();
    }

    @Override
    public boolean shouldExcludeBuiltInTools() {
        return profile.isExcludeAgentBuiltInTools();
    }

    @Override
    public @NotNull PermissionInjectionMethod getPermissionInjectionMethod() {
        return profile.getPermissionInjectionMethod();
    }

    @Override
    public @NotNull String getEffectiveMcpServerName() {
        return effectiveMcpServerName;
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
            return checkUnixLocations(binaryName);
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
        if (binaryPath.contains("/.nvm/versions/node/") && binaryPath.contains("/bin/")) {
            String nodeDir = binaryPath.substring(0, binaryPath.lastIndexOf("/bin/"));
            String nodePath = nodeDir + "/bin/node";
            if (new File(nodePath).exists()) {
                cmd.add(nodePath);
            }
        }
        cmd.add(binaryPath);
    }

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

        // Check if the MCP server is already registered in a persistent agent config pointing to
        // our port. If so, skip injection to avoid a duplicate connection under a different name.
        String existingName = detectExistingMcpRegistration(mcpPort);
        if (existingName != null) {
            effectiveMcpServerName = existingName;
            LOG.info("MCP server already registered as '" + existingName + "' at port " + mcpPort
                + " — skipping injection, using existing registration");
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
     * Scan known agent persistent MCP config files for an entry whose URL already points to
     * {@code http://127.0.0.1:{mcpPort}/mcp}. Returns the registered server name if found,
     * or {@code null} if no match is detected.
     *
     * <p>Checked locations (in order):
     * <ul>
     *   <li>{@code ~/.copilot/mcp-config.json} — Copilot CLI persistent MCP config</li>
     *   <li>{@code ~/.config/github-copilot/mcp.json} — alternative Copilot config path</li>
     * </ul>
     * Each file is expected to have a {@code mcpServers} object (or entries at the root) where
     * each key is the server name and each value has a {@code "url"} field.
     */
    @Nullable
    private String detectExistingMcpRegistration(int mcpPort) {
        String targetUrl = "http://127.0.0.1:" + mcpPort + "/mcp";
        String userHome = System.getProperty("user.home", "");

        List<Path> candidates = List.of(
            Path.of(userHome, ".copilot", "mcp-config.json"),
            Path.of(userHome, ".config", "github-copilot", "mcp.json")
        );

        for (Path configPath : candidates) {
            if (!configPath.toFile().exists()) continue;
            try {
                String content = Files.readString(configPath);
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();

                // Support both nested and flat MCP server config layouts
                JsonObject servers = root.has(MCP_SERVERS_KEY) && root.get(MCP_SERVERS_KEY).isJsonObject()
                    ? root.getAsJsonObject(MCP_SERVERS_KEY)
                    : root;

                for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject server = entry.getValue().getAsJsonObject();
                    String url = server.has("url") ? server.get("url").getAsString() : "";
                    if (targetUrl.equals(url)) {
                        LOG.info("Found existing MCP registration '" + entry.getKey()
                            + "' → " + url + " in " + configPath);
                        return entry.getKey();
                    }
                }
            } catch (Exception e) {
                LOG.debug("Could not read MCP config at " + configPath, e);
            }
        }
        return null;
    }

    /**
     * Adds {@code --allow-tool} and {@code --deny-tool} CLI flags based on plugin tool permission settings.
     * Tools set to ALLOW get {@code --allow-tool}, DENY get {@code --deny-tool}, ASK gets no flag
     * (the agent's default behavior is to prompt the user).
     */
    private void addPermissionCliFlags(@NotNull List<String> cmd) {
        var settings = new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(profile.getId());
        int allowCount = 0;
        int denyCount = 0;
        for (var entry : com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.getAllTools()) {
            if (entry.isBuiltIn) continue;
            var perm = settings.getToolPermission(entry.id);
            if (perm == com.github.catatafishen.ideagentforcopilot.services.ToolPermission.ALLOW) {
                cmd.add("--allow-tool");
                cmd.add(entry.id);
                allowCount++;
            } else if (perm == com.github.catatafishen.ideagentforcopilot.services.ToolPermission.DENY) {
                cmd.add("--deny-tool");
                cmd.add(entry.id);
                denyCount++;
            }
        }
        if (allowCount > 0 || denyCount > 0) {
            LOG.info("Permission CLI flags: " + allowCount + " allowed, " + denyCount + " denied");
        }
    }

    /**
     * Merges a {@code "permission"} block into an existing JSON config string.
     * Reads per-tool permissions from plugin settings and adds them as
     * {@code "permission": {"toolId": "allow|ask|deny", ...}}.
     */
    @NotNull
    private String mergePermissionsIntoConfig(@NotNull String configJson) {
        try {
            var parsed = com.google.gson.JsonParser.parseString(configJson).getAsJsonObject();
            var permObj = buildPermissionJsonObject();
            if (!permObj.isEmpty()) {
                parsed.add("permission", permObj);
                LOG.info("Merged " + permObj.size() + " tool permissions into agent config JSON");
            }
            return new com.google.gson.Gson().toJson(parsed);
        } catch (Exception e) {
            LOG.warn("Failed to merge permissions into config JSON — using original", e);
            return configJson;
        }
    }

    /**
     * Builds a JSON object mapping tool IDs to their permission mode (allow/ask/deny).
     * Includes all non-built-in tools with their configured permissions.
     * When {@code excludeAgentBuiltInTools} is enabled, also adds "deny" for every
     * built-in tool so the CONFIG_JSON block enforces the exclusion at the agent level.
     */
    @NotNull
    private com.google.gson.JsonObject buildPermissionJsonObject() {
        var settings = new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(profile.getId());
        var permObj = new com.google.gson.JsonObject();
        for (var entry : com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.getAllTools()) {
            if (entry.isBuiltIn) {
                if (profile.isExcludeAgentBuiltInTools()) {
                    permObj.addProperty(entry.id, "deny");
                }
                continue;
            }
            var perm = settings.getToolPermission(entry.id);
            permObj.addProperty(entry.id, perm.name().toLowerCase(java.util.Locale.ROOT));
        }
        return permObj;
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
