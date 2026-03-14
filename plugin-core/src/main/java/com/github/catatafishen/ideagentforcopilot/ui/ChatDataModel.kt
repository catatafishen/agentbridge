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

internal sealed class EntryData {
    class Prompt(
        val text: String,
        val timestamp: String = "",
        val contextFiles: List<Triple<String, String, Int>>? = null
    ) : EntryData()

    class Text(val raw: StringBuilder = StringBuilder()) : EntryData()
    class Thinking(val raw: StringBuilder = StringBuilder()) : EntryData()
    class ToolCall(
        val title: String,
        val arguments: String? = null,
        val kind: String = "other",
        var result: String? = null,
        var status: String? = null
    ) : EntryData()

    class SubAgent(
        val agentType: String,
        val description: String,
        val prompt: String? = null,
        var result: String? = null,
        var status: String? = null,
        var colorIndex: Int = 0,
        val callId: String? = null
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
    "explore" to SubAgentInfo("Explore Agent"),
    "task" to SubAgentInfo("Task Agent"),
    AGENT_TYPE_GENERAL to SubAgentInfo("General Agent"),
    "code-review" to SubAgentInfo("Code Review Agent"),
    "ui-reviewer" to SubAgentInfo("UI Review Agent"),
)

/** JSON key to use as subtitle in the chip label for specific tools */
internal val TOOL_SUBTITLE_KEY = mapOf(
    // File operations
    "read_file" to "path",
    "intellij_read_file" to "path",
    "write_file" to "path",
    "intellij_write_file" to "path",
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
    // Built-in CLI tools
    "view" to "path",
    "edit" to "path",
    "create" to "path",
    "grep" to "pattern",
    "glob" to "pattern",
    "bash" to "description",
    "web_search" to "query",
    "web_fetch" to "url",
)

internal val TOOL_DISPLAY_INFO = mapOf(
    // Code Navigation
    "search_symbols" to ToolInfo(
        "Search Symbols",
        "Search for classes, methods, and fields across the project"
    ),
    "get_file_outline" to ToolInfo(
        "File Outline",
        "Get the structure outline of a file (classes, methods, fields)"
    ),
    "find_references" to ToolInfo("Find References", "Find all usages of a symbol across the project"),
    "list_project_files" to ToolInfo("List Project Files", "List files and directories in the project tree"),
    // File Operations
    "read_file" to ToolInfo("Read File", "Read the contents of a file"),
    "intellij_read_file" to ToolInfo("Read File", "Read the contents of a file via IntelliJ"),
    "write_file" to ToolInfo("Write File", "Write or overwrite the contents of a file"),
    "intellij_write_file" to ToolInfo("Write File", "Write or overwrite file contents via IntelliJ"),
    "create_file" to ToolInfo("Create File", "Create a new file with the given content"),
    "delete_file" to ToolInfo("Delete File", "Delete a file from the project"),
    // Code Quality
    "get_problems" to ToolInfo("Get Problems", "Get current problems/warnings from the Problems panel"),
    "get_highlights" to ToolInfo("Get Highlights", "Get cached editor highlights for open files"),
    "run_inspections" to ToolInfo("Run Inspections", "Run the full IntelliJ inspection engine on the project"),
    "get_compilation_errors" to ToolInfo(
        "Check Compilation",
        "Fast compilation error check using cached daemon results"
    ),
    "apply_quickfix" to ToolInfo("Apply Quick Fix", "Apply an IntelliJ quick-fix to resolve an issue"),
    "suppress_inspection" to ToolInfo("Suppress Inspection", "Suppress an inspection warning"),
    "optimize_imports" to ToolInfo("Optimize Imports", "Remove unused imports and organize remaining ones"),
    "format_code" to ToolInfo("Format Code", "Reformat code according to project style settings"),
    "add_to_dictionary" to ToolInfo("Add to Dictionary", "Add a word to the spell-check dictionary"),
    "run_qodana" to ToolInfo("Run Qodana", "Run Qodana static analysis on the project"),
    "run_sonarqube_analysis" to ToolInfo("Run SonarQube", "Run SonarQube for IDE analysis on the project"),
    "get_sonar_rule_description" to ToolInfo("Get SonarQube Rule", "Fetch SonarQube rule descriptions by rule ID"),
    // Refactoring
    "refactor" to ToolInfo("Refactor", "Refactor code (rename, extract method, inline, safe delete)"),
    "go_to_declaration" to ToolInfo("Go to Declaration", "Navigate to the declaration of a symbol"),
    "get_type_hierarchy" to ToolInfo("Type Hierarchy", "Show supertypes and subtypes of a class"),
    "get_documentation" to ToolInfo("Get Documentation", "Retrieve documentation for a symbol"),
    // Tests
    "list_tests" to ToolInfo("List Tests", "List available test classes and methods"),
    "run_tests" to ToolInfo("Run Tests", "Execute tests and return results"),
    "get_test_results" to ToolInfo("Test Results", "Get results from the last test run"),
    "get_coverage" to ToolInfo("Get Coverage", "Get code coverage data from the last run"),
    // Git
    "git_status" to ToolInfo("Git Status", "Show working tree status"),
    "git_diff" to ToolInfo("Git Diff", "Show changes between commits, working tree, etc."),
    "git_log" to ToolInfo("Git Log", "Show commit history"),
    "git_blame" to ToolInfo("Git Blame", "Show line-by-line authorship of a file"),
    "git_commit" to ToolInfo("Git Commit", "Record changes to the repository"),
    "git_stage" to ToolInfo("Git Stage", "Stage files for commit"),
    "git_unstage" to ToolInfo("Git Unstage", "Unstage files from the index"),
    "git_branch" to ToolInfo("Git Branch", "List, create, or switch branches"),
    "git_stash" to ToolInfo("Git Stash", "Stash changes in working directory"),
    "git_show" to ToolInfo("Git Show", "Show details of a commit"),
    // Project
    "get_project_info" to ToolInfo("Project Info", "Get project name, SDK, modules, and settings"),
    "build_project" to ToolInfo("Build Project", "Trigger incremental compilation of the project"),
    "get_indexing_status" to ToolInfo("Indexing Status", "Check if IntelliJ is currently indexing"),
    "download_sources" to ToolInfo("Download Sources", "Download source jars for dependencies"),
    // Infrastructure
    "http_request" to ToolInfo("HTTP Request", "Make an HTTP request"),
    "run_command" to ToolInfo("Run Command", "Run a shell command"),
    "read_ide_log" to ToolInfo("Read IDE Log", "Read recent entries from the IDE log"),
    "get_notifications" to ToolInfo("Get Notifications", "Get IDE notification messages"),
    "read_run_output" to ToolInfo("Read Run Output", "Read output from a run configuration"),
    // Terminal
    "run_in_terminal" to ToolInfo("Run in Terminal", "Run a command in the IDE terminal"),
    "read_terminal_output" to ToolInfo("Terminal Output", "Read output from a terminal session"),
    "list_terminals" to ToolInfo("List Terminals", "List active terminal sessions"),
    // Editor
    "open_in_editor" to ToolInfo("Open in Editor", "Open a file in the editor"),
    "show_diff" to ToolInfo("Show Diff", "Show a diff view between two contents"),
    "create_scratch_file" to ToolInfo("Create Scratch", "Create a scratch file for quick experiments"),
    "list_scratch_files" to ToolInfo("List Scratches", "List available scratch files"),
    // Run Configurations
    "list_run_configurations" to ToolInfo("List Run Configs", "List available run/debug configurations"),
    "run_configuration" to ToolInfo("Run Configuration", "Execute a run/debug configuration"),
    "create_run_configuration" to ToolInfo("Create Run Config", "Create a new run/debug configuration"),
    "edit_run_configuration" to ToolInfo("Edit Run Config", "Modify an existing run/debug configuration"),
    // Display / Presentation
    "show_file" to ToolInfo("Show File", "Display a file to the user"),
    // Agent Meta
    "update_todo" to ToolInfo("Update TODO", "Update the agent's task checklist"),
    "report_intent" to ToolInfo("Intent", "Report current task intent"),
    "task" to ToolInfo("Sub-Agent Task", "Launch a specialized sub-agent for a task"),
    // Built-in CLI tools (Copilot agent)
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
    "web_search" to ToolInfo("Web Search", "AI-powered web search with citations"),
    "web_fetch" to ToolInfo("Fetch URL", "Fetch a web page and return its content"),
    // GitHub MCP tools
    "actions_get" to ToolInfo("GitHub Actions", "Get details about a GitHub Actions resource"),
    "actions_list" to ToolInfo("GitHub Actions", "List GitHub Actions workflows, runs, or jobs"),
    "get_commit" to ToolInfo("Get Commit", "Get details for a GitHub commit"),
    "get_file_contents" to ToolInfo("Get File", "Get file contents from a GitHub repository"),
    "get_job_logs" to ToolInfo("Job Logs", "Get logs for GitHub Actions workflow jobs"),
    "issue_read" to ToolInfo("Read Issue", "Get information about a GitHub issue"),
    "list_branches" to ToolInfo("List Branches", "List branches in a GitHub repository"),
    "list_commits" to ToolInfo("List Commits", "List commits in a GitHub repository"),
    "list_issues" to ToolInfo("List Issues", "List issues in a GitHub repository"),
    "list_pull_requests" to ToolInfo("List PRs", "List pull requests in a GitHub repository"),
    "pull_request_read" to ToolInfo("Read PR", "Get information about a GitHub pull request"),
    "search_code" to ToolInfo("Search Code", "Search code across GitHub repositories"),
    "search_issues" to ToolInfo("Search Issues", "Search for GitHub issues"),
    "search_pull_requests" to ToolInfo("Search PRs", "Search for GitHub pull requests"),
    "search_repositories" to ToolInfo("Search Repos", "Search for GitHub repositories"),
    "search_users" to ToolInfo("Search Users", "Search for GitHub users"),
    // IntelliJ extras
    "get_class_outline" to ToolInfo("Class Outline", "Show constructors, methods, and fields of a class"),
    "search_text" to ToolInfo("Search Text", "Search text or regex patterns across project files"),
    "undo" to ToolInfo("Undo", "Undo last edit action on a file"),
)
