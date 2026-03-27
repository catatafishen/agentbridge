package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient;
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * fixed upstream. Built-in tools are suppressed via ACP permission-denial in the meantime.
 */
public final class CopilotClient extends AcpClient {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(CopilotClient.class);

    private static final String AGENT_ID = "copilot";
    private static final String DEFAULT_AGENT_SLUG = "intellij-default";
    private static final String MCP_SERVER_NAME = "agentbridge";
    private static final String MCP_TYPE_HTTP = "http";
    private static final String KEY_RAW_INPUT = "rawInput";

    // ─── MCP tool sets ───────────────────────────────

    /**
     * All MCP tools exposed by our server (excludes internal-only: get_chat_html, search_conversation_history).
     */
    private static final List<String> ALL_MCP_TOOLS = List.of(
        "add_to_dictionary", "apply_action", "apply_quickfix", "build_project",
        "create_file", "create_run_configuration", "create_scratch_file",
        "delete_file", "delete_run_configuration", "download_sources",
        "edit_project_structure", "edit_run_configuration", "edit_text",
        "find_implementations", "find_references", "format_code",
        "get_action_options", "get_active_file", "get_available_actions",
        "get_call_hierarchy", "get_class_outline", "get_compilation_errors",
        "get_coverage", "get_documentation", "get_file_history", "get_file_outline",
        "get_highlights", "get_indexing_status", "get_notifications", "get_open_editors",
        "get_problems", "get_project_info", "get_sonar_rule_description", "get_type_hierarchy",
        "git_blame", "git_branch", "git_cherry_pick", "git_commit", "git_diff",
        "git_fetch", "git_log", "git_merge", "git_pull", "git_push", "git_rebase",
        "git_remote", "git_reset", "git_revert", "git_show", "git_stage", "git_stash",
        "git_status", "git_tag", "git_unstage", "go_to_declaration", "http_request",
        "insert_after_symbol", "insert_before_symbol", "list_project_files",
        "list_run_configurations", "list_scratch_files", "list_terminals", "list_tests",
        "list_themes", "mark_directory", "move_file", "open_in_editor", "optimize_imports",
        "read_build_output", "read_file", "read_ide_log", "read_run_output",
        "read_terminal_output", "redo", "refactor", "reload_from_disk", "rename_file",
        "replace_symbol_body", "run_command", "run_configuration", "run_in_terminal",
        "run_qodana", "run_scratch_file", "run_sonarqube_analysis", "run_tests",
        "search_symbols", "search_text", "set_theme", "show_diff", "suppress_inspection",
        "undo", "write_file", "write_terminal_input"
    );

    /**
     * Read-only tools for the explore agent — no file writes, no shell execution, no git mutations.
     */
    private static final List<String> EXPLORE_MCP_TOOLS = List.of(
        "find_implementations", "find_references",
        "get_action_options", "get_active_file", "get_available_actions",
        "get_call_hierarchy", "get_class_outline", "get_compilation_errors",
        "get_coverage", "get_documentation", "get_file_history", "get_file_outline",
        "get_highlights", "get_indexing_status", "get_notifications", "get_open_editors",
        "get_problems", "get_project_info", "get_sonar_rule_description", "get_type_hierarchy",
        "git_blame", "git_branch", "git_diff", "git_log", "git_remote",
        "git_show", "git_stash", "git_status", "git_tag",
        "go_to_declaration", "list_project_files", "list_run_configurations",
        "list_scratch_files", "list_terminals", "list_tests",
        "read_build_output", "read_file", "read_ide_log", "read_run_output",
        "read_terminal_output", "search_symbols", "search_text", "show_diff"
    );

    /**
     * Focused editing tools — no system shell, no Gradle wrappers, no cosmetic tools.
     */
    private static final List<String> EDIT_MCP_TOOLS = List.of(
        "apply_action", "apply_quickfix", "build_project", "create_file", "delete_file",
        "edit_text", "find_implementations", "find_references", "format_code",
        "get_action_options", "get_active_file", "get_available_actions",
        "get_call_hierarchy", "get_class_outline", "get_compilation_errors",
        "get_documentation", "get_file_history", "get_file_outline", "get_highlights",
        "get_problems", "get_project_info", "get_type_hierarchy",
        "git_blame", "git_diff", "git_log", "git_status",
        "go_to_declaration", "insert_after_symbol", "insert_before_symbol",
        "list_project_files", "move_file", "open_in_editor", "optimize_imports",
        "read_build_output", "read_file", "redo", "refactor", "reload_from_disk",
        "rename_file", "replace_symbol_body", "run_tests", "search_symbols",
        "search_text", "show_diff", "suppress_inspection", "undo", "write_file"
    );

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

