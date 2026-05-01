package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.agent.AgentException;
import com.github.catatafishen.agentbridge.agent.claude.BundledAgentDeployer;
import com.github.catatafishen.agentbridge.agent.claude.InstructionsManager;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.GenericSettings;
import com.github.catatafishen.agentbridge.services.McpInjectionMethod;
import com.github.catatafishen.agentbridge.services.PermissionInjectionMethod;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.settings.BinaryDetector;
import com.github.catatafishen.agentbridge.settings.ProfileBinaryDetector;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.github.catatafishen.agentbridge.settings.StartupInstructionsSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
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
public class ProfileBasedAgentConfig implements AgentConfig {

    private static final Logger LOG = Logger.getInstance(ProfileBasedAgentConfig.class);
    private static final String MCP_SERVERS_KEY = "mcpServers";

    private final AgentProfile profile;
    @Nullable
    private final ToolRegistry registry;
    @Nullable
    private final Project project;
    private String resolvedBinaryPath;
    private JsonArray authMethods;
    /**
     * Effective MCP server name — either injected ("agentbridge") or detected from existing config.
     */
    private String effectiveMcpServerName = "agentbridge";

    public ProfileBasedAgentConfig(@NotNull AgentProfile profile, @Nullable ToolRegistry registry) {
        this(profile, registry, null);
    }

    public ProfileBasedAgentConfig(@NotNull AgentProfile profile,
                                   @Nullable ToolRegistry registry,
                                   @Nullable Project project) {
        this.profile = profile;
        this.registry = registry;
        this.project = project;
    }

    /**
     * Creates the appropriate {@link ProfileBasedAgentConfig} subclass for the given profile.
     * Returns an {@link OpenCodeAgentConfig} for OpenCode, or a base {@code ProfileBasedAgentConfig}
     * for all other agents.
     */
    public static ProfileBasedAgentConfig create(@NotNull AgentProfile profile,
                                                 @Nullable ToolRegistry registry,
                                                 @Nullable Project project) {
        if (OpenCodeAgentConfig.PROFILE_ID.equals(profile.getId())) {
            return new OpenCodeAgentConfig(profile, registry, project);
        }
        return new ProfileBasedAgentConfig(profile, registry, project);
    }

    // ── Extension hooks for agent-specific subclasses ────────────────────────

    /**
     * Called at the end of {@link #buildAcpProcess} to allow subclasses to perform
     * agent-specific process configuration (e.g. writing config files, setting env vars).
     * Default implementation is a no-op.
     */
    protected void configureProcess(@NotNull ProcessBuilder pb,
                                    @Nullable String projectBasePath,
                                    int mcpPort) {
        // No-op by default
    }

    /**
     * Returns additional config file paths to check for existing MCP server registrations.
     * Appended to the default list ({@code ~/.copilot/mcp-config.json}, etc.) in
     * {@link #detectExistingMcpRegistration}.
     */
    protected @NotNull List<Path> getAdditionalMcpConfigPaths() {
        return List.of();
    }

    /**
     * Returns the JSON key that contains MCP server definitions in config files.
     * Default is {@code "mcpServers"}; OpenCode overrides to {@code "mcp"}.
     */
    protected @NotNull String getMcpContainerKey() {
        return MCP_SERVERS_KEY;
    }

