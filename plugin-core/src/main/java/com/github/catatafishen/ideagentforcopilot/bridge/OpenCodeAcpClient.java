package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ACP client for OpenCode.
 *
 * <p>Extends the generic {@link AcpClient} for OpenCode-specific behaviour.
 * OpenCode sends tool calls without arguments, then follows with tool_call_update containing the actual arguments.
 * This class handles the deferred emission pattern required by OpenCode's protocol.</p>
 *
 * <p>Other OpenCode-specific concerns (built-in tool exclusion, config-JSON permission
 * injection, env-var MCP injection) are handled by the {@link AgentConfig} strategy.</p>
 */
public class OpenCodeAcpClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(OpenCodeAcpClient.class);

    public static final String PROFILE_ID = "opencode";

    // Pending tool calls waiting for arguments (OpenCode sends args in tool_call_update, not initial tool_call)
    private final ConcurrentHashMap<String, JsonObject> pendingToolCalls = new ConcurrentHashMap<>();

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
        // MCP is registered via config file (OPENCODE_CONFIG env var → opencode.json).
        // OpenCode's session/new only accepts type: "http"/"sse", not local stdio servers.
        p.setMcpMethod(McpInjectionMethod.NONE);
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

    @Override
    protected List<String> getDuplicatedTools() {
        return List.of("bash", "edit", "write", "read", "grep", "glob", "list", "lsp", "patch", "question");
    }

    /**
     * Builds the OpenCode config JSON template with bundled agents.
     * Includes ACP-standard mcpServers array + custom IDE-aware agent definitions.
     */
    @NotNull
    private static String buildConfigTemplate() {
        return "{"
            + "\"mcpServers\":["
            + "{"
            + "\"name\":\"agentbridge\","
            + "\"type\":\"local\","
            + "\"command\":[\"{javaPath}\",\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"],"
            + "\"enabled\":true"
            + "}"
            + "],"
            + "\"default_agent\":\"ide-general\","
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
            + "CRITICAL: ALWAYS use IntelliJ MCP tools (agentbridge/*) for file operations, git, search, and terminal. "
            + "Built-in tools (read, write, edit, bash, glob, grep) are disabled.\\n\\n"
            + "Git: Use agentbridge/git_* tools exclusively — shell git bypasses VCS integration.\\n\\n"
            + "File editing: Use edit_text for surgical edits, write_file for full rewrites. "
            + "Set auto_format_and_optimize_imports=false for sequential edits.\\n\\n"
            + "Verification: Check auto-highlights in responses, use get_problems, build_project.\\n\\n"
            + "Workspace: Write temp files to .agent-work/ directory.\","
            + "\"permission\":{"
            + "\"*\":\"ask\","
            + "\"agentbridge/read_file\":\"allow\","
            + "\"agentbridge/search_text\":\"allow\","
            + "\"agentbridge/search_symbols\":\"allow\","
            + "\"agentbridge/list_project_files\":\"allow\","
            + "\"agentbridge/get_file_outline\":\"allow\","
            + "\"agentbridge/git_status\":\"allow\","
            + "\"agentbridge/git_diff\":\"allow\","
            + "\"agentbridge/git_log\":\"allow\","
            + "\"agentbridge/get_problems\":\"allow\","
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
            + "\"agentbridge/read_file\":\"allow\","
            + "\"agentbridge/search_text\":\"allow\","
            + "\"agentbridge/search_symbols\":\"allow\","
            + "\"agentbridge/list_project_files\":\"allow\","
            + "\"agentbridge/get_file_outline\":\"allow\","
            + "\"agentbridge/find_references\":\"allow\","
            + "\"agentbridge/go_to_declaration\":\"allow\","
            + "\"agentbridge/get_type_hierarchy\":\"allow\","
            + "\"agentbridge/find_implementations\":\"allow\","
            + "\"agentbridge/get_call_hierarchy\":\"allow\","
            + "\"agentbridge/get_class_outline\":\"allow\","
            + "\"agentbridge/get_documentation\":\"allow\","
            + "\"agentbridge/git_status\":\"allow\","
            + "\"agentbridge/git_diff\":\"allow\","
            + "\"agentbridge/git_log\":\"allow\","
            + "\"agentbridge/git_blame\":\"allow\","
            + "\"agentbridge/get_problems\":\"allow\","
            + "\"agentbridge/get_compilation_errors\":\"allow\""
            + "}}";
    }

    public OpenCodeAcpClient(@NotNull AgentConfig config,
                             @org.jetbrains.annotations.NotNull AgentSettings settings,
                             @Nullable ToolRegistry registry,
                             @Nullable String projectBasePath,
                             int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
        this.logMcpPrefix = "agentbridge_";
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        return name.replaceFirst("^agentbridge_", "");
    }

    /**
     * OpenCode requires {@code mcpServers} to be present as an array in {@code session/new}
     * even when MCP is registered via the config file. Send an empty array so validation passes.
     */
    @Override
    protected void addExtraSessionParams(@NotNull JsonObject params) {
        if (!params.has("mcpServers")) {
            params.add("mcpServers", new com.google.gson.JsonArray());
        }
    }

    /**
     * Override to implement OpenCode's deferred tool call pattern.
     * OpenCode sends tool calls without arguments, then follows with tool_call_update containing the actual arguments.
     */
    @Override
    protected void handleToolCallEvent(@NotNull JsonObject update, @NotNull Consumer<SessionUpdate> onUpdate) {
        String toolCallId = update.has(TOOL_CALL_ID_KEY) ? update.get(TOOL_CALL_ID_KEY).getAsString() : "";
        String args = extractAcpArguments(update);
        boolean hasArgs = args != null && !args.isEmpty() && !args.equals("{}");

        if (hasArgs) {
            // Arguments present (standard style) - emit immediately
            onUpdate.accept(buildToolCallEvent(update));
        } else {
            // No arguments (OpenCode style) - defer until we get an update with args
            LOG.info("[OpenCode tool_call] Deferring toolCallId=" + toolCallId + " (no arguments yet)");
            pendingToolCalls.put(toolCallId, update);
        }
    }

    /**
     * Override to handle OpenCode's deferred tool call arguments.
     * If there's a pending deferred tool call, emit it with merged arguments from the update.
     */
    @Override
    protected void handleToolCallUpdateEvent(@NotNull JsonObject update, @NotNull Consumer<SessionUpdate> onUpdate) {
        String toolCallId = update.has(TOOL_CALL_ID_KEY) ? update.get(TOOL_CALL_ID_KEY).getAsString() : "";

        // Check if we have a deferred tool call waiting for arguments
        JsonObject pendingCall = pendingToolCalls.remove(toolCallId);
        if (pendingCall != null) {
            // Merge arguments from update into the pending call and emit
            String args = extractAcpArguments(update);
            if (args != null && !args.isEmpty() && !args.equals("{}")) {
                // Transfer any argument-like keys found in update to pendingCall
                for (String key : new String[]{ARGUMENTS_KEY, INPUT_KEY, RAW_INPUT_KEY, CONTENT, TITLE_KEY, KIND_KEY}) {
                    if (update.has(key)) {
                        pendingCall.add(key, update.get(key));
                    }
                }
            }
            LOG.info("[OpenCode tool_call] Emitting deferred toolCallId=" + toolCallId + " with merged info from update");
            onUpdate.accept(buildToolCallEvent(pendingCall));
        }

        // Always emit the update
        onUpdate.accept(buildToolCallUpdateEvent(update));
    }

    @Override
    @NotNull
    public List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Config", ".agent-work/opencode/opencode.json", false, "OpenCode"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Agents", ".agent-work/opencode/agents/*.md", true, "OpenCode"));
        return entries;
    }

    /**
     * OpenCode-specific result extraction from rawOutput.output format.
     */
    @Override
    @Nullable
    protected String extractAgentSpecificResult(@NotNull JsonObject update) {
        // OpenCode sends results in rawOutput.output
        if (update.has("rawOutput")) {
            JsonElement rawOutput = update.get("rawOutput");
            if (rawOutput.isJsonObject()) {
                JsonObject rawOutputObj = rawOutput.getAsJsonObject();
                if (rawOutputObj.has(OUTPUT_KEY)) {
                    JsonElement output = rawOutputObj.get(OUTPUT_KEY);
                    if (output.isJsonPrimitive()) return output.getAsString();
                    if (output.isJsonObject() || output.isJsonArray()) return gson.toJson(output);
                }
            }
        }
        return null;
    }
}
