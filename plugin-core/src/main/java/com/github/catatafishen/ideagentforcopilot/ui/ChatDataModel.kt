package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.UIManager

/**
 * Shared data types and constants for the chat UI — used by both the active
 * chat panel implementation and [ConversationExporter].
 */

// ── Theme colors ──────────────────────────────────────────────────────────────

private fun getThemeColor(key: String, lightFallback: Color, darkFallback: Color): Color {
    return UIManager.getColor(key) ?: JBColor(lightFallback, darkFallback)
}

internal const val LINK_COLOR_KEY = "Component.linkColor"

internal val USER_COLOR: Color
    get() = getThemeColor(LINK_COLOR_KEY, Color(0x29, 0x79, 0xFF), Color(0x5C, 0x9D, 0xFF))

internal val TOOL_COLOR: Color
    get() = getThemeColor("EditorTabs.selectedForeground", Color(0xAE, 0xA0, 0xDC), Color(0xB4, 0xA0, 0xDC))

internal val THINK_COLOR: Color
    get() = getThemeColor("Label.disabledForeground", Color(0x80, 0x80, 0x80), Color(0xB0, 0xB0, 0xB0))

internal const val ICON_ERROR = "\u274C"

/** Matches `[quick-reply: Option A | Option B | ...]` tags. */
internal val QUICK_REPLY_TAG_REGEX = Regex("""\[quick-reply:\s*([^\]]+)]""", RegexOption.MULTILINE)

// ── Data model ────────────────────────────────────────────────────────────────

sealed class EntryData {
    class Prompt(
        val text: String,
        val timestamp: String = "",
        val contextFiles: List<Triple<String, String, Int>>? = null
    ) : EntryData()

    class Text(
        val raw: StringBuilder = StringBuilder(),
        val timestamp: String = "",
        val agent: String = ""
    ) : EntryData()

    class Thinking(
        val raw: StringBuilder = StringBuilder(),
        val timestamp: String = "",
        val agent: String = ""
    ) : EntryData()

    class ToolCall(
        val title: String,
        val arguments: String? = null,
        var kind: String = "other",
        var result: String? = null,
        var status: String? = null,
        var description: String? = null,
        var filePath: String? = null,
        val timestamp: String = "",
        val agent: String = ""
    ) : EntryData()

    class SubAgent(
        val agentType: String,
        val description: String,
        val prompt: String? = null,
        var result: String? = null,
        var status: String? = null,
        var colorIndex: Int = 0,
        val callId: String? = null,
        val timestamp: String = "",
        val agent: String = ""
    ) : EntryData()

    class ContextFiles(val files: List<Pair<String, String>>) : EntryData()
    class Status(val icon: String, val message: String) : EntryData()
    class SessionSeparator(val timestamp: String, val agent: String = "") : EntryData()
}

// ── Tool / sub-agent metadata ─────────────────────────────────────────────────

internal data class ToolInfo(val displayName: String, val description: String)

/** Sub-agent display name lookup */
internal data class SubAgentInfo(val displayName: String)

internal const val AGENT_TYPE_GENERAL = "general-purpose"

internal val SUB_AGENT_INFO = mapOf(
    // Built-in Claude Code agents (current casing from docs)
    "Explore" to SubAgentInfo("Explore"),
    "Plan" to SubAgentInfo("Plan"),
    AGENT_TYPE_GENERAL to SubAgentInfo("General"),
    // Legacy / lowercase aliases
    "explore" to SubAgentInfo("Explore"),
    "task" to SubAgentInfo("Task Agent"),
    // Custom intellij-* agents (recommended in startup instructions)
    "intellij-explore" to SubAgentInfo("Explore"),
    "intellij-edit" to SubAgentInfo("Edit Agent"),
    "intellij-default" to SubAgentInfo("Agent"),
    // Other custom agents
    "code-review" to SubAgentInfo("Code Review"),
    "ui-reviewer" to SubAgentInfo("UI Review"),
)

/** JSON key to use as subtitle in the chip label for specific tools */
internal val TOOL_SUBTITLE_KEY = mapOf(
    // File operations
    "read_file" to "path",
    "write_file" to "path",
    "edit_text" to "path",
    "create_file" to "path",
    "delete_file" to "path",
    "open_in_editor" to "file",
    "show_diff" to "file",
    "get_file_outline" to "path",
    "format_code" to "path",
    "optimize_imports" to "path",
    // Code navigation
    "search_symbols" to "query",
    "find_references" to "symbol",
    "go_to_declaration" to "symbol",
    "get_type_hierarchy" to "symbol",
    "get_documentation" to "symbol",
    "search_text" to "query",
    // Tests
    "run_tests" to "target",
    // Git
    "git_blame" to "path",
    "git_commit" to "message",
    "git_branch" to "name",
    // Code quality
    "run_inspections" to "scope",
    "apply_quickfix" to "inspection_id",
    "add_to_dictionary" to "word",
    // Run configs
    "run_configuration" to "name",
    "create_run_configuration" to "name",
    "edit_run_configuration" to "name",
    // Shell & infrastructure
    "run_command" to "title",
    "http_request" to "url",
    // Agent meta
    "report_intent" to "intent",
    "task" to "description",
    // Built-in CLI tools - lowercase
    "view" to "path",
    "edit" to "path",
    "create" to "path",
    "grep" to "pattern",
    "glob" to "pattern",
    "bash" to "description",
    "web_search" to "query",
    "web_fetch" to "url",
    // Built-in CLI tools - PascalCase variants
    "View" to "path",
    "Read" to "path",
    "Write" to "path",
    "Edit" to "path",
    "Create" to "path",
    "Grep" to "pattern",
    "Glob" to "pattern",
    "Bash" to "description",
    // OpenCode / Claude agent tools
    "todowrite" to "todos",
    "TodoWrite" to "todos",
    "codesearch" to "query",
    "CodeSearch" to "query",
    "websearch" to "query",
    "WebSearch" to "query",
    "webfetch" to "url",
    "WebFetch" to "url",
    "Task" to "description",
)

