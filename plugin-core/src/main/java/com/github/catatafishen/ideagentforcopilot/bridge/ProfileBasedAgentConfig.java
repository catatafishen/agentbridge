package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
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
 * Primarily intended for custom agents that the end user might want to add manually.
 */
public final class ProfileBasedAgentConfig implements AgentConfig {

    private static final Logger LOG = Logger.getInstance(ProfileBasedAgentConfig.class);
    private static final String MCP_SERVERS_KEY = "mcpServers";

    private final AgentProfile profile;
    @Nullable
    private final ToolRegistry registry;
    private String resolvedBinaryPath;
    private JsonArray authMethods;
    /**
     * Effective MCP server name — either injected ("intellij-code-tools") or detected from existing config.
     */
    private String effectiveMcpServerName = "intellij-code-tools";

    public ProfileBasedAgentConfig(@NotNull AgentProfile profile, @Nullable ToolRegistry registry) {
        this.profile = profile;
        this.registry = registry;
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
            InstructionsManager.ensureInstructions(projectBasePath, prependTarget,
                profile.getAdditionalInstructions());
        }
        List<String> bundledAgents = profile.getBundledAgentFiles();
        if (!bundledAgents.isEmpty()) {
            BundledAgentDeployer.ensureAgents(projectBasePath, bundledAgents);
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

        addNodeAndCommand(cmd, binaryPath);
        cmd.addAll(profile.getAcpArgs());
        addModelFlagIfSupported(cmd);
        addConfigDirIfSupported(cmd, projectBasePath);

        if (profile.isSupportsMcpConfigFlag() && profile.getMcpMethod() == McpInjectionMethod.CONFIG_FLAG) {
            addMcpConfigFlag(cmd, mcpPort);
        }
        if (profile.getMcpMethod() == McpInjectionMethod.MCP_LOCATION_FLAG) {
            addMcpLocationFlag(cmd, mcpPort);
        }
        if (profile.getPermissionInjectionMethod() == PermissionInjectionMethod.CLI_FLAGS) {
            addPermissionCliFlags(cmd);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (profile.getMcpMethod() == McpInjectionMethod.ENV_VAR && mcpPort > 0) {
            injectMcpViaEnvVar(pb, mcpPort);
        }
        return pb;
    }

    private void addModelFlagIfSupported(@NotNull List<String> cmd) {
        if (!profile.isSupportsModelFlag()) return;
        String savedModel = new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(getSettingsPrefix()).getSelectedModel();
        if (savedModel != null && !savedModel.isEmpty()) {
            cmd.add("--model");
            cmd.add(savedModel);
            LOG.info(profile.getDisplayName() + " model set to: " + savedModel);
        }
    }

    @Override
    public void clearSavedModel() {
        new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(getSettingsPrefix()).setSelectedModel("");
        LOG.info(profile.getDisplayName() + ": cleared saved model selection (rejected by CLI)");
    }

    private void addConfigDirIfSupported(@NotNull List<String> cmd, @Nullable String projectBasePath) {
        if (!profile.isSupportsConfigDir() || projectBasePath == null) return;
        Path agentWorkPath = Path.of(projectBasePath, ".agent-work");
        cmd.add("--config-dir");
        cmd.add(agentWorkPath.toString());
    }

    private void injectMcpViaEnvVar(@NotNull ProcessBuilder pb, int mcpPort) {
        String envVarName = profile.getMcpEnvVarName();
        if (envVarName.isEmpty()) return;
        String resolved = resolveMcpTemplate(mcpPort);
        if (resolved == null) return;
        if (profile.getPermissionInjectionMethod() == PermissionInjectionMethod.CONFIG_JSON) {
            resolved = mergePermissionsIntoConfig(resolved);
        }
        pb.environment().put(envVarName, resolved);
        LOG.info(profile.getDisplayName() + " MCP config injected via env var " + envVarName);
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
    public boolean denyBuiltInToolsViaPermissions() {
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

    @Override
    public @Nullable String getToolNameRegex() {
        return profile.getToolNameRegex();
    }

    @Override
    public @Nullable String getToolNameReplacement() {
        return profile.getToolNameReplacement();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @NotNull
    private String getSettingsPrefix() {
        return profile.getId();
    }

    @Override
    @Nullable
    public String getSessionInstructions() {
        // If this profile uses file-prepend (e.g. Copilot → .copilot/copilot-instructions.md,
        // Claude → CLAUDE.md), skip session/message injection — those agents ignore it.
        String prependTarget = profile.getPrependInstructionsTo();
        if (prependTarget != null && !prependTarget.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        try (java.io.InputStream is = getClass().getResourceAsStream("/default-startup-instructions.md")) {
            if (is != null) {
                sb.append(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } else {
                LOG.warn("Resource /default-startup-instructions.md not found in classpath");
                sb.append("You are running inside an IntelliJ IDEA plugin with IDE tools accessible via MCP.");
            }
        } catch (IOException e) {
            LOG.error("Failed to read /default-startup-instructions.md from classpath", e);
            sb.append("You are running inside an IntelliJ IDEA plugin with IDE tools accessible via MCP.");
        }
        String additional = profile.getAdditionalInstructions();
        if (!additional.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(additional);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @Override
    public boolean supportsSessionMessage() {
        return profile.isSupportsSessionMessage();
    }

    @Override
    public boolean requiresResourceDuplication() {
        return profile.isRequiresResourceDuplication();
    }

    @Override
    public boolean sendResourceReferences() {
        return profile.isSendResourceReferences();
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
     * Writes the resolved MCP config JSON as {@code mcp.json} inside a temporary directory
     * and appends {@code --mcp-location <tempDir>} to the command.
     * Used by agents (e.g. Junie) that discover MCP servers by scanning a folder for {@code mcp.json}.
     */
    private void addMcpLocationFlag(@NotNull List<String> cmd, int mcpPort) {
        if (mcpPort <= 0) {
            LOG.info("MCP port is " + mcpPort + " — skipping MCP location config");
            return;
        }
        String template = profile.getMcpConfigTemplate();
        if (template.isEmpty()) {
            LOG.info("No MCP config template — skipping MCP location config for " + profile.getDisplayName());
            return;
        }

        String resolved = resolveMcpTemplate(mcpPort);
        if (resolved == null) return;

        try {
            Path tempDir = Files.createTempDirectory("acp-mcp-loc-");
            Path configFile = tempDir.resolve("mcp.json");
            Files.writeString(configFile, resolved);
            // Register for deletion on JVM exit
            configFile.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            cmd.add("--mcp-location");
            cmd.add(tempDir.toString());
            LOG.info("MCP location config written to " + configFile);
        } catch (IOException e) {
            LOG.warn("Failed to write MCP location config file", e);
        }
    }

    @Nullable
    private String detectExistingMcpRegistration(int mcpPort) {
        String targetUrl = "http://127.0.0.1:" + mcpPort + "/mcp";
        String userHome = System.getProperty("user.home", "");
        List<Path> candidates = List.of(
            Path.of(userHome, ".copilot", "mcp-config.json"),
            Path.of(userHome, ".config", "github-copilot", "mcp.json")
        );
        for (Path configPath : candidates) {
            String found = scanConfigFileForMcpRegistration(configPath, targetUrl);
            if (found != null) {
                effectiveMcpServerName = found;
                LOG.info("MCP server already registered as '" + found + "' at port " + mcpPort
                    + " — skipping injection, using existing registration");
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static String scanConfigFileForMcpRegistration(Path configPath, String targetUrl) {
        if (!configPath.toFile().exists()) return null;
        try {
            String content = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject servers = root.has(MCP_SERVERS_KEY) && root.get(MCP_SERVERS_KEY).isJsonObject()
                ? root.getAsJsonObject(MCP_SERVERS_KEY)
                : root;
            for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject server = entry.getValue().getAsJsonObject();
                String url = server.has("url") ? server.get("url").getAsString() : "";
                if (targetUrl.equals(url)) {
                    LOG.info("Found existing MCP registration '" + entry.getKey() + "' → " + url + " in " + configPath);
                    return entry.getKey();
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not read MCP config at " + configPath, e);
        }
        return null;
    }

    /**
     * Adds {@code --allow-tool} and {@code --deny-tool} CLI flags based on plugin tool permission settings.
     * Tools set to ALLOW get {@code --allow-tool}, DENY get {@code --deny-tool}, ASK gets no flag
     * (the agent's default behavior is to prompt the user).
     */
    private void addPermissionCliFlags(@NotNull List<String> cmd) {
        if (registry == null) return;
        var settings = new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(profile.getId());
        int allowCount = 0;
        int denyCount = 0;
        for (var entry : registry.getAllTools()) {
            if (entry.isBuiltIn()) continue;
            var perm = settings.getToolPermission(entry.id());
            if (perm == com.github.catatafishen.ideagentforcopilot.services.ToolPermission.ALLOW) {
                cmd.add("--allow-tool");
                cmd.add(entry.id());
                allowCount++;
            } else if (perm == com.github.catatafishen.ideagentforcopilot.services.ToolPermission.DENY) {
                cmd.add("--deny-tool");
                cmd.add(entry.id());
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
        var permObj = new com.google.gson.JsonObject();
        if (registry == null) return permObj;
        var settings = new com.github.catatafishen.ideagentforcopilot.services.GenericSettings(profile.getId());
        for (var entry : registry.getAllTools()) {
            if (entry.isBuiltIn()) {
                if (profile.isExcludeAgentBuiltInTools()) {
                    permObj.addProperty(entry.id(), "deny");
                }
                continue;
            }
            var perm = settings.getToolPermission(entry.id());
            permObj.addProperty(entry.id(), perm.name().toLowerCase(java.util.Locale.ROOT));
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

    @Nullable
    static AuthMethod parseStandardAuthMethod(@Nullable JsonArray authMethods) {
        if (authMethods == null || authMethods.isEmpty()) return null;
        JsonObject first = authMethods.get(0).getAsJsonObject();
        AuthMethod method = new AuthMethod();
        method.setId(first.has("id") ? first.get("id").getAsString() : "");
        method.setName(first.has("name") ? first.get("name").getAsString() : "");
        method.setDescription(first.has("description") ? first.get("description").getAsString() : "");
        parseTerminalAuthFromMeta(first, method);
        return method;
    }

    private static void parseTerminalAuthFromMeta(JsonObject first, AuthMethod method) {
        if (!first.has("_meta")) return;
        JsonObject meta = first.getAsJsonObject("_meta");
        if (!meta.has("terminal-auth")) return;
        JsonObject termAuth = meta.getAsJsonObject("terminal-auth");
        method.setCommand(termAuth.has("command") ? termAuth.get("command").getAsString() : null);
        if (!termAuth.has("args")) return;
        List<String> args = new ArrayList<>();
        for (JsonElement a : termAuth.getAsJsonArray("args")) {
            args.add(a.getAsString());
        }
        method.setArgs(args);
    }
}
