package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure knowledge about Copilot CLI's built-in tools: which ones we exclude by default, which ones we
 * deliberately keep, and how to tell an unrecognised tool name from a human-readable tool title.
 * See {@code docs/COPILOT-BUILTIN-TOOLS.md} for the inventory behind these lists.
 */
final class CopilotBuiltInTools {

    /**
     * File/search and shell built-ins that overlap with AgentBridge MCP tools. Passed to
     * {@code --excluded-tools} unless the user configured their own list.
     */
    static final String DEFAULT_EXCLUDED =
        // File and search tools
        "view,edit,create,apply_patch,str_replace_editor,glob,grep,rg,grep_search,file_search,"
            + "search_code_subagent,lsp,"
            // Shell tools (including the async companions, which are useless without the shell itself)
            + "bash,read_bash,write_bash,stop_bash,list_bash,"
            + "powershell,read_powershell,write_powershell,stop_powershell";

    /**
     * Built-ins we know about and deliberately leave available: web access, the todo/session SQL
     * tools, sub-agents, skills, tool discovery and CLI plumbing.
     */
    static final Set<String> KNOWN_KEPT = Set.of(
        "web_fetch", "web_search", "sql", "session_store_sql",
        "task", "read_agent", "write_agent", "list_agents",
        "skill", "tool_search_tool", "report_intent", "task_complete",
        "ask_user", "exit_plan_mode"
    );

    /**
     * Copilot reports bare tool names (e.g. {@code read_bash}) as the ACP title for tools without a
     * custom display title, whereas tools with one use free text ("Update review todo",
     * "Using skill: x", "Fetching host/path"). Only lowercase snake_case titles can be tool names.
     */
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");

    private CopilotBuiltInTools() {
    }

    static boolean looksLikeToolName(@NotNull String title) {
        return TOOL_NAME.matcher(title).matches();
    }

    /**
     * Whether {@code title} is a tool name we have never classified: it looks like a tool name, is not
     * in the effective exclusion list, not in the default list, and not one of the tools we keep.
     */
    static boolean isUnknown(@NotNull String title, @NotNull String effectiveExcludedCsv) {
        if (!looksLikeToolName(title)) {
            return false;
        }
        return !KNOWN_KEPT.contains(title)
            && !parse(DEFAULT_EXCLUDED).contains(title)
            && !parse(effectiveExcludedCsv).contains(title);
    }

    static @NotNull Set<String> parse(@NotNull String csv) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .forEach(names::add);
        return names;
    }

    /**
     * Returns {@code csv} with {@code tool} appended, unchanged if it is already listed.
     */
    static @NotNull String withTool(@NotNull String csv, @NotNull String tool) {
        Set<String> names = parse(csv);
        names.add(tool);
        return String.join(",", names);
    }
}
