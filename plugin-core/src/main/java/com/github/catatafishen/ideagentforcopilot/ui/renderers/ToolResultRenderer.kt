package com.github.catatafishen.ideagentforcopilot.ui.renderers

import java.util.*

/**
 * Interface for custom tool-result renderers in the chat panel.
 * Each implementation transforms raw tool output text into styled HTML.
 */
internal fun interface ToolResultRenderer {
    /**
     * Render tool output as HTML, or return null to fall back to default `<pre><code>`.
     */
    fun render(output: String): String?
}

/**
 * Registry of tool-result renderers and shared HTML utilities.
 */
internal object ToolRenderers {

    private val registry: Map<String, ToolResultRenderer> = mapOf(
        // Git
        "git_commit" to GitCommitRenderer,
        "git_status" to GitStatusRenderer,
        "git_diff" to GitDiffRenderer,
        "git_log" to GitLogRenderer,
        "git_show" to GitShowRenderer,
        "git_blame" to GitBlameRenderer,
        "git_branch" to GitBranchRenderer,
        "git_tag" to GitTagRenderer,
        "git_stash" to GitStashRenderer,
        "git_stage" to GitStageRenderer,
        // Build & test
        "build_project" to BuildResultRenderer,
        "run_tests" to TestResultRenderer,
        "list_tests" to ListTestsRenderer,
        "list_run_configurations" to RunConfigRenderer,
        "get_coverage" to CoverageRenderer,
        // Code quality
        "run_inspections" to InspectionResultRenderer,
        "get_compilation_errors" to InspectionResultRenderer,
        "get_highlights" to InspectionResultRenderer,
        "get_problems" to InspectionResultRenderer,
        // Search & navigation
        "search_text" to SearchResultRenderer,
        "search_symbols" to SearchResultRenderer,
        "find_references" to SearchResultRenderer,
        "get_file_outline" to FileOutlineRenderer,
        "get_class_outline" to ClassOutlineRenderer,
        // Navigation
        "go_to_declaration" to GoToDeclarationRenderer,
        "get_type_hierarchy" to TypeHierarchyRenderer,
        // Refactoring
        "refactor" to RefactorRenderer,
        // Project & files
        "list_project_files" to ListProjectFilesRenderer,
        "get_project_info" to ProjectInfoRenderer,
        // Infrastructure
        "run_command" to RunCommandRenderer,
        // HTTP
        "http_request" to HttpRequestRenderer,
        // File I/O
        "intellij_read_file" to ReadFileRenderer,
        "read_file" to ReadFileRenderer,
        "intellij_write_file" to WriteFileRenderer,
        "write_file" to WriteFileRenderer,
        "create_file" to WriteFileRenderer,
        // Symbol editing
        "replace_symbol_body" to ReplaceSymbolRenderer,
        "insert_before_symbol" to ReplaceSymbolRenderer,
        "insert_after_symbol" to ReplaceSymbolRenderer,
    )

    fun get(toolName: String): ToolResultRenderer? = registry[toolName]

    fun hasRenderer(toolName: String): Boolean = toolName in registry

    // ── Shared HTML utilities ─────────────────────────────────

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    data class DiffStats(val files: String, val insertions: String, val deletions: String)

    fun parseDiffStats(line: String): DiffStats {
        val filesMatch = Regex("""\d+ files? changed""").find(line)
        val insMatch = Regex("""(\d+) insertions?\(\+\)""").find(line)
        val delMatch = Regex("""(\d+) deletions?\(-\)""").find(line)
        return DiffStats(
            files = filesMatch?.value ?: line,
            insertions = insMatch?.groupValues?.get(1) ?: "",
            deletions = delMatch?.groupValues?.get(1) ?: "",
        )
    }
}
