package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ACP client for OpenCode.
 *
 * <p>Extends the generic {@link AcpClient} for OpenCode-specific behaviour.
 * Currently all OpenCode-specific concerns (built-in tool exclusion, config-JSON permission
 * injection, env-var MCP injection) are handled by the {@link AgentConfig} strategy, so
 * no {@link AgentClient} method overrides are needed yet.</p>
 *
 * <p>This class exists as an explicit extension point: future OpenCode-specific
 * {@link AgentClient} overrides belong here rather than in the generic base.</p>
 */
public class OpenCodeAcpClient extends AcpClient {

    public static final String PROFILE_ID = "opencode";

    private static final String ADDITIONAL_INSTRUCTIONS =
        """
            SUB-AGENT SELECTION:
            When spawning sub-agents via the `task` tool, prefer these IDE-aware custom agents:
            - Use `@ide-explore` for codebase exploration and search tasks
            - Use `@ide-general` for general development tasks
            These agents use IntelliJ MCP tools with live editor buffers instead of stale file operations.""";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("OpenCode");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setDescription("Experimental profile — OpenCode ACP support is community-maintained. "
            + "Install: npm i -g opencode-ai");
        p.setBinaryName(PROFILE_ID);
        p.setInstallHint("Install with: npm i -g opencode-ai");
        p.setAcpArgs(List.of("acp"));
        p.setMcpMethod(McpInjectionMethod.ENV_VAR);
        p.setMcpEnvVarName("OPENCODE_CONFIG_CONTENT");
        p.setMcpConfigTemplate(buildConfigTemplate());
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(false);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(true);
        p.setSendResourceReferences(false);  // OpenCode doesn't support ACP resource references; content is inlined
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(false);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.CONFIG_JSON);
        p.setSupportsSessionMessage(false);
        p.setAdditionalInstructions(ADDITIONAL_INSTRUCTIONS);
        return p;
    }

    /**
     * Builds the OpenCode config JSON template with bundled agents.
     * Includes MCP server config + custom IDE-aware agent definitions.
     */
    @NotNull
    private static String buildConfigTemplate() {
        return "{"
            + "\"default_agent\":\"ide-general\","
            + "\"mcp\":{\"intellij-code-tools\":"
            + "{\"type\":\"local\","
            + "\"command\":[\"{javaPath}\",\"-jar\",\"{mcpJarPath}\","
            + "\"--port\",\"{mcpPort}\"]}},"
            + "\"agent\":{"
            + buildGeneralAgent() + ","
            + buildExploreAgent()
            + "}"
            + "}";
    }

    /**
     * IDE-aware general-purpose agent with most IntelliJ tools enabled.
     */
    @NotNull
    private static String buildGeneralAgent() {
        return "\"ide-general\":{"
            + "\"name\":\"ide-general\","
            + "\"description\":\"General-purpose agent for IntelliJ projects. Uses IntelliJ MCP tools for file operations, git, search, and testing.\","
            + "\"mode\":\"primary\","
            + "\"prompt\":\"You are working in an IntelliJ IDEA project with access to IDE-native tools via MCP.\\n\\n"
            + "CRITICAL: ALWAYS use IntelliJ MCP tools (intellij-code-tools/*) for file operations, git, search, and terminal. "
            + "Built-in tools (read, write, edit, bash, glob, grep) are disabled.\\n\\n"
            + "Git: Use intellij-code-tools/git_* tools exclusively — shell git bypasses VCS integration.\\n\\n"
            + "File editing: Use edit_text for surgical edits, write_file for full rewrites. "
            + "Set auto_format_and_optimize_imports=false for sequential edits.\\n\\n"
            + "Verification: Check auto-highlights in responses, use get_problems, build_project.\\n\\n"
            + "Workspace: Write temp files to .agent-work/ directory.\","
            + "\"permission\":{"
            + "\"*\":\"ask\","
            + "\"intellij-code-tools/read_file\":\"allow\","
            + "\"intellij-code-tools/search_text\":\"allow\","
            + "\"intellij-code-tools/search_symbols\":\"allow\","
            + "\"intellij-code-tools/list_project_files\":\"allow\","
            + "\"intellij-code-tools/get_file_outline\":\"allow\","
            + "\"intellij-code-tools/git_status\":\"allow\","
            + "\"intellij-code-tools/git_diff\":\"allow\","
            + "\"intellij-code-tools/git_log\":\"allow\","
            + "\"intellij-code-tools/get_problems\":\"allow\","
            + "\"read\":\"deny\","
            + "\"write\":\"deny\","
            + "\"edit\":\"deny\","
            + "\"bash\":\"deny\","
            + "\"glob\":\"deny\","
            + "\"grep\":\"deny\","
            + "\"list\":\"deny\""
            + "}}";
    }

    /**
     * IDE-aware exploration agent with read-only IntelliJ tools.
     */
    @NotNull
    private static String buildExploreAgent() {
        return "\"ide-explore\":{"
            + "\"name\":\"ide-explore\","
            + "\"description\":\"Fast codebase explorer using IntelliJ code intelligence. Read-only agent optimized for searching and understanding code.\","
            + "\"mode\":\"subagent\","
            + "\"prompt\":\"You are a fast, read-only code explorer with IntelliJ code intelligence.\\n\\n"
            + "MISSION: Quickly find, analyze, and explain code using IntelliJ's semantic understanding.\\n\\n"
            + "SEARCH STRATEGY:\\n"
            + "1. Start with search_symbols for classes/methods (fastest, most precise)\\n"
            + "2. Use search_text for keywords, string literals, comments\\n"
            + "3. Use list_project_files with glob patterns\\n"
            + "4. Use get_file_outline to understand structure before reading\\n"
            + "5. Use navigation tools (go_to_declaration, find_references)\\n\\n"
            + "CONSTRAINTS: Read-only — all write operations disabled.\\n\\n"
            + "FORMAT: Be concise, include file:line refs, show 5-10 line snippets.\","
            + "\"permission\":{"
            + "\"*\":\"deny\","
            + "\"intellij-code-tools/read_file\":\"allow\","
            + "\"intellij-code-tools/search_text\":\"allow\","
            + "\"intellij-code-tools/search_symbols\":\"allow\","
            + "\"intellij-code-tools/list_project_files\":\"allow\","
            + "\"intellij-code-tools/get_file_outline\":\"allow\","
            + "\"intellij-code-tools/find_references\":\"allow\","
            + "\"intellij-code-tools/go_to_declaration\":\"allow\","
            + "\"intellij-code-tools/get_type_hierarchy\":\"allow\","
            + "\"intellij-code-tools/find_implementations\":\"allow\","
            + "\"intellij-code-tools/get_call_hierarchy\":\"allow\","
            + "\"intellij-code-tools/get_class_outline\":\"allow\","
            + "\"intellij-code-tools/get_documentation\":\"allow\","
            + "\"intellij-code-tools/git_status\":\"allow\","
            + "\"intellij-code-tools/git_diff\":\"allow\","
            + "\"intellij-code-tools/git_log\":\"allow\","
            + "\"intellij-code-tools/git_blame\":\"allow\","
            + "\"intellij-code-tools/get_problems\":\"allow\","
            + "\"intellij-code-tools/get_compilation_errors\":\"allow\""
            + "}}";
    }

    public OpenCodeAcpClient(@NotNull AgentConfig config,
                             @org.jetbrains.annotations.NotNull AgentSettings settings,
                             @Nullable ToolRegistry registry,
                             @Nullable String projectBasePath,
                             int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        return name.replaceFirst("^intellij-code-tools_", "");
    }
}