    /**
     * Returns agent-specific native tool names that should be denied in generated configs.
     * Only applied when {@link AgentProfile#isExcludeAgentBuiltInTools()} is {@code true}.
     * Default returns an empty list.
     */
    protected @NotNull List<String> getNativeToolDenyList() {
        return List.of();
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
            String agentsDir = profile.getAgentsDirectory();
            if (agentsDir != null && !agentsDir.isEmpty()) {
                BundledAgentDeployer.ensureAgents(projectBasePath, agentsDir, bundledAgents);
            } else {
                BundledAgentDeployer.ensureAgents(projectBasePath, bundledAgents);
            }
        }
    }

    @Override
    public @NotNull String findAgentBinary() throws AgentException {
        // 1. User-provided custom path takes priority; validate it exists
        String customPath = profile.getCustomBinaryPath();
        if (!customPath.isEmpty()) {
            File custom = new File(customPath);
            if (custom.exists()) {
                resolvedBinaryPath = customPath;
                return customPath;
            }
            throw new AgentException(profile.getDisplayName() + " binary not found at: " + customPath,
                null, false);
        }

        // 2. Auto-detect primary binary name and alternates
        ProfileBinaryDetector detector =
            new ProfileBinaryDetector(profile);
        String binaryName = profile.getBinaryName();
        if (!binaryName.isEmpty()) {
            String found = detector.resolve(binaryName,
                profile.getAlternateNames().toArray(String[]::new));
            if (found != null) {
                resolvedBinaryPath = found;
                return found;
            }
        } else {
            // No primary name - try alternates only
            for (String altName : profile.getAlternateNames()) {
                String found = BinaryDetector.findBinaryPath(altName);
                if (found != null) {
                    resolvedBinaryPath = found;
                    return found;
                }
            }
        }

        String hint = profile.getInstallHint().isEmpty()
            ? "Ensure it is installed and available on your PATH."
            : profile.getInstallHint();
        throw new AgentException(profile.getDisplayName() + " CLI not found. " + hint, null, false);
    }

    @Override
    @SuppressWarnings("RedundantThrows") // Required by AgentConfig interface, other implementations do throw
    public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                   @Nullable String projectBasePath,
                                                   int mcpPort) throws AgentException {
        resolvedBinaryPath = binaryPath;
        List<String> cmd = new ArrayList<>();

        addNodeAndCommand(cmd, binaryPath);
        cmd.addAll(profile.getAcpArgs());
        addModelFlagIfSupported(cmd);

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

        // Inject captured shell environment (includes nvm, sdkman, etc.)
        pb.environment().putAll(ShellEnvironment.getEnvironment());

        // Set agent-specific config directory environment variables
        setAgentConfigDirEnvVars(pb, projectBasePath);

        // Agent-specific process configuration (e.g. OpenCode writes its own config file)
        configureProcess(pb, projectBasePath, mcpPort);

        return pb;
    }

    private void addModelFlagIfSupported(@NotNull List<String> cmd) {
        if (!profile.isSupportsModelFlag()) return;
        String savedModel = getSettings().getSelectedModel();
        if (savedModel != null && !savedModel.isEmpty()) {
            cmd.add("--model");
            cmd.add(savedModel);
            LOG.info(profile.getDisplayName() + " model set to: " + savedModel);
        }
    }

    @Override
    public void clearSavedModel() {
        getSettings().setSelectedModel("");
        LOG.info(profile.getDisplayName() + ": cleared saved model selection (rejected by CLI)");
    }

    @Override
    public @NotNull String getMcpConfigTemplate() {
        return profile.getMcpConfigTemplate();
    }

    /**
     * Sets agent-specific config directory environment variables for agents that require them.
     * Each agent gets its own subdirectory under .agent-work/<agent-id>/
     */
    private void setAgentConfigDirEnvVars(@NotNull ProcessBuilder pb, @Nullable String projectBasePath) {
        configureAgentEnvironment(pb.environment(), projectBasePath);
    }

    /**
     * Configures agent-specific environment variables for the given environment map.
     * This can be used both for ACP agent processes and for auth commands.
     *
     * <p>Most agents use their standard home directories (e.g. {@code ~/.copilot/},
     * {@code ~/.claude/}) without environment overrides. Only agents that require
     * non-standard config injection (like OpenCode) need entries here.</p>
     *
     * @param environment     The environment map to configure (e.g., from ProcessBuilder)
     * @param projectBasePath The project base path, null if not available
     */
    public void configureAgentEnvironment(@NotNull Map<String, String> environment, @Nullable String projectBasePath) {
        // No-op by default — subclasses can override for agent-specific env vars.
        // Most agents (copilot, claude, kiro, junie) use their standard home directories
        // without environment overrides.
    }

    /**
     * Configures environment variables for login/auth commands.
     *
     * <p>Now a no-op: agents use their standard home directories ({@code ~/.copilot/},
     * {@code ~/.claude/}) so no environment overrides are needed for authentication.</p>
     */
    public void configureLoginCommandEnvironment(@NotNull Map<String, String> environment, @Nullable String projectBasePath) {
        // No-op: all agents use standard home directories for authentication.
    }

    /**
     * Format JSON with pretty printing, falling back to raw content if formatting fails.
     */
    @NotNull
    protected String formatJsonSafely(@NotNull String json) {
        try {
            return new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(JsonParser.parseString(json));
        } catch (Exception e) {
            LOG.warn("Failed to format JSON (invalid JSON?), using raw content. JSON: " + json, e);
            return json;
        }
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
    public @NotNull PermissionInjectionMethod getPermissionInjectionMethod() {
        return profile.getPermissionInjectionMethod();
    }

    @Override
    public @NotNull String getEffectiveMcpServerName() {
        return effectiveMcpServerName;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @NotNull
    private GenericSettings getSettings() {
        return project != null ? new GenericSettings(profile.getId(), project) : new GenericSettings(profile.getId());
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
        sb.append(StartupInstructionsSettings.getInstance().getInstructions());
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
    public boolean sendResourceReferences() {
        return profile.isSendResourceReferences();
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
            Path configFile = createPrivateMcpConfigFile("acp-mcp-", ".json");
            Files.writeString(configFile, resolved);
            configFile.toFile().deleteOnExit();
            cmd.add("--additional-mcp-config");
            cmd.add("@" + configFile.toAbsolutePath());
            LOG.info("MCP config written to " + configFile.toAbsolutePath());
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
            Path tempDir = createPrivateMcpConfigDirectory("acp-mcp-loc-");
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

    private Path createPrivateMcpConfigFile(@NotNull String prefix, @NotNull String suffix) throws IOException {
        Path configDir = resolvePrivateMcpConfigDir();
        Files.createDirectories(configDir);
        return Files.createTempFile(configDir, prefix, suffix);
    }

    private Path createPrivateMcpConfigDirectory(@NotNull String prefix) throws IOException {
        Path configDir = resolvePrivateMcpConfigDir();
        Files.createDirectories(configDir);
        return Files.createTempDirectory(configDir, prefix);
    }

    private Path resolvePrivateMcpConfigDir() {
        if (project != null) {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isBlank()) {
                return Path.of(basePath, ".agent-work", "mcp-configs");
            }
        }
        return Path.of(System.getProperty("user.home"), ".agentbridge", "mcp-configs");
    }

    @Nullable
    private String detectExistingMcpRegistration(int mcpPort) {
        String targetUrl = "http://127.0.0.1:" + mcpPort + "/mcp";
        String userHome = System.getProperty("user.home", "");
        List<Path> candidates = new ArrayList<>(List.of(
            Path.of(userHome, ".copilot", "mcp-config.json"),
            Path.of(userHome, ".config", "github-copilot", "mcp.json")
        ));

        // For OpenCode, also check ~/.config/opencode/opencode.json
        candidates.addAll(getAdditionalMcpConfigPaths());

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
    private String scanConfigFileForMcpRegistration(Path configPath, String targetUrl) {
        if (!configPath.toFile().exists()) return null;
        try {
            String content = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            // Subclasses may override getMcpContainerKey() (e.g. OpenCode uses "mcp")
            String mcpKey = getMcpContainerKey();

            JsonObject servers;
            if (root.has(mcpKey) && root.get(mcpKey).isJsonObject()) {
                servers = root.getAsJsonObject(mcpKey);
            } else if (root.has(MCP_SERVERS_KEY) && root.get(MCP_SERVERS_KEY).isJsonObject()) {
                servers = root.getAsJsonObject(MCP_SERVERS_KEY);
            } else {
                servers = root;
            }

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
        var settings = getSettings();
        int allowCount = 0;
        int denyCount = 0;
        for (var entry : registry.getAllTools()) {
            if (entry.isBuiltIn()) continue;
            var perm = settings.getToolPermission(entry.id());
            if (perm == ToolPermission.ALLOW) {
                cmd.add("--allow-tool");
                cmd.add(entry.id());
                allowCount++;
            } else if (perm == ToolPermission.DENY) {
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
    protected String mergePermissionsIntoConfig(@NotNull String configJson) {
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

    @NotNull
    private com.google.gson.JsonObject buildPermissionJsonObject() {
        var permObj = new com.google.gson.JsonObject();

        // Subclasses may deny their agent's native built-in tools
        if (profile.isExcludeAgentBuiltInTools()) {
            for (String nativeTool : getNativeToolDenyList()) {
                permObj.addProperty(nativeTool, "deny");
            }
        }

        if (registry == null) return permObj;
        var settings = getSettings();
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
    protected String resolveMcpTemplate(int mcpPort) {
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

    @Override
    public boolean requiresMcpInSessionNew() {
        return profile.getMcpMethod() == McpInjectionMethod.SESSION_NEW;
    }

    @Override
    public @NotNull String getMcpServerName() {
        return profile.getMcpServerName();
    }

}
