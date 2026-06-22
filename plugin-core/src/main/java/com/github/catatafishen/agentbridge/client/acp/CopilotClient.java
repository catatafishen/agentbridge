package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.InitializeResponse;
import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.client.ClientSessionException;
import com.github.catatafishen.agentbridge.model.Model;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentNudgeService;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * GitHub Copilot ACP client.
 * <p>
 * Command: {@code copilot --acp --stdio}
 * Tool prefix: {@code agentbridge-read_file} → strip {@code agentbridge-}
 * Model display: multiplier from {@code _meta.copilotUsage}
 * References: requires inline (no ACP resource blocks)
 * MCP: HTTP via {@code mcpServers} in {@code session/new} + merged into {@code ~/.copilot/mcp-config.json}
 * Agents: three custom agents written to {@code ~/.copilot/agents/} at launch
 * <p>
 * <b>Tool filtering note:</b> {@code --excluded-tools} and {@code --available-tools} are honored
 * by Copilot CLI in ACP mode, so built-in tools that overlap with our MCP tools are excluded
 * outright and never offered to the model.
 */
public final class CopilotClient extends AcpClient {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(CopilotClient.class);

    private static final String AGENT_ID = "copilot";
    private static final String DEFAULT_AGENT_SLUG = "intellij-default";
    private static final String MCP_SERVER_NAME = "agentbridge";
    private static final String MCP_TOOL_PREFIX = "agentbridge-";
    private static final String MCP_TYPE_HTTP = "http";
    private static final String KEY_RAW_INPUT = "rawInput";
    private static final String SESSION_STATE_DIR = "session-state";
    private static final String KEY_MCP_SERVERS = "mcpServers";
    private static final String AGENT_SLUG_EXPLORE = "intellij-explore";
    private static final String AGENT_SLUG_EDIT = "intellij-edit";

    // ─── MCP tool sets ───────────────────────────────

    /**
     * All non-built-in MCP tools.
     */
    private List<String> allMcpToolIds() {
        return ToolRegistry.getInstance(project).getAllTools().stream()
            .filter(t -> !t.isBuiltIn())
            .map(ToolDefinition::id)
            .sorted()
            .toList();
    }

    /**
     * Read-only tools only — no writes, no execution.
     */
    private List<String> exploreMcpToolIds() {
        return ToolRegistry.getInstance(project).getAllTools().stream()
            .filter(t -> !t.isBuiltIn() && t.kind() == ToolDefinition.Kind.READ)
            .map(ToolDefinition::id)
            .sorted()
            .toList();
    }

    /**
     * Editing tools — read + edit kinds, excludes execute/write (shell, debug, terminal).
     */
    private List<String> editMcpToolIds() {
        return ToolRegistry.getInstance(project).getAllTools().stream()
            .filter(t -> !t.isBuiltIn()
                && (t.kind() == ToolDefinition.Kind.READ || t.kind() == ToolDefinition.Kind.EDIT))
            .map(ToolDefinition::id)
            .sorted()
            .toList();
    }

    /**
     * Copilot built-in web tools (not from our MCP server).
     */
    private static final List<String> WEB_TOOLS = List.of("web_fetch", "web_search");

    /**
     * Copilot CLI built-in tools to exclude via {@code --excluded-tools}.
     * These overlap with (or duplicate) our agentbridge MCP tools and would confuse the model.
     */
    private static final String EXCLUDED_BUILTIN_TOOLS =
        "view,edit,create,bash,glob,grep";
    /**
     * Known Copilot CLI built-in tool names. Used by {@link #resolveToolId} to distinguish
     * actual tool names from human-readable task descriptions that the CLI sends as titles
     * for sub-agent invocations.
     */
    private static final Set<String> KNOWN_BUILTIN_TOOL_NAMES = Set.of(
        "view", "edit", "create", "bash", "glob", "grep", "task", "report_intent",
        "web_fetch", "web_search", "task_complete", "sql", "skill"
    );

    // ─── Lifecycle ───────────────────────────────────

