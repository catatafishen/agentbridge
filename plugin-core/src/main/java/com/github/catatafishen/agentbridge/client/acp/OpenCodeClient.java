package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.github.catatafishen.agentbridge.services.AutopilotPolicy;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.net.NetUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * OpenCode ACP client.
 * <p>
 * Command: {@code opencode acp}
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * References: requires inline (no ACP resource blocks)
 */
public final class OpenCodeClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(OpenCodeClient.class);
    private static final String AGENT_ID = "opencode";
    private static final String BUILD_AGENT = "build";
    private static final String PLAN_AGENT = "plan";
    private static final String GENERAL_AGENT = "general";
    private static final String EXPLORE_AGENT = "explore";
    private static final String MCP_SERVER_NAME = "agentbridge";
    private volatile boolean autopilot;
    private static final String SCOUT_AGENT = "scout";
    private static final String PROJECT_AGENT_DIR = ".opencode/agent";
    private static final String PROJECT_AGENTS_DIR = ".opencode/agents";
    private static final String DEPLOYED_AGENT_DIR = ".agent-work/opencode/agent";

    /**
     * OpenCode version probed during {@link #finalizeCommand}, before the process is spawned.
     * {@link #buildEnvironment} runs before the ACP {@code initialize} handshake, so the version
     * reported by the agent itself is not available yet — the probe result is the only source.
     */
    private volatile String probedVersion;

    private static final String KEY_RAW_INPUT = "rawInput";
    private static final List<String> NATIVE_TOOLS_TO_DENY = List.of(
        "grep", "glob", "ls", "read", "write", "edit", "patch", "bash",
        "lsp", "websearch", "webfetch", "codesearch", "todoread", "todowrite"
    );

    /**
     * V2 permission actions to deny, in v2's own action names. V2 renamed or folded the v1
     * tool names: {@code bash} became {@code shell}, the single {@code edit} action now covers
     * the edit, write, and patch tools, and {@code ls}/{@code lsp}/{@code codesearch}/
     * {@code todoread}/{@code todowrite} are not v2 actions at all. {@code question} is denied
     * so agents ask the user through the plugin's {@code prompt_user} MCP tool instead of
     * OpenCode's native question tool.
     */
    private static final List<String> V2_ACTIONS_TO_DENY = List.of(
        "read", "edit", "glob", "grep", "shell", "websearch", "webfetch", "question"
    );

    static List<String> nativeToolsToDeny() {
        return NATIVE_TOOLS_TO_DENY;
    }

    static List<String> v2ActionsToDeny() {
        return V2_ACTIONS_TO_DENY;
    }

    /**
     * Address of this client's own OpenCode HTTP server. Per client, not global: every project runs
     * its own OpenCode process on its own port.
     */
    private final OpenCodeServerEndpoint serverEndpoint = new OpenCodeServerEndpoint();

    /**
     * {@code false} when the running OpenCode is known to have no reachable HTTP server (2.x), as
     * opposed to a server that should be there but whose address is lost. Decides whether a missing
     * address is an expected "no transcript" or an error worth showing.
     */
    private volatile boolean serverReachableByDesign = true;

    public OpenCodeClient(Project project) {
        super(project);
    }

    /**
     * Test constructor allowing transport injection, mirroring
     * {@link AcpClient#AcpClient(Project, JsonRpcTransport)}. Package-private:
     * production code must use {@link #OpenCodeClient(Project)}.
     */
    OpenCodeClient(Project project, JsonRpcTransport transport) {
        super(project, transport);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    @Override
    public @Nullable String defaultAgentSlug() {
        return BUILD_AGENT;
    }

    @Override
    public boolean supportsModelGrouping() {
        return true;
    }

    /**
     * OpenCode v2 exposes its session modes ({@code build}, {@code plan}) as the {@code mode}
     * config option, and a mode switch applies to the live session via
     * {@code session/set_config_option(configId=mode)} (verified against v2.0.27: the agent's
     * self-reported mode changes in-session). Verified NOT switchable: v2 rejects
     * {@code general}/{@code explore} with {@code -32602 mode not found} (they are Task-tool
     * subagents, not primary modes), and v1 has no config-option channel, so both keep the
     * process-restart flow.
     */
    @Override
    public boolean canSwitchAgentLive(@Nullable String slug) {
        return isOpenCodeV2OrLater() && isSelectableOpenCodeMode(slug);
    }

    /**
     * Whether {@code slug} is one of the mode values OpenCode advertised in its {@code mode}
     * config option (kept fresh by the set_config_option echo). Only advertised values are
     * valid arguments to a mode switch.
     */
    private boolean isSelectableOpenCodeMode(@Nullable String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        return getAvailableConfigOptions().stream()
            .filter(o -> "mode".equals(o.id()))
            .flatMap(o -> o.values().stream())
            .anyMatch(v -> slug.equals(v.id()));
    }

    /**
     * Pushes an agent selection made while a session is live to OpenCode v2 via the {@code mode}
     * config option (the echo application keeps local option state in sync). No-op on v1 or when
     * there is no active session (the selection is then applied by {@link #onSessionCreated}).
     */
    @Override
    protected void onAgentSlugChanged(@Nullable String slug) {
        if (!canSwitchAgentLive(slug)) {
            return;
        }
        String sessionId = getCurrentSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            setConfigOption(sessionId, "mode", slug);
        }
    }

    /**
     * Applies an agent selection stored before the session existed (the process-restart flow
     * persists it, and {@code ActiveAgentManager} re-applies it on start). Sends the stored slug
     * as the session mode when it differs from the mode the session already runs.
     */
    @Override
    protected void onSessionCreated(String sessionId) {
        if (!isOpenCodeV2OrLater()) {
            return;
        }
        String slug = getCurrentAgentSlug();
        if (!isSelectableOpenCodeMode(slug)) {
            return;
        }
        String currentMode = getAvailableConfigOptions().stream()
            .filter(o -> "mode".equals(o.id()))
            .map(o -> o.selectedValueId())
            .filter(v -> v != null && !v.isBlank())
            .findFirst().orElse(null);
        if (!slug.equals(currentMode)) {
            setConfigOption(sessionId, "mode", slug);
        }
    }

    @Override
    public List<AbstractClient.AgentMode> getAvailableAgents() {
        // The exclusion set always covers all five built-in slugs, including the three
        // dropped from the v2 menu: on v2+ this client injects its own inline explore,
        // so a user's explore.md on disk is deliberately NOT listed (the plugin owns
        // that entry), and general/explore/scout files cannot shadow what v2 itself
        // treats as built-ins. v1 keeps the full list, where the same exclusion
        // protects the built-ins from disk discoveries.
        Set<String> builtInSlugs = Set.of(BUILD_AGENT, PLAN_AGENT, GENERAL_AGENT, EXPLORE_AGENT, SCOUT_AGENT);
        List<AbstractClient.AgentMode> projectAgents = List.of();
        String basePath = project.getBasePath();
        if (basePath != null) {
            projectAgents = ProjectAgentScanner.scanAgentDirectories(
                Path.of(basePath),
                builtInSlugs,
                PROJECT_AGENT_DIR,
                PROJECT_AGENTS_DIR,
                DEPLOYED_AGENT_DIR
            );
        }
        return mergeAgents(
            builtInAgentsFor(probedVersion),
            ProjectAgentScanner.scanGlobalAgents(globalOpenCodeAgentDirs(), builtInSlugs),
            projectAgents
        );
    }

    /**
     * The user-level OpenCode agents directories, in OpenCode's own precedence order
     * (an earlier directory wins on slug collisions): v2 reads both {@code
     * ~/.opencode/agents} and the documented XDG location {@code ~/.config/opencode/agents},
     * with the former overriding the latter on collisions (verified live on v2.0.27).
     */
    static List<Path> globalOpenCodeAgentDirs() {
        Path home = Path.of(System.getProperty("user.home"));
        return List.of(home.resolve(".opencode/agents"), home.resolve(".config/opencode/agents"));
    }

    /**
     * Merges the dropdown's three agent sources: built-ins, user-level global agents
     * ({@code ~/.opencode/agents}) and project-scanned agents. OpenCode's own precedence
     * applies: a project agent with the same slug as a global one wins (project config
     * overrides global), and built-ins are never shadowed (the scanners exclude their
     * slugs, and {@code putIfAbsent} guards them again here). Without the global scan the
     * Agent menu omits user-level custom agents even though OpenCode still advertises
     * them as mode values, which leaks near-duplicate Mode and session-option sections
     * into the dropdown.
     */
    static List<AbstractClient.AgentMode> mergeAgents(List<AbstractClient.AgentMode> builtIns,
                                                      List<AbstractClient.AgentMode> globalAgents,
                                                      List<AbstractClient.AgentMode> projectAgents) {
        Map<String, AbstractClient.AgentMode> merged = new LinkedHashMap<>();
        Set<String> builtInSlugs = new HashSet<>();
        for (AbstractClient.AgentMode agent : builtIns) {
            builtInSlugs.add(agent.slug());
            merged.putIfAbsent(agent.slug(), agent);
        }
        for (AbstractClient.AgentMode agent : globalAgents) merged.putIfAbsent(agent.slug(), agent);
        // Project wins over global on slug collisions, but never over a built-in.
        for (AbstractClient.AgentMode agent : projectAgents) {
            if (!builtInSlugs.contains(agent.slug())) merged.put(agent.slug(), agent);
        }
        return new ArrayList<>(merged.values());
    }

    static List<AbstractClient.AgentMode> builtInAgents() {
        return List.of(
            new AbstractClient.AgentMode(BUILD_AGENT, "Build", "Default primary agent with full tool access"),
            new AbstractClient.AgentMode(PLAN_AGENT, "Plan", "Read-only planning mode with guarded edits and bash"),
            new AbstractClient.AgentMode(GENERAL_AGENT, "General", "General-purpose subagent for complex tasks"),
            new AbstractClient.AgentMode(EXPLORE_AGENT, "Explore", "Fast read-only subagent for codebase exploration"),
            new AbstractClient.AgentMode(SCOUT_AGENT, "Scout", "Read-only subagent for external docs and dependency research")
        );
    }

    /**
     * The built-in agent list for the probed OpenCode version. On v2, the only built-in
     * PRIMARY agents are the session modes {@code build} and {@code plan}: the {@code mode}
     * config option (the switchable primary surface on v2) advertises exactly those, plus
     * custom project agents from {@code .opencode/agents}. {@code general} and {@code explore}
     * are Task-tool subagents (spawned by the model, not selectable as primaries: v2 rejects
     * them with {@code -32602 mode not found}), and {@code scout} does not exist at all on v2
     * (v2 docs: "V2 has no built-in scout agent"). Advertising any of the three in the Agent
     * menu puts a phantom entry there that cannot be launched, so all are dropped whenever
     * the version is known to be v2+. The explore subagent itself stays available to the
     * model via the inline config this client injects, and project-scanned custom agents
     * remain listed. Unknown or v1 versions keep the full list (conservative default).
     */
    static List<AbstractClient.AgentMode> builtInAgentsFor(@Nullable String probedVersion) {
        List<AbstractClient.AgentMode> agents = builtInAgents();
        if (!isDefinitelyOpenCodeV2(probedVersion)) return agents;
        Set<String> nonPrimaryOnV2 = Set.of(SCOUT_AGENT, GENERAL_AGENT, EXPLORE_AGENT);
        return agents.stream()
            .filter(a -> !nonPrimaryOnV2.contains(a.slug()))
            .toList();
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // On Windows, opencode is installed via npm and the native binary is not on PATH.
        // Probe the project-local node_modules path as a fallback.
        String windowsPath = resolveWindowsOpenCodePath(cwd);
        List<String> cmd = new java.util.ArrayList<>(List.of(windowsPath != null ? windowsPath : AGENT_ID, "acp"));
        AgentProfile profile = AgentProfileManager.getInstance().getProfile(AGENT_ID);
        if (profile != null) {
            cmd.addAll(profile.parsedExtraCliArgs());
        }
        return cmd;
    }

    /**
     * Pins the port of the HTTP server that {@code opencode acp} runs beside ACP, so subagent
     * transcripts can be read from it.
     * <ul>
     *   <li>An explicit {@code --port} from the profile's extra arguments is respected as is.</li>
     *   <li>OpenCode 1.x accepts {@code --port}, so a free port is chosen and appended.</li>
     *   <li>OpenCode 2.x's {@code acp} command has no {@code --port} flag and exits on it, so
     *       nothing is appended and transcripts are unavailable. Its HTTP server is started internally
     *       with a random port and password that only the {@code acp} process knows.</li>
     * </ul>
     * An undeterminable version is treated like 2.x: the agent must launch, and a missing
     * transcript is the lesser harm (the reason is logged).
     *
     * @param installedVersion supplies the installed OpenCode version, or {@code null} if unknown;
     *                         only consulted when there is no explicit {@code --port}
     */
    void pinServerPort(List<String> cmd, Supplier<@Nullable String> installedVersion) {
        serverReachableByDesign = true;
        Integer explicitPort = OpenCodeServerEndpoint.portFromArgs(cmd);
        if (explicitPort != null) {
            serverEndpoint.publish(explicitPort);
            return;
        }
        String version = installedVersion.get();
        if (isOpenCodeV2OrLater(version)) {
            serverEndpoint.clear();
            serverReachableByDesign = false;
            LOG.info("OpenCode " + (version != null ? version : "(unknown version)")
                + ": `opencode acp` has no --port flag, so subagent transcripts are unavailable");
            return;
        }
        try {
            int port = NetUtils.findAvailableSocketPort();
            cmd.add("--port");
            cmd.add(String.valueOf(port));
            serverEndpoint.publish(port);
        } catch (IOException e) {
            // The transcript feature is optional: launch OpenCode on its own random port, but make
            // the missing subagent conversation explainable from the log.
            serverEndpoint.clear();
            LOG.warn("No free port for the OpenCode HTTP server; subagent transcripts are unavailable", e);
        }
    }

    @Override
    protected void afterStop() {
        // The port belonged to the process that has just been destroyed.
        serverEndpoint.clear();
    }

    /**
     * Looks up an environment variable the way the OpenCode process saw it at launch: the captured
     * login-shell environment is laid over the IDE's own (see {@code AcpClient.launchProcess}). A
     * GUI-launched IDE does not inherit variables set in shell startup files, so reading only
     * {@link System#getenv} would miss e.g. {@code OPENCODE_SERVER_PASSWORD}.
     */
    static @Nullable String launchEnvironmentVariable(@NotNull Map<String, String> shellEnvironment, @NotNull String name) {
        String fromShell = shellEnvironment.get(name);
        return fromShell != null ? fromShell : System.getenv(name);
    }

    /**
     * On Windows, opencode is shipped as a native binary inside its npm package and is not
     * added to PATH by default. Probes the project-local {@code node_modules} tree for the
     * {@code opencode-windows-x64} binary bundled by {@code opencode-ai}.
     *
     * <p>Package-private and static so unit tests can call it directly without an
     * IntelliJ application context.</p>
     *
     * @param projectBasePath the project root directory, or {@code null} if unavailable
     * @return absolute path to {@code opencode.exe}, or {@code null} if not found or not on Windows
     */
    @Nullable
    static String resolveWindowsOpenCodePath(@Nullable String projectBasePath) {
        if (!SystemInfo.isWindows) {
            return null;
        }
        if (projectBasePath == null || projectBasePath.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(projectBasePath,
            "node_modules", "opencode-ai", "node_modules", "opencode-windows-x64", "bin", "opencode.exe");
        if (Files.isRegularFile(candidate)) {
            return candidate.toString();
        }
        return null;
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        return buildInitConfig(probedVersion, mcpPort);
    }

    @Override
    protected List<String> finalizeCommand(List<String> resolvedCommand) {
        List<String> cmd = new ArrayList<>(resolvedCommand);
        probedVersion = OpenCodeVersionProbe.probe(resolvedCommand.getFirst());
        pinServerPort(cmd, () -> probedVersion);
        return cmd;
    }

    /**
     * Builds the {@code OPENCODE_CONFIG_CONTENT} environment variable: the native-tool deny list,
     * plus — on a confirmed OpenCode v2+ — an inline {@code agents} entry defining the read-only
     * {@code explore} subagent.
     *
     * <p>Why inline: v2 no longer reads agent markdown files from the directories the plugin used
     * to deploy (the bundled {@code agents/opencode/ide-explore.md} was never deployed to a v2
     * location, so the "Explore" entry advertised in the agent dropdown had no backend definition
     * and every task started with it failed). v2's config schema supports inline {@code agents}
     * keyed by slug, merged late via {@code OPENCODE_CONFIG_CONTENT}, so the definition travels
     * with the process launch and needs no files in the user's project.</p>
     *
     * <p>Only a <em>confirmed</em> v2 version gets the {@code agents} key: an unknown version must
     * not risk breaking a v1 install that may reject unknown config keys — an unresolvable version
     * leaves the config exactly as it was before this change.</p>
     *
     * <p>A confirmed v2 also gets the deny list as a v2 {@code permissions} ruleset with v2's
     * action names. The v1-style {@code permission} map is deprecated in v2 and half of its tool
     * names are not v2 actions, so the ruleset is what actually denies on v2; the map stays for
     * v1 and unknown versions.</p>
     *
     * <p>A confirmed v2 also declares the AgentBridge MCP server under {@code mcp.servers} so it
     * is part of the launch config and survives OpenCode's instance reloads, which drop servers
     * registered only through {@code session/new}. {@code oauth} is disabled because the endpoint
     * is a local loopback server that uses no credentials.</p>
     *
     * <p>NOTE: We do NOT set {@code "default_agent"} here. OpenCode v1.4.10+ rejects
     * subagent slugs (like "build", "plan", "explore") as the {@code default_agent} value,
     * causing {@code session/new} to fail with
     * {@code "default agent \"build\" is a subagent"}. OpenCode selects its own default
     * agent internally — the plugin's agent dropdown controls which agent to start via
     * the session/create flow instead.</p>
     */
    static Map<String, String> buildInitConfig(@Nullable String installedVersion, int mcpPort) {
        JsonObject config = new JsonObject();
        config.add("permission", permissionDenyConfig());
        if (isDefinitelyOpenCodeV2(installedVersion)) {
            config.add("permissions", v2PermissionDenyRules());
            if (mcpPort > 0) {
                config.add("mcp", v2McpServersConfig(mcpPort));
            }
            JsonObject explore = inlineExploreAgent();
            if (explore != null) {
                JsonObject agents = new JsonObject();
                agents.add(EXPLORE_AGENT, explore);
                config.add("agents", agents);
            }
        }
        return Map.of("OPENCODE_CONFIG_CONTENT", new Gson().toJson(config));
    }

    /**
     * The v2 {@code mcp} config object registering the AgentBridge loopback endpoint as a
     * remote server with OAuth disabled (it has no credentials to exchange).
     */
    private static JsonObject v2McpServersConfig(int mcpPort) {
        JsonObject agentbridge = new JsonObject();
        agentbridge.addProperty("type", "remote");
        agentbridge.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        agentbridge.addProperty("oauth", false);
        JsonObject servers = new JsonObject();
        servers.add(MCP_SERVER_NAME, agentbridge);
        JsonObject mcp = new JsonObject();
        mcp.add("servers", servers);
        return mcp;
    }

    /** True only when the version is known and its major is ≥ 2 (unknown versions are NOT v2 here). */
    private static boolean isDefinitelyOpenCodeV2(@Nullable String version) {
        int[] parsed = KiroClient.parseVersion(version);
        return parsed != null && parsed[0] >= 2;
    }

    /**
     * The read-only plugin MCP tools the explore subagent may call (v2 names MCP tools with
     * underscores and gates them under the {@code execute} permission).
     */
    private static final List<String> EXPLORE_ALLOWED_MCP_TOOLS = List.of(
        "agentbridge_read_file", "agentbridge_search_text", "agentbridge_search_symbols",
        "agentbridge_list_project_files", "agentbridge_get_file_outline", "agentbridge_find_references",
        "agentbridge_go_to_declaration", "agentbridge_get_type_hierarchy", "agentbridge_find_implementations",
        "agentbridge_get_call_hierarchy", "agentbridge_get_class_outline", "agentbridge_get_documentation",
        "agentbridge_git_status", "agentbridge_git_diff", "agentbridge_git_log", "agentbridge_git_blame",
        "agentbridge_get_problems", "agentbridge_get_compilation_errors"
    );

    /** Mutating plugin MCP tools the explore subagent is explicitly denied. */
    private static final List<String> EXPLORE_DENIED_MCP_TOOLS = List.of(
        "agentbridge_write_file", "agentbridge_edit_text", "agentbridge_delete_file",
        "agentbridge_git_commit", "agentbridge_git_stage", "agentbridge_run_command",
        "agentbridge_build_project"
    );

    /**
     * Builds the v2 inline definition of the {@code explore} subagent: read-only native tools
     * allowed, mutating native tools denied, the plugin's read-only MCP tools allowed under
     * {@code execute}, and the bundled explore prompt as {@code system}. No {@code model} key —
     * the subagent inherits the session model.
     *
     * @return the agent object, or null when the bundled prompt resource cannot be loaded (in
     * which case the config stays as before — the agent simply is not defined inline)
     */
    private static @Nullable JsonObject inlineExploreAgent() {
        String prompt = bundledExplorePrompt();
        if (prompt == null) {
            return null;
        }
        JsonObject agent = new JsonObject();
        agent.addProperty("description", "Fast read-only subagent for codebase exploration");
        agent.addProperty("mode", "subagent");

        // v2 Permission.Ruleset: an ORDERED array of {action, resource, effect} rules
        // (packages/schema/src/permission.ts). Evaluation is last-match-wins; agent rules
        // are appended AFTER the built-in explore's policy and the top-level config rules
        // (verified live on opencode v2.0.27 via `opencode debug agents` run with the
        // exact injected config): our appended rules win for the actions they cover, and
        // the built-in explore's catch-all deny keeps uncovered MCP/other actions blocked.
        // The top-level native-tool denies this client also injects land after the
        // built-in explore's read-only allows, so the subagent's native read/grep/glob
        // end up denied as well: it reaches the codebase exclusively through the
        // plugin's MCP tools under execute. The rule list carves out:
        //  - execute: the only reachability path for MCP tools in v2 Code Mode
        //  - the plugin's 18 read-only agentbridge_* tools allowed
        //  - the plugin's 7 mutating agentbridge_* tools denied (defense in depth)
        //  - the mutating native actions denied under their v2 names (edit covers the
        //    write and patch tools; shell beats the built-in explore's surviving shell
        //    allow), plus question so the subagent cannot ask the user outside the
        //    plugin's tools
        JsonArray permissions = new JsonArray();
        permissions.add(permissionRule("execute", "allow"));
        for (String tool : EXPLORE_ALLOWED_MCP_TOOLS) {
            permissions.add(permissionRule(tool, "allow"));
        }
        for (String tool : EXPLORE_DENIED_MCP_TOOLS) {
            permissions.add(permissionRule(tool, "deny"));
        }
        for (String tool : List.of("edit", "shell", "question")) {
            permissions.add(permissionRule(tool, "deny"));
        }
        agent.add("permissions", permissions);

        // v2 ConfigAgent.Info.system is a plain string.
        agent.addProperty("system", prompt);
        return agent;
    }

    private static JsonObject permissionRule(String action, String effect) {
        JsonObject rule = new JsonObject();
        rule.addProperty("action", action);
        rule.addProperty("resource", "*");
        rule.addProperty("effect", effect);
        return rule;
    }

    /**
     * The markdown body (frontmatter stripped) of the bundled
     * {@code agents/opencode/ide-explore.md} prompt.
     */
    private static @Nullable String bundledExplorePrompt() {
        try (InputStream in = OpenCodeClient.class.getResourceAsStream("/agents/opencode/ide-explore.md")) {
            if (in == null) {
                return null;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return stripFrontmatter(content);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Strips v1-style YAML frontmatter from a bundled agent prompt: the v2 inline
     * definition carries its own metadata, so the file's frontmatter (permission map,
     * model pin) must not leak into the system prompt. The closing fence is located by
     * scanning for a line whose trimmed content is {@code ---} (lenient about
     * indentation and trailing whitespace); an {@code indexOf} search would
     * cut at a {@code ---} inside a multi-line YAML value and leak the rest of the
     * frontmatter into the prompt. Returns null when the content is unusable (an
     * unclosed frontmatter fence, or nothing left after stripping). Package-private
     * for unit testing.
     */
    static @Nullable String stripFrontmatter(String content) {
        String body = content;
        if (content.startsWith("---")) {
            String[] lines = content.split("\n", -1);
            int fence = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    fence = i;
                    break;
                }
            }
            if (fence < 0) {
                return null;
            }
            body = String.join("\n", Arrays.asList(lines).subList(fence + 1, lines.length));
        }
        String prompt = body.strip();
        return prompt.isEmpty() ? null : prompt;
    }

    /**
     * Builds the OPENCODE_CONFIG_CONTENT environment variable denying native tools.
     * Legacy helper: the production launch path is {@link #buildInitConfig}, which wraps
     * this shape and adds the v2 inline agents map. Retained for v1 compatibility
     * verification and direct testing of the deny map.
     *
     * <p>NOTE: We do NOT set {@code "default_agent"} here. OpenCode v1.4.10+ rejects
     * subagent slugs (like "build", "plan", "explore") as the {@code default_agent} value,
     * causing {@code session/new} to fail with
     * {@code "default agent \"build\" is a subagent"}. OpenCode selects its own default
     * agent internally — the plugin's agent dropdown controls which agent to start via
     * the session/create flow instead.</p>
     */
    static Map<String, String> buildPermissionConfig() {
        JsonObject config = new JsonObject();
        config.add("permission", permissionDenyConfig());
        return Map.of("OPENCODE_CONFIG_CONTENT", new Gson().toJson(config));
    }

    /**
     * The permission object denying the mutating native tools, shared by the v2 init config
     * and the v1 permission config so the two cannot drift. Emitted for every version: v2
     * still tolerates the deprecated field, and a confirmed v2 additionally gets
     * {@link #v2PermissionDenyRules()} with the action names v2 actually evaluates.
     */
    private static JsonObject permissionDenyConfig() {
        JsonObject permission = new JsonObject();
        for (String tool : NATIVE_TOOLS_TO_DENY) {
            permission.addProperty(tool, "deny");
        }
        return permission;
    }

    /**
     * The v2 {@code permissions} ruleset denying the native actions under their v2 names,
     * so the deny list reaches v2 through its documented field instead of only the
     * deprecated v1 {@code permission} map.
     */
    private static JsonArray v2PermissionDenyRules() {
        JsonArray rules = new JsonArray();
        for (String action : V2_ACTIONS_TO_DENY) {
            rules.add(permissionRule(action, "deny"));
        }
        return rules;
    }

    /**
     * OpenCode runs a subagent in a child session that ACP never streams, but the HTTP server
     * that {@code opencode acp} starts beside ACP serves it (see {@link OpenCodeServerEndpoint}).
     * The child session id is in the {@code task} result text.
     */
    @Override
    public @Nullable List<EntryData> loadSubAgentTranscript(
        @NotNull String subAgentEntryId, @Nullable String subAgentResult) throws IOException {
        OpenCodeTaskOutput task = OpenCodeTaskOutput.parse(subAgentResult);
        if (task == null) {
            return null;
        }
        String baseUrl = serverEndpoint.baseUrl();
        if (baseUrl == null) {
            if (!serverReachableByDesign) {
                // OpenCode 2.x: no transcript source yet. Not an error, so no error entry in the chat.
                return null;
            }
            throw new IOException("OpenCode HTTP server address is unknown (the agent was started with an "
                + "unparseable --port, no port was free, or the agent has been stopped)");
        }
        Map<String, String> shellEnvironment = ShellEnvironment.getEnvironment();
        try {
            return OpenCodeSessionHttpClient
                .fetchTranscript(baseUrl, task.childSessionId(), name -> launchEnvironmentVariable(shellEnvironment, name))
                .toEntries(subAgentEntryId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading the subagent session " + task.childSessionId(), e);
        }
    }

    @Override
    protected String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                         @Nullable JsonObject argumentsObj) {
        if ("task".equals(resolvedTitle)) {
            return extractTaskSubAgentType(params);
        }
        return super.extractSubAgentType(params, resolvedTitle, argumentsObj);
    }

    /**
     * Extracts the sub-agent type from a "task" tool call's rawInput.
     * Returns the {@code subagent_type} value if present, otherwise {@code "general"}.
     */
    static String extractTaskSubAgentType(JsonObject params) {
        JsonObject raw = params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()
            ? params.getAsJsonObject(KEY_RAW_INPUT) : null;
        if (raw != null && raw.has("subagent_type")) {
            return raw.get("subagent_type").getAsString();
        }
        return GENERAL_AGENT;
    }

    @Override
    @Nullable
    protected JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        JsonObject fromRawInput = extractRawInputArgs(params);
        return fromRawInput != null ? fromRawInput : super.parseToolCallArguments(params);
    }

    /**
     * Extracts tool call arguments from the {@code rawInput} field.
     * Returns {@code null} if rawInput is absent or empty.
     */
    @Nullable
    static JsonObject extractRawInputArgs(JsonObject params) {
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            JsonObject raw = params.getAsJsonObject(KEY_RAW_INPUT);
            if (!raw.entrySet().isEmpty()) {
                return raw;
            }
        }
        return null;
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return stripToolPrefix(protocolTitle);
    }

    /**
     * Strips the {@code agentbridge_} prefix from an OpenCode tool title.
     */
    public static String stripToolPrefix(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge_", "");
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    /**
     * Returns {@code true} if the title starts with the OpenCode MCP tool prefix.
     */
    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith("agentbridge_");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }

    @Override
    public @Nullable java.nio.file.Path getSessionDirectory() {
        java.io.File dir = com.github.catatafishen.agentbridge.session.exporters.ExportUtils.sessionsDir(project);
        return dir.isDirectory() ? dir.toPath() : null;
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws InterruptedException, ExecutionException, TimeoutException {
        String result = sendLoadSessionRequest("session/resume", cwd, sessionId);
        markSessionHistoryLoadedInternally();
        return result;
    }

    /**
     * The plugin-side Autopilot / Supervised switch. OpenCode advertises no autopilot option of its
     * own, so the plugin provides one, shown in the same dropdown as Kiro's.
     */
    static com.github.catatafishen.agentbridge.bridge.SessionOption autopilotSessionOption(boolean enabled) {
        String current = enabled ? AutopilotPolicy.AUTOPILOT : AutopilotPolicy.SUPERVISED;
        return new com.github.catatafishen.agentbridge.bridge.SessionOption(
            AutopilotPolicy.OPTION_KEY, "Autopilot",
            List.of(AutopilotPolicy.AUTOPILOT, AutopilotPolicy.SUPERVISED),
            Map.of(AutopilotPolicy.AUTOPILOT, "Autopilot", AutopilotPolicy.SUPERVISED, "Supervised"), current);
    }

    @Override
    protected List<com.github.catatafishen.agentbridge.bridge.SessionOption> localSessionOptions() {
        return List.of(autopilotSessionOption(autopilot));
    }

    @Override
    protected boolean applyLocalSessionOption(@org.jetbrains.annotations.NotNull String key,
                                              @org.jetbrains.annotations.NotNull String value) {
        if (!AutopilotPolicy.OPTION_KEY.equals(key)) {
            return false;
        }
        // The permission gate reads the stored choice (PsiBridgeService); this only keeps the
        // dropdown's selected value in step with it.
        autopilot = AutopilotPolicy.isAutopilot(value);
        return true;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        applySessionMcpServers(probedVersion, mcpPort, params);
    }

    /**
     * Sets {@code mcpServers} on session/new and session/resume params. A confirmed v2 registers
     * AgentBridge in the launch config (see {@link #buildInitConfig}); sending it again per session
     * would register the same server twice. The ACP schema still requires the field, and v2 rejects
     * a missing one with -32602 ("mcpServers: Required value is missing"), so v2 gets an empty list.
     */
    static void applySessionMcpServers(@Nullable String installedVersion, int mcpPort, JsonObject params) {
        if (isDefinitelyOpenCodeV2(installedVersion) && mcpPort > 0) {
            params.add("mcpServers", new JsonArray());
        } else {
            addMcpServerConfig(mcpPort, params);
        }
    }

    /**
     * Model selection routing. OpenCode 2 moved ACP model changes to Session Config
     * Options ({@code session/set_config_option}); the older {@code session/set_model}
     * method is gone and fails with {@code Method not found}. Route v2+ model changes
     * through the config option; on v1, keep the standard method.
     */
    @Override
    protected void sendSetModel(String sessionId, String modelId) {
        if (isOpenCodeV2OrLater()) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("sessionId", sessionId);
                params.addProperty("configId", "model");
                params.addProperty("value", modelId);
                transport.sendRequest("session/set_config_option", params)
                    .whenComplete(this::applyConfigOptionEcho)
                    .get(SET_CONFIG_OPTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcException jre
                    && jre.getCode() == com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcErrorCodes.METHOD_NOT_FOUND) {
                    // Version misdetection or unexpected build: retry the legacy method.
                    com.intellij.openapi.diagnostic.Logger.getInstance(OpenCodeClient.class).warn(displayName() + ": session/set_config_option(model) not found (OpenCode " + openCodeVersion() + "), retrying via session/set_model");
                    super.sendSetModel(sessionId, modelId);
                } else {
                    throw new RuntimeException(displayName() + ": failed to set model " + modelId + " via session/set_config_option", e);
                }
            } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException(displayName() + ": failed to set model " + modelId + " via session/set_config_option", e);
            }
        } else {
            super.sendSetModel(sessionId, modelId);
        }
    }

    private static final long SET_CONFIG_OPTION_TIMEOUT_SECONDS = 10;

    /**
     * The OpenCode version reported in the ACP {@code initialize} response, or {@code null}
     * if the agent hasn't initialized yet or didn't report a version.
     */
    private @Nullable String openCodeVersion() {
        var caps = getCapabilities();
        return caps != null && caps.agentInfo() != null ? caps.agentInfo().version() : null;
    }

    /**
     * Whether the connected OpenCode is version 2.x or later. Unparseable or missing
     * versions return {@code true}: the method-not-found fallback in
     * {@link #sendSetModel} recovers a misdetected old build.
     */
    static boolean isOpenCodeV2OrLater(@Nullable String version) {
        int[] parsed = KiroClient.parseVersion(version);
        return parsed == null || parsed[0] >= 2;
    }

    private boolean isOpenCodeV2OrLater() {
        return isOpenCodeV2OrLater(openCodeVersion());
    }

    /**
     * Adds the {@code mcpServers} block to session/new params with type "http".
     */
    static void addMcpServerConfig(int mcpPort, JsonObject params) {
        JsonObject server = new JsonObject();
        server.addProperty("name", "agentbridge");
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }
}
