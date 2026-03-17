package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ACP client for Kiro.
 *
 * <p>Kiro filters tools via agent configuration files with {@code allowedTools} arrays.
 * This client creates a custom agent config that:
 * <ul>
 *   <li>Allows safe web tools (web_search, web_fetch)</li>
 *   <li>Allows IntelliJ MCP tools (via @intellij-code-tools/*)</li>
 *   <li>Excludes built-in file tools (read, write, glob, grep) — provided via MCP instead</li>
 *   <li>Excludes built-in shell tool — provided via MCP run_command instead</li>
 * </ul>
 *
 * <p>See: <a href="https://kiro.dev/docs/cli/custom-agents/configuration-reference/">Kiro Agent Configuration Reference</a>
 */
public class KiroAcpClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(KiroAcpClient.class);
    public static final String PROFILE_ID = "kiro";
    private static final String AGENT_NAME = "intellij-agent";
    private static final String AGENTS_DIR = ".agent-work/.kiro/agents";
    private static final String SETTINGS_DIR = ".agent-work/.kiro/settings";

    private static final String ADDITIONAL_INSTRUCTIONS =
        """
            CRITICAL — TOOL USAGE:
            You are running inside IntelliJ IDEA with IDE-native tools accessible via MCP.

            File operations: Use @intellij-code-tools/* tools exclusively. Built-in tools (read, write, glob, grep) are DISABLED.
            - Read: @intellij-code-tools/read_file (uses live editor buffers)
            - Write: @intellij-code-tools/write_file or edit_text (integrates with IntelliJ VFS)
            - Search: @intellij-code-tools/search_text, search_symbols
            - List: @intellij-code-tools/list_project_files

            Git operations: Use @intellij-code-tools/git_* tools exclusively — shell git bypasses IntelliJ's VCS integration.

            Shell commands: Use @intellij-code-tools/run_command instead of shell — integrates with IntelliJ's run panel.

            Web access: Use web_search and web_fetch (enabled) for internet access.

            Workspace: Write temporary files to .agent-work/ directory (git-ignored, persists across sessions).""";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Kiro");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setTransportType(TransportType.ACP);
        p.setDescription("Kiro CLI — experimental support. Ensure 'kiro-cli' is in your PATH.");
        p.setBinaryName("kiro-cli");
        p.setAlternateNames(List.of());
        p.setInstallHint("Install Kiro CLI and ensure it's available on your PATH.");
        p.setInstallUrl("https://kiro.dev/docs/cli/acp/");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of("acp", "--agent", AGENT_NAME));
        p.setMcpMethod(McpInjectionMethod.SESSION_NEW);
        p.setMcpConfigTemplate(buildMcpConfigTemplate());
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setSendResourceReferences(true);
        p.setExcludeAgentBuiltInTools(false);  // Handled via agent config allowedTools
        p.setUsePluginPermissions(false);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setSupportsSessionMessage(true);
        p.setAdditionalInstructions(ADDITIONAL_INSTRUCTIONS);
        return p;
    }

    /**
     * Builds the MCP config template for Kiro.
     * Kiro expects mcpServers as an object (same format as mcp.json).
     * See: https://kiro.dev/docs/cli/acp/#agent-capabilities
     */
    @NotNull
    private static String buildMcpConfigTemplate() {
        return "{"
            + "\"mcpServers\":{"
            + "\"intellij-code-tools\":{"
            + "\"command\":\"{javaPath}\","
            + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"],"
            + "\"env\":{}"
            + "}"
            + "}"
            + "}";
    }

    /**
     * Builds the Kiro agent configuration JSON.
     * This defines which tools are available and which are auto-allowed.
     */
    @NotNull
    private static String buildAgentConfig() {
        return "{\n"
            + "  \"name\": \"" + AGENT_NAME + "\",\n"
            + "  \"description\": \"IntelliJ IDEA integration agent with IDE-native tools via MCP\",\n"
            // Tools field: define what tools are available (not all are auto-allowed)
            + "  \"tools\": [\n"
            + "    \"web_search\",\n"       // Safe web tool
            + "    \"web_fetch\",\n"        // Safe web tool
            + "    \"introspect\",\n"       // Kiro self-awareness
            + "    \"code\",\n"             // Kiro code intelligence (may overlap with our MCP tools)
            + "    \"session\",\n"          // Temporary session settings
            + "    \"@intellij-code-tools/*\"\n"  // All our MCP tools
            // Omitted built-in tools (we provide them via MCP):
            // - read, write, glob, grep (file operations)
            // - shell (command execution)
            // - edit (we use edit_text via MCP)
            // - delegate, use_subagent (task delegation — may conflict with our workflow)
            // - aws (external service — user can add if needed)
            + "  ],\n"
            // AllowedTools field: which tools can execute without prompting
            + "  \"allowedTools\": [\n"
            + "    \"web_search\",\n"
            + "    \"web_fetch\",\n"
            + "    \"introspect\",\n"
            + "    \"@intellij-code-tools/read_file\",\n"
            + "    \"@intellij-code-tools/search_text\",\n"
            + "    \"@intellij-code-tools/search_symbols\",\n"
            + "    \"@intellij-code-tools/list_project_files\",\n"
            + "    \"@intellij-code-tools/get_file_outline\",\n"
            + "    \"@intellij-code-tools/git_status\",\n"
            + "    \"@intellij-code-tools/git_diff\",\n"
            + "    \"@intellij-code-tools/git_log\",\n"
            + "    \"@intellij-code-tools/git_blame\",\n"
            + "    \"@intellij-code-tools/get_problems\",\n"
            + "    \"@intellij-code-tools/get_compilation_errors\",\n"
            + "    \"@intellij-code-tools/go_to_declaration\",\n"
            + "    \"@intellij-code-tools/find_references\",\n"
            + "    \"@intellij-code-tools/get_type_hierarchy\",\n"
            + "    \"@intellij-code-tools/find_implementations\",\n"
            + "    \"@intellij-code-tools/get_call_hierarchy\",\n"
            + "    \"@intellij-code-tools/get_class_outline\",\n"
            + "    \"@intellij-code-tools/get_documentation\",\n"
            + "    \"@intellij-code-tools/get_indexing_status\"\n"
            // Write operations, git writes, and destructive operations are omitted
            // → user will be prompted for these
            + "  ],\n"
            + "  \"model\": \"claude-sonnet-4\"\n"
            + "}";
    }

    public KiroAcpClient(@NotNull AgentConfig config,
                         @NotNull AgentSettings settings,
                         @Nullable ToolRegistry registry,
                         @Nullable String projectBasePath,
                         int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
        this.logMcpPrefix = "@intellij-code-tools/";

        // Write the agent config file to .agent-work/.kiro/agents/
        // and the MCP config file to .agent-work/.kiro/settings/
        if (projectBasePath != null) {
            ensureAgentConfig(projectBasePath);
            ensureMcpConfig(projectBasePath);
        }
    }

    /**
     * Ensures the Kiro agent config file exists in the workspace.
     * Called during client construction to prepare for launch.
     */
    private void ensureAgentConfig(@NotNull String projectBasePath) {
        try {
            Path agentsDir = Path.of(projectBasePath, AGENTS_DIR);
            Files.createDirectories(agentsDir);

            Path configFile = agentsDir.resolve(AGENT_NAME + ".json");
            String agentConfig = buildAgentConfig();
            Files.writeString(configFile, agentConfig, StandardCharsets.UTF_8);

            LOG.info("Kiro agent config written to: " + configFile);
        } catch (IOException e) {
            LOG.warn("Failed to write Kiro agent config", e);
        }
    }

    /**
     * Ensures the Kiro MCP config file exists in the workspace.
     */
    private void ensureMcpConfig(@NotNull String projectBasePath) {
        try {
            Path settingsDir = Path.of(projectBasePath, SETTINGS_DIR);
            Files.createDirectories(settingsDir);

            Path mcpFile = settingsDir.resolve("mcp.json");
            String template = agentConfig.getMcpConfigTemplate();
            if (template != null && !template.isEmpty()) {
                String resolved = resolveMcpTemplate(template);
                if (resolved != null) {
                    Files.writeString(mcpFile, resolved, StandardCharsets.UTF_8);
                    LOG.info("Kiro MCP config written to: " + mcpFile);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to write Kiro MCP config", e);
        }
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        // Kiro uses @server/tool format, normalize to just "tool"
        if (name.startsWith("@intellij-code-tools/")) {
            return name.substring("@intellij-code-tools/".length());
        }
        return name;
    }
}