    // ─── Lifecycle ───────────────────────────────────

    public CopilotClient(Project project) {
        super(project);
        // Force refresh to pick up charset fix for non-ASCII usernames
        com.github.catatafishen.ideagentforcopilot.settings.ShellEnvironment.refresh();
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws IOException {
        Path home = copilotHome();
        writeAgentDefinitions(home.toString());
        mergeMcpConfig(home, mcpPort);
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
        return List.of(
            new AbstractAgentClient.AgentMode(DEFAULT_AGENT_SLUG, "Intellij-Default",
                "Full IntelliJ toolset with abuse-detection instructions"),
            new AbstractAgentClient.AgentMode("intellij-explore", "Intellij-Explore",
                "Read-only code navigation, no file edits or shell execution"),
            new AbstractAgentClient.AgentMode("intellij-edit", "Intellij-Edit",
                "Focused editing and refactoring tools, no system shell")
        );
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

        // The Copilot CLI ignores resumeSessionId in ACP session/new params — it only
        // supports resume via the --resume CLI flag. The ID is NOT cleared here — it will
        // be overwritten by persistResumeSessionId() after a successful session/new. If the
        // CLI fails to start, the old ID is preserved for the next attempt.
        String resumeId = ActiveAgentManager.getInstance(project).getSettings().getResumeSessionId();
        if (resumeId != null) {
            cmd.add("--resume=" + resumeId);
            Path sessionDir = copilotHome().resolve("session-state").resolve(resumeId);
            LOG.info("Copilot --resume=" + resumeId
                + " sessionDir=" + sessionDir + " (exists=" + Files.isDirectory(sessionDir) + ")");
        } else {
            LOG.info("Copilot: no resumeSessionId set, starting fresh session");
        }

        return cmd;
    }

    /**
     * Returns the standard Copilot CLI home directory ({@code ~/.copilot/}).
     * No environment overrides — the CLI uses the real user home for config, auth, and sessions.
     */
    static Path copilotHome() {
        return Path.of(System.getProperty("user.home"), ".copilot");
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
        params.add("mcpServers", servers);
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
        return protocolTitle.replaceFirst("^agentbridge-", "");
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return protocolTitle.startsWith("agentbridge-");
    }

    /**
     * Copilot sends tool arguments in a non-standard {@code rawInput} field instead of
     * the spec-compliant {@code arguments} field. Fall back to {@code rawInput} if
     * {@code arguments} is absent.
     *
     * <b>Why extracted:</b> This is a Copilot-specific deviation from the ACP spec.
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

    private static void writeAgentDefinitions(String configDir) throws IOException {
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
        Path configPath = copilotDir.resolve("mcp-config.json");
        JsonObject root;
        if (Files.exists(configPath)) {
            String existing = Files.readString(configPath, StandardCharsets.UTF_8);
            root = com.google.gson.JsonParser.parseString(existing).getAsJsonObject();
        } else {
            root = new JsonObject();
        }
        if (!root.has("mcpServers") || !root.get("mcpServers").isJsonObject()) {
            root.add("mcpServers", new JsonObject());
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("type", MCP_TYPE_HTTP);
        entry.addProperty("url", "http://localhost:" + mcpPort + "/mcp");
        root.getAsJsonObject("mcpServers").add(MCP_SERVER_NAME, entry);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, root.toString(), StandardCharsets.UTF_8);
    }

    private static void writeAgentFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    private static String buildDefaultAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Default",
            "Full-featured IntelliJ coding assistant with access to all IDE tools",
            merge(ALL_MCP_TOOLS, WEB_TOOLS),
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

    private static String buildExploreAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Explore",
            "Read-only IntelliJ code explorer for analysing and understanding a codebase",
            merge(EXPLORE_MCP_TOOLS, WEB_TOOLS),
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

    private static String buildEditAgentDefinition() {
        return buildAgentDefinition(
            "Intellij-Edit",
            "Focused IntelliJ code editing assistant — makes targeted changes and validates them",
            merge(EDIT_MCP_TOOLS, WEB_TOOLS),
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
}