    public CopilotClient(Project project) {
        super(project);
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws IOException {
        // Do NOT call ShellEnvironment.refresh() here. refresh() clears the cache, which forces
        // a login-shell spawn on the next getEnvironment() call — that blocks for 1–5 s while
        // bash -l sources nvm/sdkman/cargo init scripts. Doing this on every connect makes the
        // chat pane noticeably slow to open. The cache is pre-warmed at project open by
        // ActiveAgentManager and remains valid for the IDE session.
        Path home = copilotHome();
        writeAgentDefinitions(home.toString());
        String basePath = project.getBasePath();
        if (basePath != null) {
            Set<String> builtInSlugs = Set.of(DEFAULT_AGENT_SLUG, AGENT_SLUG_EXPLORE, AGENT_SLUG_EDIT);
            ProjectAgentScanner.copyToGlobalAgentsDir(Path.of(basePath), home.resolve("agents"), builtInSlugs);
        }
        mergeMcpConfig(home, mcpPort);
        migrateResumeSessionFromLegacyPath();
    }

    // ─── Identity ────────────────────────────────────

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "GitHub Copilot";
    }

    @Override
    public @Nullable String defaultAgentSlug() {
        return DEFAULT_AGENT_SLUG;
    }

    /**
     * Returns an empty list — Copilot CLI already exposes its agent definitions as ACP
     * config options (populated from {@code ~/.copilot/agents/*.md}), which the plugin
     * shows via {@link #listSessionOptions()}. Returning agents here would create a
     * duplicate "Agent" section in the dropdown.
     */
    @Override
    public List<AbstractClient.AgentMode> getAvailableAgents() {
        return List.of();
    }

    // ─── Process ─────────────────────────────────────

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        String agentSlug = getCurrentAgentSlug();
        List<String> cmd = new java.util.ArrayList<>(List.of(
            AGENT_ID, "--acp", "--stdio",
            "--disable-builtin-mcps",
            "--no-auto-update",
            "--excluded-tools", EXCLUDED_BUILTIN_TOOLS
        ));
        if (agentSlug != null && !agentSlug.isEmpty()) {
            cmd.add("--agent");
            cmd.add(agentSlug);
        }

        // Resume is handled exclusively over ACP (session/resume) by AcpClient when the agent
        // advertises the capability. We intentionally do NOT pass --resume here:
        //   * --resume forces a restart of the Copilot process to attempt resume, whereas
        //     session/resume happens over the running ACP transport with no restart.
        //   * Passing --resume with a stale ID (after a crash, manual cleanup, or CLI cache
        //     eviction) made Copilot crash on startup. The plugin then restarted it, which
        //     crashed again with the same stale ID, locking the user into an infinite crash
        //     loop with no recovery path (issue #750).
        //   * When the agent does not advertise session/resume, loadSession() throws, the
        //     standard AcpClient fallback enables conversation-history injection and notifies
        //     the user that resume was unavailable.

