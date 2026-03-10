package com.github.catatafishen.ideagentforcopilot.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for MCP tool input schemas (JSON Schema objects).
 * Ported from the duplicated schema definitions in the mcp-server module.
 * Used by {@link com.github.catatafishen.idemcpserver.McpProtocolHandler} to serve
 * complete tool schemas to any MCP client.
 */
public final class ToolSchemas {

    private static final Map<String, JsonObject> SCHEMAS;

    private ToolSchemas() {
    }

    /**
     * Get the input schema for a tool by name.
     * Returns an empty object schema if the tool has no defined schema.
     */
    public static JsonObject getInputSchema(String toolName) {
        JsonObject schema = SCHEMAS.get(toolName);
        return schema != null ? schema.deepCopy() : emptySchema();
    }

    private static JsonObject emptySchema() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", new JsonObject());
        s.add("required", new JsonArray());
        return s;
    }

    private static JsonObject schema(Object[][] params, String... required) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (Object[] p : params) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", (String) p[1]);
            prop.addProperty("description", (String) p[2]);
            if (p.length > 3 && p[3] != null) {
                prop.addProperty("default", (String) p[3]);
            }
            props.add((String) p[0], prop);
        }
        s.add("properties", props);
        JsonArray req = new JsonArray();
        for (String r : required) req.add(r);
        s.add("required", req);
        return s;
    }

    private static void addArrayItems(JsonObject schema, String propName) {
        JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject(propName);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        prop.add("items", items);
    }

    private static void addDictProperty(JsonObject schema, String name, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "object");
        prop.addProperty("description", description);
        prop.add("properties", new JsonObject());
        JsonObject additionalProps = new JsonObject();
        additionalProps.addProperty("type", "string");
        prop.add("additionalProperties", additionalProps);
        schema.getAsJsonObject("properties").add(name, prop);
    }

    static {
        Map<String, JsonObject> m = new LinkedHashMap<>();

        // ── Search & navigation ──────────────────────────────────────────────

        m.put("search_symbols", schema(new Object[][]{
            {"query", "string", "Symbol name to search for, or '*' to list all symbols in the project"},
            {"type", "string", "Optional: filter by type (class, method, field, property). Default: all types", ""}
        }, "query"));

        m.put("get_file_outline", schema(new Object[][]{
            {"path", "string", "Absolute or project-relative path to the file to outline"}
        }, "path"));

        m.put("get_class_outline", schema(new Object[][]{
            {"class_name", "string", "Fully qualified class name (e.g. 'java.util.ArrayList', 'com.intellij.openapi.project.Project')"},
            {"include_inherited", "boolean", "If true, include inherited methods and fields from superclasses. Default: false (own members only)"}
        }, "class_name"));

        m.put("find_references", schema(new Object[][]{
            {"symbol", "string", "The exact symbol name to search for"},
            {"file_pattern", "string", "Optional glob pattern to filter files (e.g., '*.java')", ""}
        }, "symbol"));

        m.put("list_project_files", schema(new Object[][]{
            {"directory", "string", "Optional subdirectory to list (relative to project root)", ""},
            {"pattern", "string", "Optional glob pattern (e.g., '*.java')", ""}
        }));

        m.put("search_text", schema(new Object[][]{
            {"query", "string", "Text or regex pattern to search for"},
            {"file_pattern", "string", "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", ""},
            {"regex", "boolean", "If true, treat query as regex. Default: false (literal match)"},
            {"case_sensitive", "boolean", "Case-sensitive search. Default: true"},
            {"max_results", "integer", "Maximum results to return (default: 100)"}
        }, "query"));

        m.put("list_tests", schema(new Object[][]{
            {"file_pattern", "string", "Optional glob pattern to filter test files (e.g., '*IntegrationTest*')", ""}
        }));

        m.put("run_tests", schema(new Object[][]{
            {"target", "string", "Test target: fully qualified class class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"},
            {"module", "string", "Optional Gradle module name (e.g., 'plugin-core')", ""}
        }, "target"));

        m.put("get_coverage", schema(new Object[][]{
            {"file", "string", "Optional file or class name to filter coverage results", ""}
        }));

        m.put("get_project_info", schema(new Object[][]{}));

        m.put("list_run_configurations", schema(new Object[][]{}));

        m.put("run_configuration", schema(new Object[][]{
            {"name", "string", "Exact name of the run configuration"}
        }, "name"));

        JsonObject createRunConfig = schema(new Object[][]{
            {"name", "string", "Name for the new run configuration"},
            {"type", "string", "Configuration type: 'application', 'junit', or 'gradle'"},
            {"jvm_args", "string", "Optional: JVM arguments (e.g., '-Xmx512m')"},
            {"program_args", "string", "Optional: program arguments"},
            {"working_dir", "string", "Optional: working directory path"},
            {"main_class", "string", "Optional: main class (for Application configs)"},
            {"test_class", "string", "Optional: test class (for JUnit configs)"},
            {"module_name", "string", "Optional: IntelliJ module name (from project structure)"},
            {"tasks", "string", "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"},
            {"script_parameters", "string", "Optional: Gradle script parameters (e.g., '--info')"},
            {"shared", "boolean", "Store as shared project file (default: true). If false, stored in workspace only"}
        }, "name", "type");
        addDictProperty(createRunConfig, "env", "Environment variables as key-value pairs");
        m.put("create_run_configuration", createRunConfig);

        JsonObject editRunConfig = schema(new Object[][]{
            {"name", "string", "Name of the run configuration to edit"},
            {"jvm_args", "string", "Optional: new JVM arguments"},
            {"program_args", "string", "Optional: new program arguments"},
            {"working_dir", "string", "Optional: new working directory"},
            {"tasks", "string", "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"},
            {"script_parameters", "string", "Optional: Gradle script parameters (e.g., '--info')"},
            {"shared", "boolean", "Optional: toggle shared (project file) vs workspace-local storage"}
        }, "name");
        addDictProperty(editRunConfig, "env", "Environment variables as key-value pairs");
        m.put("edit_run_configuration", editRunConfig);

        m.put("delete_run_configuration", schema(new Object[][]{
            {"name", "string", "Exact name of the run configuration to delete"}
        }, "name"));

        // ── Code quality ─────────────────────────────────────────────────────

        m.put("get_problems", schema(new Object[][]{
            {"path", "string", "Optional: file path to check. If omitted, checks all open files", ""}
        }));

        m.put("optimize_imports", schema(new Object[][]{
            {"path", "string", "Absolute or project-relative path to the file to optimize imports"}
        }, "path"));

        m.put("format_code", schema(new Object[][]{
            {"path", "string", "Absolute or project-relative path to the file to format"}
        }, "path"));

        m.put("get_highlights", schema(new Object[][]{
            {"path", "string", "Optional: file path to check. If omitted, checks all open files", ""},
            {"limit", "integer", "Maximum number of highlights to return (default: 100)"}
        }));

        m.put("get_compilation_errors", schema(new Object[][]{
            {"path", "string", "Optional: specific file to check. If omitted, checks all open source files", ""}
        }));

        m.put("run_inspections", schema(new Object[][]{
            {"scope", "string", "Optional: file or directory path to inspect. Examples: 'src/main/java/com/example/MyClass.java' or 'src/main/java/com/example'"},
            {"limit", "integer", "Page size (default: 100). Maximum problems per response"},
            {"offset", "integer", "Number of problems to skip (default: 0). Use for pagination"},
            {"min_severity", "string", "Minimum severity filter. Options: ERROR, WARNING, WEAK_WARNING, INFO. Default: all severities included. Only set this if the user explicitly asks to filter by severity."}
        }));

        m.put("add_to_dictionary", schema(new Object[][]{
            {"word", "string", "The word to add to the project dictionary"}
        }, "word"));

        m.put("suppress_inspection", schema(new Object[][]{
            {"path", "string", "Path to the file containing the code to suppress"},
            {"line", "integer", "Line number where the inspection finding is located"},
            {"inspection_id", "string", "The inspection ID to suppress (e.g., 'SpellCheckingInspection')"}
        }, "path", "line", "inspection_id"));

        m.put("run_qodana", schema(new Object[][]{
            {"limit", "integer", "Maximum number of problems to return (default: 100)"}
        }));

        m.put("run_sonarqube_analysis", schema(new Object[][]{
            {"scope", "string", "Analysis scope: 'all' (full project) or 'changed' (VCS changed files only). Default: 'all'"},
            {"limit", "integer", "Maximum number of findings to return. Default: 100"},
            {"offset", "integer", "Pagination offset. Default: 0"}
        }));

        // ── File operations ──────────────────────────────────────────────────

        m.put("intellij_read_file", schema(new Object[][]{
            {"path", "string", "Absolute or project-relative path to the file to read"},
            {"start_line", "integer", "Optional: first line to read (1-based, inclusive)"},
            {"end_line", "integer", "Optional: last line to read (1-based, inclusive). Use with start_line to read a range"}
        }, "path"));

        m.put("intellij_write_file", schema(new Object[][]{
            {"path", "string", "Absolute or project-relative path to the file to write or create"},
            {"content", "string", "Full file content to write (replaces entire file). Creates the file if it doesn't exist"},
            {"auto_format_and_optimize_imports", "boolean", "Auto-format code AND optimize imports after writing (default: true). \u26a0\ufe0f Import optimization REMOVES imports it considers unused \u2014 if you add imports in one edit and reference them in a later edit, set this to false or combine both changes in one edit"}
        }, "path", "content"));

        m.put("edit_text", schema(new Object[][]{
            {"path", "string", "Absolute or project-relative path to the file to edit"},
            {"old_str", "string", "Exact string to find and replace. Must match exactly one location in the file"},
            {"new_str", "string", "Replacement string"},
            {"auto_format_and_optimize_imports", "boolean", "Auto-format code AND optimize imports after editing (default: true). \u26a0\ufe0f Import optimization REMOVES imports it considers unused \u2014 if you add imports in one edit and reference them in a later edit, set this to false or combine both changes in one edit"}
        }, "path", "old_str", "new_str"));

        m.put("create_file", schema(new Object[][]{
            {"path", "string", "Path for the new file (absolute or project-relative). File must not already exist"},
            {"content", "string", "Content to write to the file"}
        }, "path", "content"));

        m.put("delete_file", schema(new Object[][]{
            {"path", "string", "Path to the file to delete (absolute or project-relative)"}
        }, "path"));

        m.put("rename_file", schema(new Object[][]{
            {"path", "string", "Path to the file to rename (absolute or project-relative)"},
            {"new_name", "string", "New file name (just the filename, not a full path)"}
        }, "path", "new_name"));

        m.put("move_file", schema(new Object[][]{
            {"path", "string", "Path to the file to move (absolute or project-relative)"},
            {"destination", "string", "Destination directory path (absolute or project-relative)"}
        }, "path", "destination"));

        m.put("reload_from_disk", schema(new Object[][]{
            {"path", "string", "File or directory path to reload (absolute or project-relative). Omit to reload the entire project root."}
        }));

        m.put("open_in_editor", schema(new Object[][]{
            {"file", "string", "Path to the file to open"},
            {"line", "integer", "Optional: line number to navigate to after opening"},
            {"focus", "boolean", "Optional: if true (default), the editor gets focus. Set to false to open without stealing focus"}
        }, "file"));

        m.put("show_diff", schema(new Object[][]{
            {"file", "string", "Path to the first file"},
            {"file2", "string", "Optional: path to second file for two-file comparison"},
            {"content", "string", "Optional: proposed new content to diff against the current file"},
            {"title", "string", "Optional: title for the diff viewer tab"}
        }, "file"));

        // ── Git ──────────────────────────────────────────────────────────────

        m.put("git_status", schema(new Object[][]{
            {"verbose", "boolean", "If true, show full 'git status' output including untracked files"}
        }));

        m.put("git_diff", schema(new Object[][]{
            {"staged", "boolean", "If true, show staged (cached) changes only"},
            {"commit", "string", "Compare against this commit (e.g., 'HEAD~1', branch name)"},
            {"path", "string", "Limit diff to this file path"},
            {"stat_only", "boolean", "If true, show only file stats (insertions/deletions), not full diff"}
        }));

        m.put("git_log", schema(new Object[][]{
            {"max_count", "integer", "Maximum number of commits to show (default: 10)"},
            {"format", "string", "Output format: 'oneline', 'short', 'medium', 'full'"},
            {"author", "string", "Filter commits by author name or email"},
            {"since", "string", "Show commits after this date (e.g., '2024-01-01')"},
            {"path", "string", "Show only commits touching this file"},
            {"branch", "string", "Show commits from this branch (default: current)"}
        }));

        m.put("git_blame", schema(new Object[][]{
            {"path", "string", "File path to blame"},
            {"line_start", "integer", "Start line number for partial blame"},
            {"line_end", "integer", "End line number for partial blame"}
        }, "path"));

        m.put("git_commit", schema(new Object[][]{
            {"message", "string", "Commit message (use conventional commit format)"},
            {"amend", "boolean", "If true, amend the previous commit instead of creating a new one"},
            {"all", "boolean", "If true, automatically stage all modified and deleted files"}
        }, "message"));

        JsonObject gitStage = schema(new Object[][]{
            {"path", "string", "Single file path to stage"},
            {"paths", "array", "Multiple file paths to stage"},
            {"all", "boolean", "If true, stage all changes (including untracked files)"}
        });
        addArrayItems(gitStage, "paths");
        m.put("git_stage", gitStage);

        JsonObject gitUnstage = schema(new Object[][]{
            {"path", "string", "Single file path to unstage"},
            {"paths", "array", "Multiple file paths to unstage"}
        });
        addArrayItems(gitUnstage, "paths");
        m.put("git_unstage", gitUnstage);

        m.put("git_branch", schema(new Object[][]{
            {"action", "string", "Action: 'list' (default), 'create', 'switch', 'delete'"},
            {"name", "string", "Branch name (required for create/switch/delete)"},
            {"base", "string", "Base ref for create (default: HEAD)"},
            {"all", "boolean", "For list: include remote branches"},
            {"force", "boolean", "For delete: force delete unmerged branches"}
        }));

        m.put("git_stash", schema(new Object[][]{
            {"action", "string", "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"},
            {"message", "string", "Stash message (for push action)"},
            {"index", "string", "Stash index (for pop/apply/drop, e.g., 'stash@{0}')"},
            {"include_untracked", "boolean", "For push: include untracked files"}
        }));

        m.put("git_revert", schema(new Object[][]{
            {"commit", "string", "Commit SHA to revert"},
            {"no_commit", "boolean", "If true, revert changes to working tree without creating a commit"},
            {"no_edit", "boolean", "If true, use the default commit message without editing"}
        }, "commit"));

        m.put("git_show", schema(new Object[][]{
            {"ref", "string", "Commit SHA, branch, tag, or ref (default: HEAD)"},
            {"stat_only", "boolean", "If true, show only file stats, not full diff content"},
            {"path", "string", "Limit output to this file path"}
        }));

        m.put("git_push", schema(new Object[][]{
            {"remote", "string", "Remote name (default: origin)"},
            {"branch", "string", "Branch to push (default: current)"},
            {"force", "boolean", "Force push"},
            {"set_upstream", "boolean", "Set upstream tracking reference"},
            {"tags", "boolean", "Push all tags"}
        }));

        m.put("git_remote", schema(new Object[][]{
            {"action", "string", "Action: 'list' (default), 'add', 'remove', 'set_url', 'get_url'"},
            {"name", "string", "Remote name (required for add/remove/set_url/get_url)"},
            {"url", "string", "Remote URL (required for add/set_url)"}
        }));

        m.put("git_fetch", schema(new Object[][]{
            {"remote", "string", "Remote name (default: origin)"},
            {"branch", "string", "Specific branch to fetch"},
            {"prune", "boolean", "Remove remote-tracking refs that no longer exist on the remote"},
            {"tags", "boolean", "Fetch all tags from the remote"}
        }));

        m.put("git_pull", schema(new Object[][]{
            {"remote", "string", "Remote name (default: origin)"},
            {"branch", "string", "Branch to pull (default: current tracking branch)"},
            {"rebase", "boolean", "If true, rebase instead of merge when pulling"},
            {"ff_only", "boolean", "If true, only fast-forward (abort if not possible)"}
        }));

        m.put("git_merge", schema(new Object[][]{
            {"branch", "string", "Branch to merge into current branch"},
            {"message", "string", "Custom merge commit message"},
            {"no_ff", "boolean", "Create a merge commit even for fast-forward merges"},
            {"ff_only", "boolean", "Only merge if fast-forward is possible"},
            {"squash", "boolean", "Squash all commits into a single commit (requires manual commit after)"},
            {"abort", "boolean", "Abort an in-progress merge"}
        }));

        m.put("git_rebase", schema(new Object[][]{
            {"branch", "string", "Branch to rebase onto"},
            {"onto", "string", "Rebase onto a specific commit (used with --onto)"},
            {"interactive", "boolean", "Start an interactive rebase"},
            {"autosquash", "boolean", "Automatically squash fixup! and squash! commits (requires interactive)"},
            {"abort", "boolean", "Abort an in-progress rebase"},
            {"continue_rebase", "boolean", "Continue a paused rebase after resolving conflicts"},
            {"skip", "boolean", "Skip the current patch and continue rebase"}
        }));

        JsonObject gitCherryPick = schema(new Object[][]{
            {"commits", "array", "One or more commit SHAs to cherry-pick"},
            {"no_commit", "boolean", "Apply changes without creating commits"},
            {"abort", "boolean", "Abort an in-progress cherry-pick"},
            {"continue_pick", "boolean", "Continue cherry-pick after resolving conflicts"}
        });
        addArrayItems(gitCherryPick, "commits");
        m.put("git_cherry_pick", gitCherryPick);

        m.put("git_tag", schema(new Object[][]{
            {"action", "string", "Action: 'list' (default), 'create', 'delete'"},
            {"name", "string", "Tag name (required for create/delete)"},
            {"commit", "string", "Commit to tag (default: HEAD, for create)"},
            {"message", "string", "Tag message (for annotated tags)"},
            {"annotate", "boolean", "Create an annotated tag (requires message)"},
            {"pattern", "string", "Glob pattern to filter tags (for list)"},
            {"sort", "string", "Sort field for list (e.g., '-creatordate' for newest first)"}
        }));

        m.put("git_reset", schema(new Object[][]{
            {"commit", "string", "Target commit (default: HEAD)"},
            {"mode", "string", "Reset mode: 'soft' (keep staged), 'mixed' (default, unstage), 'hard' (discard all changes)"},
            {"path", "string", "Reset a specific file path (unstages it)"}
        }));

        m.put("get_file_history", schema(new Object[][]{
            {"path", "string", "Path to the file to get history for (absolute or project-relative)"},
            {"max_count", "integer", "Maximum number of commits to show (default: 20)"}
        }, "path"));

        // ── Infrastructure ───────────────────────────────────────────────────

        JsonObject httpRequest = schema(new Object[][]{
            {"url", "string", "Full URL to request (e.g., http://localhost:8080/api)"},
            {"method", "string", "HTTP method: GET (default), POST, PUT, PATCH, DELETE"},
            {"body", "string", "Request body (for POST/PUT/PATCH)"}
        }, "url");
        addDictProperty(httpRequest, "headers", "Request headers as key-value pairs");
        m.put("http_request", httpRequest);

        m.put("run_command", schema(new Object[][]{
            {"command", "string", "Shell command to execute (e.g., 'gradle build', 'cat file.txt')"},
            {"timeout", "integer", "Timeout in seconds (default: 60)"},
            {"title", "string", "Human-readable title for the Run panel tab. ALWAYS set this to a short descriptive name"},
            {"offset", "integer", "Character offset to start output from (default: 0). Use for pagination when output is truncated"},
            {"max_chars", "integer", "Maximum characters to return per page (default: 8000)"}
        }, "command"));

        m.put("read_ide_log", schema(new Object[][]{
            {"lines", "integer", "Number of recent lines to return (default: 50)"},
            {"filter", "string", "Only return lines containing this text"},
            {"level", "string", "Filter by log level: INFO, WARN, ERROR"}
        }));

        m.put("get_notifications", schema(new Object[][]{}));

        m.put("read_run_output", schema(new Object[][]{
            {"tab_name", "string", "Name of the Run tab to read (default: most recent)"},
            {"max_chars", "integer", "Maximum characters to return (default: 8000)"}
        }));

        // ── Terminal ─────────────────────────────────────────────────────────

        m.put("run_in_terminal", schema(new Object[][]{
            {"command", "string", "The command to run in the terminal"},
            {"tab_name", "string", "Name for the terminal tab. If omitted, reuses the most recent agent-created tab or creates a new one"},
            {"new_tab", "boolean", "If true, always create a new terminal tab instead of reusing an existing one"},
            {"shell", "string", "Shell to use (e.g., 'bash', 'zsh'). If omitted, uses the default shell"}
        }, "command"));

        m.put("write_terminal_input", schema(new Object[][]{
            {"input", "string", "Text or keystrokes to send. Supports escape sequences: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, {backspace}, \\n, \\t"},
            {"tab_name", "string", "Name of the terminal tab to write to. If omitted, writes to the currently selected tab"}
        }, "input"));

        m.put("read_terminal_output", schema(new Object[][]{
            {"tab_name", "string", "Name of the terminal tab to read from"},
            {"max_lines", "integer", "Maximum number of lines to return from the end of the terminal buffer (default: 50). Use 0 for the full buffer."}
        }));

        m.put("list_terminals", schema(new Object[][]{}));

        // ── Documentation ────────────────────────────────────────────────────

        m.put("get_documentation", schema(new Object[][]{
            {"symbol", "string", "Fully qualified symbol name (e.g. java.util.List)"}
        }, "symbol"));

        m.put("download_sources", schema(new Object[][]{
            {"library", "string", "Optional library name filter (e.g. 'junit')"}
        }));

        // ── Scratch files ────────────────────────────────────────────────────

        m.put("create_scratch_file", schema(new Object[][]{
            {"name", "string", "Scratch file name with extension (e.g., 'test.py', 'notes.md')"},
            {"content", "string", "The content to write to the scratch file"}
        }, "name", "content"));

        m.put("list_scratch_files", schema(new Object[][]{}));

        m.put("run_scratch_file", schema(new Object[][]{
            {"name", "string", "Scratch file name with extension (e.g., 'test.kts', 'MyApp.java', 'hello.js')"},
            {"module", "string", "Optional: module name for classpath (e.g., 'plugin-core')"},
            {"interactive", "boolean", "Optional: enable interactive/REPL mode (Kotlin scripts)"}
        }, "name"));

        // ── IDE & project ────────────────────────────────────────────────────

        m.put("get_indexing_status", schema(new Object[][]{
            {"wait", "boolean", "If true, blocks until indexing finishes"},
            {"timeout", "integer", "Max seconds to wait when wait=true (default: 30)"}
        }));

        m.put("get_active_file", schema(new Object[][]{}));

        m.put("get_open_editors", schema(new Object[][]{}));

        m.put("list_themes", schema(new Object[][]{}));

        m.put("set_theme", schema(new Object[][]{
            {"theme", "string", "Theme name or partial name (e.g., 'Darcula', 'Light')"}
        }, "theme"));

        m.put("build_project", schema(new Object[][]{
            {"module", "string", "Optional: build only a specific module (e.g., 'plugin-core')"}
        }));

        m.put("mark_directory", schema(new Object[][]{
            {"path", "string", "Directory path (absolute or project-relative)"},
            {"type", "string", "Directory type: 'sources', 'test_sources', 'resources', 'test_resources', 'generated_sources', 'excluded', or 'unmark' to remove marking"}
        }, "path", "type"));

        m.put("edit_project_structure", schema(new Object[][]{
            {"action", "string", "Action: 'list_modules', 'list_dependencies', 'add_dependency', 'remove_dependency', 'list_sdks', 'add_sdk', 'remove_sdk'"},
            {"module", "string", "Module name (required for list_dependencies, add_dependency, remove_dependency)"},
            {"dependency_name", "string", "Name of the dependency to add or remove"},
            {"dependency_type", "string", "Type of dependency to add: 'library' (default) or 'module'"},
            {"scope", "string", "Dependency scope: 'COMPILE' (default), 'TEST', 'RUNTIME', 'PROVIDED'"},
            {"jar_path", "string", "Path to JAR file (absolute or project-relative). Required when adding a library dependency"},
            {"sdk_type", "string", "SDK type name for add_sdk (e.g., 'Python SDK', 'JavaSDK'). Use list_sdks to see available types"},
            {"sdk_name", "string", "SDK name for remove_sdk. Use list_sdks to see configured SDK names"},
            {"home_path", "string", "Home path for add_sdk. Use list_sdks to see suggested paths for each SDK type"}
        }, "action"));

        // ── Refactoring ──────────────────────────────────────────────────────

        m.put("apply_quickfix", schema(new Object[][]{
            {"file", "string", "Path to the file containing the problem"},
            {"line", "integer", "Line number where the problem is located"},
            {"inspection_id", "string", "The inspection ID from run_inspections output (e.g., 'unused')"},
            {"fix_index", "integer", "Which fix to apply if multiple are available (default: 0)"}
        }, "file", "line", "inspection_id"));

        m.put("refactor", schema(new Object[][]{
            {"operation", "string", "Refactoring type: 'rename', 'extract_method', 'inline', or 'safe_delete'"},
            {"file", "string", "Path to the file containing the symbol"},
            {"symbol", "string", "Name of the symbol to refactor (class, method, field, or variable)"},
            {"line", "integer", "Line number to disambiguate if multiple symbols share the same name"},
            {"new_name", "string", "New name for 'rename' operation. Required when operation is 'rename'"}
        }, "operation", "file", "symbol"));

        m.put("replace_symbol_body", schema(new Object[][]{
            {"file", "string", "Path to the file containing the symbol"},
            {"symbol", "string", "Name of the symbol to replace (method, class, function, or field)"},
            {"new_body", "string", "The complete new definition to replace the symbol with"},
            {"line", "integer", "Optional: line number hint to disambiguate if multiple symbols share the same name"}
        }, "file", "symbol", "new_body"));

        m.put("insert_before_symbol", schema(new Object[][]{
            {"file", "string", "Path to the file containing the symbol"},
            {"symbol", "string", "Name of the symbol to insert before"},
            {"content", "string", "The content to insert before the symbol"},
            {"line", "integer", "Optional: line number hint to disambiguate if multiple symbols share the same name"}
        }, "file", "symbol", "content"));

        m.put("insert_after_symbol", schema(new Object[][]{
            {"file", "string", "Path to the file containing the symbol"},
            {"symbol", "string", "Name of the symbol to insert after"},
            {"content", "string", "The content to insert after the symbol"},
            {"line", "integer", "Optional: line number hint to disambiguate if multiple symbols share the same name"}
        }, "file", "symbol", "content"));

        m.put("go_to_declaration", schema(new Object[][]{
            {"file", "string", "Path to the file containing the symbol usage"},
            {"symbol", "string", "Name of the symbol to look up"},
            {"line", "integer", "Line number where the symbol appears"}
        }, "file", "symbol", "line"));

        m.put("get_type_hierarchy", schema(new Object[][]{
            {"symbol", "string", "Fully qualified or simple class/interface name"},
            {"direction", "string", "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both"}
        }, "symbol"));

        m.put("find_implementations", schema(new Object[][]{
            {"symbol", "string", "Class, interface, or method name to find implementations for"},
            {"file", "string", "Optional: file path for method context (required when searching for method overrides)"},
            {"line", "integer", "Optional: line number to disambiguate the method (required when searching for method overrides)"}
        }, "symbol"));

        m.put("get_call_hierarchy", schema(new Object[][]{
            {"symbol", "string", "Method name to find callers for"},
            {"file", "string", "Path to the file containing the method definition"},
            {"line", "integer", "Line number where the method is defined"}
        }, "symbol", "file", "line"));

        m.put("undo", schema(new Object[][]{
            {"path", "string", "Path to the file to undo changes on"},
            {"count", "integer", "Number of undo steps (default: 1). Each write + auto-format counts as 2 steps"}
        }, "path"));

        m.put("redo", schema(new Object[][]{
            {"path", "string", "Path to the file to redo changes on"},
            {"count", "integer", "Number of redo steps (default: 1)"}
        }, "path"));

        // ── Other ────────────────────────────────────────────────────────────

        m.put("get_chat_html", schema(new Object[][]{}));

        m.put("search_conversation_history", schema(new Object[][]{
            {"query", "string", "Text to search for across conversations (case-insensitive)"},
            {"file", "string", "Conversation to read: 'current' for the active session, or an archive timestamp (e.g., '2026-03-04T15-30-00')"},
            {"max_chars", "integer", "Maximum characters to return (default: 8000)"}
        }));

        SCHEMAS = Collections.unmodifiableMap(m);
    }
}
