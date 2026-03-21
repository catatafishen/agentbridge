package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * GitHub Copilot ACP client.
 * <p>
 * Command: {@code copilot --acp --stdio [--config-dir ...]}
 * Tool prefix: {@code agentbridge-read_file} → strip {@code agentbridge-}
 * Model display: multiplier from {@code _meta.copilotUsage}
 * References: requires inline (no ACP resource blocks)
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * Agents: three custom agents written to {@code .agent-work/copilot/agents/} at launch
 */
public final class CopilotClient extends AcpClient {

    private static final String AGENT_ID = "copilot";
    private static final String DEFAULT_MODE_SLUG = "intellij-default";
    private static final String MCP_SERVER_NAME = "agentbridge";
    private static final String MCP_TYPE_HTTP = "http";

    // ─── MCP tool sets ───────────────────────────────

    /** All MCP tools exposed by our server (excludes internal-only: get_chat_html, search_conversation_history). */
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

    /** Read-only tools for the explore agent — no file writes, no shell execution, no git mutations. */
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

    /** Focused editing tools — no system shell, no Gradle wrappers, no cosmetic tools. */
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

    /** Copilot built-in web tools (not from our MCP server). */
    private static final List<String> WEB_TOOLS = List.of("web_fetch", "web_search");

    // ─── Lifecycle ───────────────────────────────────

    public CopilotClient(Project project) {
        super(project);
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws IOException {
        String configDir = cwd + File.separator + ".agent-work" + File.separator + AGENT_ID;
        writeAgentDefinitions(configDir);
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
    public @Nullable String defaultModeSlug() {
        return DEFAULT_MODE_SLUG;
    }

    // ─── Process ─────────────────────────────────────

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        String configDir = cwd + File.separator + ".agent-work" + File.separator + AGENT_ID;
        return List.of(AGENT_ID, "--acp", "--stdio", "--config-dir", configDir);
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        String copilotHome = cwd + File.separator + ".agent-work" + File.separator + AGENT_ID;
        return Map.of("COPILOT_HOME", copilotHome);
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

    // ─── Tools ───────────────────────────────────────

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge-", "");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    // ─── Models ──────────────────────────────────────

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.MULTIPLIER;
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