        return cmd;
    }

    @Override
    protected boolean supportsSessionResumption() {
        // Use session/resume if Copilot advertises it; fall back to false (old --resume is broken).
        InitializeResponse caps = getCapabilities();
        return caps != null
            && caps.agentCapabilities() != null
            && caps.agentCapabilities().sessionCapabilities() != null
            && caps.agentCapabilities().sessionCapabilities().supportsResume();
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws ClientSessionException {
        if (!supportsSessionResumption()) {
            // Defensive guard — callers should check supportsSessionResumption() before calling,
            // but we throw here to surface the error clearly if they don't.
            throw new ClientSessionException(
                "Copilot CLI does not advertise ACP session/resume capability. "
                    + "Install a newer Copilot CLI version that supports session resumption.");
        }
        try {
            return sendLoadSessionRequest("session/resume", cwd, sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientSessionException("session/resume interrupted", e);
        } catch (Exception e) {
            throw new ClientSessionException("session/resume failed: " + e.getMessage(), e);
        }
    }

    @Override
    public @Nullable Path getSessionDirectory() {
        String sid = getCurrentSessionId();
        if (sid == null) return null;
        Path dir = copilotHome().resolve(SESSION_STATE_DIR).resolve(sid);
        return java.nio.file.Files.isDirectory(dir) ? dir : null;
    }

    /**
     * Returns the standard Copilot CLI home directory ({@code ~/.copilot/}).
     * No environment overrides — the CLI uses the real user home for config, auth, and sessions.
     */
    static Path copilotHome() {
        return Path.of(SystemProperties.getUserHome(), ".copilot");
    }

    private void migrateResumeSessionFromLegacyPath() {
        String resumeId = ActiveAgentManager.getInstance(project).getSettings().getResumeSessionId();
        if (resumeId == null) return;

        Path newDir = copilotHome().resolve(SESSION_STATE_DIR).resolve(resumeId);
        if (Files.isDirectory(newDir)) return;

        String basePath = project.getBasePath();
        if (basePath == null) return;

        Path legacyDir = Path.of(basePath, ".agent-work", AGENT_ID, SESSION_STATE_DIR, resumeId);
        if (!Files.isDirectory(legacyDir)) return;

        try {
            Files.createDirectories(newDir.getParent());
            Files.createSymbolicLink(newDir, legacyDir);
            LOG.info("Migrated resume session " + resumeId + " from legacy path: " + legacyDir + " → " + newDir);
        } catch (IOException e) {
            LOG.warn("Failed to migrate resume session from legacy path: " + legacyDir, e);
        }
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        // No environment overrides — let the CLI use standard ~/.copilot/ for config,
        // auth, and session state. Overriding HOME breaks --resume path resolution.
        return Map.of();
    }

    @Override
    protected boolean shouldStripNonEssentialPath() {
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile profile = mgr.getProfile(AGENT_ID);
        return profile != null && profile.isStripNonEssentialPath();
    }

    // ─── Session ─────────────────────────────────────

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        JsonObject server = new JsonObject();
        server.addProperty("name", MCP_SERVER_NAME);
        server.addProperty("type", MCP_TYPE_HTTP);
        server.addProperty("url", "http://localhost:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray()); // Copilot requires headers as empty array

        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add(KEY_MCP_SERVERS, servers);
    }

    // ─── Mode selection ──────────────────────────────

    /**
     * For Copilot the {@code modeSlug} in {@code session/prompt} must be one of the ACP
     * standard mode URIs returned by {@code session/new} (e.g. the {@code #agent} mode URI).
     * The custom agent slug is passed via {@code --agent} at CLI startup and must not be
     * sent as {@code modeSlug} — Copilot would ignore it and use the default agent.
     */
    @Override
    public @Nullable String getEffectiveModeSlug() {
        return getCurrentModeSlug();
    }

    // ─── Tools ───────────────────────────────────────

    @Override
    protected String resolveToolId(String protocolTitle) {
        if (protocolTitle.startsWith(MCP_TOOL_PREFIX)) {
            return protocolTitle.substring(MCP_TOOL_PREFIX.length());
        }
        // Copilot CLI sends known tool names (bash, grep, task, etc.) directly.
        // Normalize to lowercase so the rest of the system uses consistent IDs.
        String lower = protocolTitle.toLowerCase();
        if (KNOWN_BUILTIN_TOOL_NAMES.contains(lower)) {
            return lower;
        }
        // Unrecognized title (e.g. sub-agent internal calls like "Confirm new file exists"
        // or human-readable task descriptions). Return as-is — ToolCallTracker will correct
        // the chip label to the real MCP tool name once execution is correlated.
        return protocolTitle;
    }

    @Override
    protected @Nullable String resolveAcpName(@NotNull String rawTitle, @Nullable String kind) {
        if (rawTitle.startsWith(MCP_TOOL_PREFIX)) {
            return rawTitle.substring(MCP_TOOL_PREFIX.length());
        }
        String lower = rawTitle.toLowerCase();
        if (KNOWN_BUILTIN_TOOL_NAMES.contains(lower)) {
            return lower;
        }
        return kind;
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        if (protocolTitle.startsWith(MCP_TOOL_PREFIX)) {
            return true;
        }
        // Any "<server>-<tool>" pattern (e.g. "github-create_issue", "puppeteer-screenshot")
        // is a third-party MCP server tool explicitly configured by the user. Do not reprimand.
        if (hasMcpServerPrefix(protocolTitle)) {
            return true;
        }
        // Copilot CLI sends human-readable display names (e.g. "Git Stage") in
        // permission requests instead of the snake_case "agentbridge-git_stage" form
        // used in session/update notifications. Fall back to ToolRegistry lookup
        // so these are recognized as MCP tools rather than flagged as built-in.
        ToolRegistry registry = ToolRegistry.getInstance(project);
        return registry.findByDisplayName(protocolTitle) != null;
    }

    /**
     * Returns {@code true} if {@code title} starts with a {@code <server>-} prefix,
     * indicating a tool from any MCP server (e.g. {@code agentbridge-}, {@code github-},
     * {@code puppeteer-}). Server prefixes are all-alphanumeric — no spaces or underscores.
     */
    private static boolean hasMcpServerPrefix(String title) {
        int hyphen = title.indexOf('-');
        if (hyphen <= 0) return false;
        String prefix = title.substring(0, hyphen);
        return prefix.chars().allMatch(Character::isLetterOrDigit);
    }

    /**
     * Copilot sends tool arguments in a non-standard {@code rawInput} field instead of
     * the spec-compliant {@code arguments} field. Fall back to {@code rawInput} if
     * {@code arguments} is absent.
     *
     * <p><b>Why extracted:</b> This is a Copilot-specific deviation from the ACP spec.
     * Other clients use the standard {@code arguments} field provided by the base class.
     */
    @Override
    protected com.google.gson.JsonObject parseToolCallArguments(@org.jetbrains.annotations.NotNull com.google.gson.JsonObject params) {
        com.google.gson.JsonObject standard = super.parseToolCallArguments(params);
        if (standard != null) return standard;
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            return params.getAsJsonObject(KEY_RAW_INPUT);
        }
        return null;
    }

    @Override
    protected String extractSubAgentType(
        @org.jetbrains.annotations.NotNull com.google.gson.JsonObject params,
        @org.jetbrains.annotations.NotNull String resolvedTitle,
        @org.jetbrains.annotations.Nullable com.google.gson.JsonObject argumentsObj) {
        String standard = super.extractSubAgentType(params, resolvedTitle, argumentsObj);
        if (standard != null) return standard;

        // Match only against the "agent" config option values (sub-agent names reported by Copilot CLI)
        for (AgentConfigOption opt : getAvailableConfigOptions()) {
            if (!"agent".equals(opt.id())) continue;
            for (AgentConfigOptionValue val : opt.values()) {
                if (resolvedTitle.equalsIgnoreCase(val.id()) || resolvedTitle.equalsIgnoreCase(val.label())) {
                    return val.id();
                }
            }
        }
        return null;
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    // ─── Models ──────────────────────────────────────

    /**
     * Copilot delivers its entire response via {@code session/update} streaming notifications
     * and often never sends the final JSON-RPC response to {@code session/prompt}.
     * When a timeout occurs we treat it as a successful end-of-turn since the UI has already
     * received and rendered all the content.
     */
    @Override
    protected @Nullable PromptResponse tryRecoverPromptException(Exception cause) {
        Throwable root = cause;
        while (root.getCause() != null) root = root.getCause();
        if (root instanceof TimeoutException) {
            return new PromptResponse("end_turn", null);
        }
        return null;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.TOKEN_COUNT;
    }

    @Override
    public @Nullable String getModelMultiplier(Model model) {
        JsonObject meta = model._meta();
        if (meta != null && meta.has("copilotUsage")) {
            return meta.get("copilotUsage").getAsString();
        }
        return null;
    }

    // ─── Agent definitions ───────────────────────────

    private void writeAgentDefinitions(String configDir) throws IOException {
        Path agentsDir = Path.of(configDir, "agents");
        Files.createDirectories(agentsDir);
        writeAgentFile(agentsDir.resolve("intellij-default.md"), buildDefaultAgentDefinition());
        writeAgentFile(agentsDir.resolve("intellij-explore.md"), buildExploreAgentDefinition());
        writeAgentFile(agentsDir.resolve("intellij-edit.md"), buildEditAgentDefinition());
    }

    /**
     * Merges our agentbridge MCP server into the user's existing {@code mcp-config.json}.
     * If the file doesn't exist, creates it. If it does, adds/updates the agentbridge entry
     * without clobbering other user-configured MCP servers.
     */
    private void mergeMcpConfig(Path copilotDir, int mcpPort) throws IOException {
        mergeMcpConfigStatic(copilotDir, mcpPort, MCP_SERVER_NAME, MCP_TYPE_HTTP);
    }

    /**
     * Merges the agentbridge MCP server entry into the user's existing {@code mcp-config.json}.
     * Extracted as a static method for testability.
     */
    static void mergeMcpConfigStatic(Path copilotDir, int mcpPort,
                                     String serverName, String transportType) throws IOException {
        Path configPath = copilotDir.resolve("mcp-config.json");
        JsonObject root;
        if (Files.exists(configPath)) {
            String existing = Files.readString(configPath, StandardCharsets.UTF_8);
            root = com.google.gson.JsonParser.parseString(existing).getAsJsonObject();
        } else {
            root = new JsonObject();
        }
        if (!root.has(KEY_MCP_SERVERS) || !root.get(KEY_MCP_SERVERS).isJsonObject()) {
            root.add(KEY_MCP_SERVERS, new JsonObject());
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("type", transportType);
        entry.addProperty("url", "http://localhost:" + mcpPort + "/mcp");
        root.getAsJsonObject(KEY_MCP_SERVERS).add(serverName, entry);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, root.toString(), StandardCharsets.UTF_8);
    }

    private static void writeAgentFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    private String buildDefaultAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Default",
            "Full-featured IntelliJ coding assistant with access to all IDE tools",
            merge(allMcpToolIds(), WEB_TOOLS),
            """
                You are running inside an IntelliJ IDEA plugin. All interactions with the
                system go through AgentBridge MCP tools. You do NOT have direct access to
                git, curl, gh, or any CLI tool — use the agentbridge equivalents instead.

                IMPORTANT — use IntelliJ MCP tools, not shell commands, for the following:
                - Git: use git_status, git_diff, git_log, git_commit, git_stage, git_branch, etc.
                  Do NOT run git via run_command or run_in_terminal — it causes editor buffer desync.
                - File reading: use read_file, not cat/head/tail via run_command.
                - File editing: use write_file, edit_text, replace_symbol_body, etc., not sed via run_command.
                - Text search: use search_text and search_symbols, not grep/rg via run_command.
                - File search: use list_project_files, not find via run_command.
                - Build/test: use build_project and run_tests, not Gradle tasks via run_command.
                - HTTP/API calls: use http_request, not curl/gh/wget via run_command.
                  The plugin injects bot identity tokens into http_request — native tools
                  bypass this and actions will be attributed to the user instead of the bot.
                """
        );
    }

    private String buildExploreAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Explore",
            "Read-only IntelliJ code explorer for analysing and understanding a codebase",
            merge(exploreMcpToolIds(), WEB_TOOLS),
            """
                You are a read-only code analysis assistant running inside an IntelliJ IDEA
                plugin. Your role is to explore, search, and explain the codebase — not to
                make any changes. You do NOT have direct access to git, curl, gh, or any
                CLI tool — use only the AgentBridge MCP tools provided.

                Use IntelliJ tools for all exploration:
                - read_file, list_project_files, get_file_outline for file content
                - search_text, search_symbols, find_references, find_implementations for search
                - git_status, git_diff, git_log for git history
                - get_compilation_errors, get_problems for diagnostics

                Do NOT suggest or make any edits to files.
                """
        );
    }

    private String buildEditAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Edit",
            "Focused IntelliJ code editing assistant — makes targeted changes and validates them",
            merge(editMcpToolIds(), WEB_TOOLS),
            """
                You are a precise code editing assistant running inside an IntelliJ IDEA
                plugin. Make targeted, minimal changes and verify them with build_project
                or run_tests after each edit. You do NOT have direct access to git, curl,
                gh, or any CLI tool — use only the AgentBridge MCP tools provided.

                IMPORTANT — use IntelliJ MCP tools, not shell commands:
                - Git: use git_status, git_diff, git_commit, etc., not git via run_command.
                - File editing: use edit_text, write_file, replace_symbol_body.
                - Search: use search_text, search_symbols, not grep via run_command.
                - Build/test: use build_project and run_tests.
                - HTTP/API calls: use http_request, not curl/gh via run_command.
                """
        );
    }

    private static String buildAgentDefinition(String name, String description,
                                               List<String> tools, String systemPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: \"").append(description).append("\"\n");
        sb.append("tools:\n");
        for (String tool : tools) {
            sb.append("  - ").append(tool).append("\n");
        }
        sb.append("---\n\n");
        sb.append(systemPrompt.stripLeading());
        return sb.toString();
    }

    private static List<String> merge(List<String> mcpTools, List<String> builtinTools) {
        // MCP tools use agentbridge/ prefix; built-in Copilot tools have no prefix
        List<String> result = new java.util.ArrayList<>();
        for (String tool : mcpTools) {
            result.add("agentbridge/" + tool);
        }
        result.addAll(builtinTools);
        return result;
    }

    /**
     * Clears the human nudge slot at turn start so user input from the previous turn
     * doesn't leak into the new prompt.
     */
    @Override
    protected PromptRequest beforeSendPrompt(PromptRequest request) {
        AgentNudgeService.getInstance(project).clearHumanNudges();
        return request;
    }

    /**
     * Auto-deny is disabled for Copilot CLI in ACP mode.
     * <p>
     * Copilot CLI ends the current agent turn whenever a {@code session/request_permission}
     * response uses {@code deny_once} or {@code reject_once} — the agent sees
     * "The user rejected this tool call" and performs {@code end_turn} without retrying.
     * This makes auto-deny counterproductive: the agent can't recover and use the correct
     * MCP tool within the same turn.
     * <p>
     * With auto-deny off, any built-in tool the CLI still offers is auto-approved instead of
     * ending the turn.
     * <p>
     * Re-enable when Copilot CLI fixes in-turn recovery after ACP tool denial.
     * Tracked: https://github.com/NousResearch/hermes-agent/issues/17284
     */
    @Override
    protected boolean isAutoDenyEnabled() {
        return false;
    }
}
