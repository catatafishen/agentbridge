package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.github.catatafishen.agentbridge.agent.AgentSessionException;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentNudgeService;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.ui.NudgeSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <b>Tool filtering note:</b> {@code --excluded-tools} and {@code --available-tools} are currently
 * ignored in ACP mode (bug #556). The flags are passed anyway so they take effect once the bug is
 * fixed upstream. Built-in tools are auto-approved but tracked; a corrective "reprimand" is
 * prepended to the next user message to redirect the model toward MCP alternatives.
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
     * <p>
     * NOTE: {@code --excluded-tools} is currently ignored in ACP mode (bug #556). The flag is
     * passed anyway so it takes effect once the bug is fixed upstream.
     */
    private static final String EXCLUDED_BUILTIN_TOOLS =
        "view,edit,create,bash,glob,grep,task,report_intent";
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

    @Override
    public List<AbstractAgentClient.AgentMode> getAvailableAgents() {
        List<AbstractAgentClient.AgentMode> agents = new ArrayList<>(List.of(
            new AbstractAgentClient.AgentMode(DEFAULT_AGENT_SLUG, "Intellij-Default",
                "Full IntelliJ toolset with abuse-detection instructions"),
            new AbstractAgentClient.AgentMode(AGENT_SLUG_EXPLORE, "Intellij-Explore",
                "Read-only code navigation, no file edits or shell execution"),
            new AbstractAgentClient.AgentMode(AGENT_SLUG_EDIT, "Intellij-Edit",
                "Focused editing and refactoring tools, no system shell")
        ));

        String basePath = project.getBasePath();
        if (basePath != null) {
            Set<String> builtInSlugs = Set.of(DEFAULT_AGENT_SLUG, AGENT_SLUG_EXPLORE, AGENT_SLUG_EDIT);
            agents.addAll(ProjectAgentScanner.scanProjectAgents(Path.of(basePath), builtInSlugs));
        }

        return agents;
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

        // The Copilot CLI ignores both resumeSessionId (ACP param) and --resume (CLI flag) in
        // ACP mode as of v1.0.12. The flag is sent anyway in case a future version honours it.
        // Resume failure is handled by AcpClient.loadSession() → enableInjectionFallback().
        String resumeId = ActiveAgentManager.getInstance(project).getSettings().getResumeSessionId();
        if (resumeId != null) {
            cmd.add("--resume=" + resumeId);
            Path sessionDir = copilotHome().resolve(SESSION_STATE_DIR).resolve(resumeId);
            LOG.info("Copilot --resume=" + resumeId
                + " sessionDir=" + sessionDir + " (exists=" + Files.isDirectory(sessionDir) + ")");
        } else {
            LOG.info("Copilot: no resumeSessionId set, starting fresh session");
        }

        return cmd;
    }

    @Override
    protected boolean supportsSessionResumption() {
        return false;
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws AgentSessionException {
        // The --resume CLI flag is the only mechanism, and it is broken in ACP mode as of v1.0.12.
        throw new AgentSessionException(
            "Copilot CLI does not support session loading in ACP mode (as of v1.0.12). "
                + "The --resume CLI flag is passed at launch but is currently ignored.");
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
        return Path.of(System.getProperty("user.home"), ".copilot");
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
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        if (protocolTitle.startsWith(MCP_TOOL_PREFIX)) {
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

    /**
     * In Copilot's ACP implementation, sub-agent invocations appear as {@code tool_call}
     * notifications where the {@code title} is the agent's name (e.g., "Intellij-Explore").
     * The ACP spec has no standard {@code agentType} field, so we detect sub-agent calls
     * by matching the resolved title against the names of our registered Copilot agents.
     */
    @Override
    @org.jetbrains.annotations.Nullable
    protected String extractSubAgentType(
        @org.jetbrains.annotations.NotNull com.google.gson.JsonObject params,
        @org.jetbrains.annotations.NotNull String resolvedTitle,
        @org.jetbrains.annotations.Nullable com.google.gson.JsonObject argumentsObj) {
        // First, try the standard field-based detection from the base class
        String standard = super.extractSubAgentType(params, resolvedTitle, argumentsObj);
        if (standard != null) return standard;

        // Copilot-specific: match by title against known agent names
        // getAvailableAgents() returns the slugs and names we registered
        for (AgentMode agent : getAvailableAgents()) {
            if (resolvedTitle.equalsIgnoreCase(agent.slug()) || resolvedTitle.equalsIgnoreCase(agent.name())) {
                return agent.slug();
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
        return ModelDisplayMode.MULTIPLIER;
    }

    @Override
    public boolean supportsMultiplier() {
        return true;
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
                You are a coding assistant with full access to IntelliJ IDEA tools.

                IMPORTANT — use IntelliJ tools, not shell commands, for the following:
                - Git: use git_status, git_diff, git_log, git_commit, git_stage, git_branch, etc.
                  Do NOT run git via run_command or run_in_terminal — it causes editor buffer desync.
                - File reading: use read_file, not cat/head/tail via run_command.
                - File editing: use write_file, edit_text, replace_symbol_body, etc., not sed via run_command.
                - Text search: use search_text and search_symbols, not grep/rg via run_command.
                - File search: use list_project_files, not find via run_command.
                - Build/test: use build_project and run_tests, not Gradle tasks via run_command.
                """
        );
    }

    private String buildExploreAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Explore",
            "Read-only IntelliJ code explorer for analysing and understanding a codebase",
            merge(exploreMcpToolIds(), WEB_TOOLS),
            """
                You are a read-only code analysis assistant. Your role is to explore, search,
                and explain the codebase — not to make any changes.

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
                You are a precise code editing assistant. Make targeted, minimal changes
                and verify them with build_project or run_tests after each edit.

                IMPORTANT — use IntelliJ tools, not shell commands:
                - Git: use git_status, git_diff, git_commit, etc., not git via run_command.
                - File editing: use edit_text, write_file, replace_symbol_body.
                - Search: use search_text, search_symbols, not grep via run_command.
                - Build/test: use build_project and run_tests.
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

    // ─── Built-in tool reprimand ─────────────────────────────────────────────

    /**
     * Detects built-in tool calls in the session update stream and fires a reprimand nudge.
     * This handles tools that execute WITHOUT requesting permission (e.g. "safe" read/search
     * tools like grep, glob, view that Copilot may run silently without an ACP permission event).
     * Tools that DO request permission are handled separately by the permission handler
     * via auto-deny, but that path is only reached when Copilot asks — not when it skips the ask.
     */
    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        if (update instanceof SessionUpdate.ToolCall toolCall && !toolCall.isSubAgent()) {
            String title = toolCall.title();
            // Built-in detection: known names (grep, view, bash…) or bash-with-description (has spaces).
            boolean isBuiltIn = KNOWN_BUILTIN_TOOL_NAMES.contains(title.toLowerCase())
                || (title.contains(" ") && !title.startsWith(MCP_TOOL_PREFIX));
            if (isBuiltIn && shouldReprimand(title)) {
                com.github.catatafishen.agentbridge.settings.ChatInputSettings.ReprimandNudgeMode mode =
                    com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().getReprimandNudgeMode();
                if (mode != com.github.catatafishen.agentbridge.settings.ChatInputSettings.ReprimandNudgeMode.DISABLED) {
                    boolean showBubble = mode == com.github.catatafishen.agentbridge.settings.ChatInputSettings.ReprimandNudgeMode.ENABLED;
                    AgentNudgeService.getInstance(project).addNudge(buildSingleToolReprimand(title), NudgeSource.NATIVE_TOOL_REPRIMAND, showBubble);
                }
            }
        }
        return update;
    }

    /**
     * Clears the human nudge slot at turn start so user input from the previous turn
     * doesn't leak into the new prompt. The reprimand slot is intentionally left intact:
     * if the model ended a turn after a built-in tool was denied (without calling any MCP
     * tool to consume the reprimand), the reprimand must survive to be delivered in the
     * first MCP call of the next turn.
     */
    @Override
    protected PromptRequest beforeSendPrompt(PromptRequest request) {
        AgentNudgeService.getInstance(project).clearHumanNudges();
        return request;
    }

    private String buildSingleToolReprimand(String toolId) {
        boolean isBashWithDescription = toolId.contains(" ");
        String label = isBashWithDescription ? "\"" + toolId + "\"" : toolId;
        String alternative = mcpAlternative(toolId);
        return "[System notice] Use " + alternative + " — not the built-in " + label
            + ". All agentbridge-* MCP tools are available.";
    }

    /**
     * Returns {@code false} for built-in tools that have no meaningful MCP alternative
     * (e.g. meta-tools like {@code report_intent}, {@code skill}, {@code sql}) to avoid
     * misleading reprimands.
     */
    private static boolean shouldReprimand(String toolId) {
        String lower = toolId.toLowerCase();
        // Web tools and Copilot meta-tools have no meaningful MCP alternative — skip reprimand.
        return !WEB_TOOLS.contains(lower) && switch (lower) {
            case "report_intent", "skill", "sql", "task_complete" -> false;
            default -> true;
        };
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
     * With auto-deny off, disallowed built-in tools are auto-approved instead, and the
     * reprimand nudge mechanism injects corrective guidance into the next MCP tool response.
     * <p>
     * Re-enable when Copilot CLI fixes in-turn recovery after ACP tool denial.
     * Tracked: https://github.com/NousResearch/hermes-agent/issues/17284
     */
    @Override
    protected boolean isAutoDenyEnabled() {
        return false;
    }

    @Override
    protected String mcpAlternative(String toolId) {
        // Copilot CLI sends the bash `description` as the protocol title; descriptions contain spaces.
        if (toolId.contains(" ")) {
            return bashDescriptionAlternative(toolId.toLowerCase());
        }
        return super.mcpAlternative(toolId);
    }

    /**
     * Infers the best MCP alternative from the human-readable bash description.
     * Copilot CLI sends the {@code description} parameter as the ACP title for bash calls,
     * so we never know the literal tool name — only what the agent said it was doing.
     */
    private static String bashDescriptionAlternative(String lowerDesc) {
        if (lowerDesc.startsWith("write ") || lowerDesc.startsWith("create ") || lowerDesc.startsWith("save ")) {
            return "agentbridge-write_file (for existing files) or agentbridge-create_file (for new files)"
                + " — these write through the IDE editor buffer and stay in sync with IntelliJ."
                + " Never use shell redirection (cat >, tee, heredocs) to write project files.";
        }
        if (lowerDesc.startsWith("find ") || lowerDesc.startsWith("search ") || lowerDesc.startsWith("grep ")) {
            return "agentbridge-search_text or agentbridge-search_symbols — these are IDE-integrated and semantic-aware.";
        }
        if (lowerDesc.startsWith("read ") || lowerDesc.startsWith("get ") || lowerDesc.startsWith("check ")) {
            return "agentbridge-read_file (for files) or agentbridge-search_text (for content search).";
        }
        return "agentbridge-run_command for shell commands, agentbridge-write_file for file writes,"
            + " or agentbridge-search_text for searches.";
    }
}