/**
 * Display info for EXTERNAL tools only (not from our MCP plugin).
 * Our MCP tools define displayName() and description() in their tool classes.
 * This map is used as a fallback when the tool is not in the registry.
 */
internal val TOOL_DISPLAY_INFO = mapOf(
    // ── Copilot CLI built-in tools ──
    "view" to ToolInfo("View", "View file or directory contents"),
    "edit" to ToolInfo("Edit", "Make string replacements in a file"),
    "create" to ToolInfo("Create", "Create a new file"),
    "grep" to ToolInfo("Grep", "Search file contents with ripgrep"),
    "glob" to ToolInfo("Glob", "Find files by name pattern"),
    "bash" to ToolInfo("Bash", "Run a shell command"),
    "read_bash" to ToolInfo("Read Bash", "Read output from an async shell command"),
    "write_bash" to ToolInfo("Write Bash", "Send input to an async shell command"),
    "stop_bash" to ToolInfo("Stop Bash", "Stop a running shell command"),
    "list_bash" to ToolInfo("List Bash", "List active shell sessions"),
    "web_search" to ToolInfo("Web Search", "AI-powered web search"),
    "web_fetch" to ToolInfo("Fetch URL", "Fetch a web page"),
    "update_todo" to ToolInfo("Update TODO", "Update the agent's task checklist"),
    "report_intent" to ToolInfo("Intent", "Report current task intent"),
    "task" to ToolInfo("Sub-Agent Task", "Launch a sub-agent"),
    // PascalCase variants (some agents use these)
    "View" to ToolInfo("View", "View file or directory contents"),
    "Read" to ToolInfo("Read", "Read file contents"),
    "Write" to ToolInfo("Write", "Write file contents"),
    "Edit" to ToolInfo("Edit", "Make string replacements in a file"),
    "Create" to ToolInfo("Create", "Create a new file"),
    "Grep" to ToolInfo("Grep", "Search file contents"),
    "Glob" to ToolInfo("Glob", "Find files by pattern"),
    "Bash" to ToolInfo("Bash", "Run a shell command"),
    "Task" to ToolInfo("Sub-Agent Task", "Launch a sub-agent"),

    // ── OpenCode / Claude built-in tools ──
    "todowrite" to ToolInfo("Update TODO", "Update the agent's task list"),
    "TodoWrite" to ToolInfo("Update TODO", "Update the agent's task list"),
    "codesearch" to ToolInfo("Code Search", "Search external code libraries"),
    "CodeSearch" to ToolInfo("Code Search", "Search external code libraries"),
    "webfetch" to ToolInfo("Fetch URL", "Fetch a web page"),
    "WebFetch" to ToolInfo("Fetch URL", "Fetch a web page"),
    "websearch" to ToolInfo("Web Search", "Search the web"),
    "WebSearch" to ToolInfo("Web Search", "Search the web"),
    "skill" to ToolInfo("Skill", "Use an agent skill"),
    "Skill" to ToolInfo("Skill", "Use an agent skill"),

    // ── GitHub MCP tools (external server) ──
    "actions_get" to ToolInfo("GitHub Actions", "Get GitHub Actions resource"),
    "actions_list" to ToolInfo("GitHub Actions", "List GitHub Actions"),
    "get_commit" to ToolInfo("Get Commit", "Get GitHub commit details"),
    "get_file_contents" to ToolInfo("Get File", "Get GitHub file contents"),
    "get_job_logs" to ToolInfo("Job Logs", "Get GitHub Actions logs"),
    "issue_read" to ToolInfo("Read Issue", "Get GitHub issue"),
    "list_branches" to ToolInfo("List Branches", "List GitHub branches"),
    "list_commits" to ToolInfo("List Commits", "List GitHub commits"),
    "list_issues" to ToolInfo("List Issues", "List GitHub issues"),
    "list_pull_requests" to ToolInfo("List PRs", "List GitHub PRs"),
    "pull_request_read" to ToolInfo("Read PR", "Get GitHub PR"),
    "search_code" to ToolInfo("Search Code", "Search GitHub code"),
    "search_issues" to ToolInfo("Search Issues", "Search GitHub issues"),
    "search_pull_requests" to ToolInfo("Search PRs", "Search GitHub PRs"),
    "search_repositories" to ToolInfo("Search Repos", "Search GitHub repos"),
    "search_users" to ToolInfo("Search Users", "Search GitHub users"),
)
