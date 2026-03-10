package com.github.catatafishen.ideagentforcopilot.services;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registry of all tools the agent can use, both built-in agent tools
 * and MCP tools we provide via IntelliJ.
 */
public final class ToolRegistry {

    public enum Category {
        FILE("File Operations"),
        SEARCH("Search & Navigation"),
        CODE_QUALITY("Code Quality"),
        BUILD("Build / Run / Test"),
        RUN("Terminal & Commands"),
        GIT("Git"),
        REFACTOR("Refactoring"),
        IDE("IDE & Project"),
        SHELL("Shell (built-in)"),
        OTHER("Other"),
        MACRO("Recorded Macros");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    public static final class ToolEntry {
        public final String id;
        public final String displayName;
        /**
         * One-line description shown as a tooltip in the settings panel.
         */
        public final String description;
        public final Category category;
        /**
         * True = agent built-in tool; excluded via excludedTools in session/new for agents that
         * support it (e.g. OpenCode). Cannot be disabled in Copilot CLI (ACP bug #556).
         */
        public final boolean isBuiltIn;
        /**
         * True = this built-in tool fires a permission request that we can intercept.
         * False (and isBuiltIn=true) = runs silently with no hook.
         */
        public final boolean hasDenyControl;
        /**
         * True = tool accepts a file path; supports inside-project / outside-project
         * sub-permissions.
         */
        public final boolean supportsPathSubPermissions;

        public ToolEntry(String id, String displayName, String description, Category category,
                         boolean isBuiltIn, boolean hasDenyControl, boolean supportsPathSubPermissions) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.category = category;
            this.isBuiltIn = isBuiltIn;
            this.hasDenyControl = hasDenyControl;
            this.supportsPathSubPermissions = supportsPathSubPermissions;
        }
    }

    private static final List<ToolEntry> ALL_TOOLS = Collections.unmodifiableList(Arrays.asList(
        // -- Built-in agent tools ---------------------------------------------------------
        // Copilot CLI: view/read/grep/glob run silently -- no permission hook (hasDenyControl=false)
        // OpenCode:    read/grep/glob/list run silently -- no permission hook (hasDenyControl=false)
        new ToolEntry("view", "Read File (built-in)", "Read file contents from disk (Copilot CLI built-in)", Category.FILE, true, false, true),
        new ToolEntry("read", "Read File (built-in)", "Read file contents from disk (built-in)", Category.FILE, true, false, true),
        new ToolEntry("grep", "Grep Search (built-in)", "Search file contents with regular expressions (built-in)", Category.SEARCH, true, false, false),
        new ToolEntry("glob", "Glob Find (built-in)", "Find files by name pattern (built-in)", Category.SEARCH, true, false, false),
        new ToolEntry("list", "List Files (built-in)", "List files and directories (OpenCode built-in)", Category.SEARCH, true, false, true),
        new ToolEntry("bash", "Bash Shell (built-in)", "Run arbitrary shell commands -- use run_command instead for safer, paginated execution", Category.SHELL, true, true, false),
        // edit/write/create/execute/runInTerminal fire permission requests (hasDenyControl=true)
        new ToolEntry("edit", "Edit File (built-in)", "Edit a file in place (built-in) -- use edit_text or replace_symbol_body for IDE-aware editing", Category.FILE, true, true, true),
        new ToolEntry("write", "Write File (built-in)", "Create or overwrite a file (OpenCode built-in) -- use intellij_write_file for IDE-aware writing", Category.FILE, true, true, true),
        new ToolEntry("create", "Create File (built-in)", "Create a new file (Copilot CLI built-in) -- use create_file for IDE-aware creation", Category.FILE, true, true, true),
        new ToolEntry("execute", "Execute (built-in)", "Execute a shell command (built-in)", Category.SHELL, true, true, false),
        new ToolEntry("runInTerminal", "Run in Terminal (built-in)", "Run a command in the integrated terminal (Copilot CLI built-in)", Category.SHELL, true, true, false),

        // -- File operations --------------------------------------------------------------
        new ToolEntry("intellij_read_file", "Read File", "Read a file via IntelliJ's editor buffer -- always returns the current in-memory content", Category.FILE, false, false, true),
        new ToolEntry("intellij_write_file", "Write File", "Write full file content or create a new file through IntelliJ's editor buffer", Category.FILE, false, false, true),
        new ToolEntry("edit_text", "Edit Text", "Surgical find-and-replace edit within a file -- for small changes inside methods, imports, or config", Category.FILE, false, false, true),
        new ToolEntry("create_file", "Create File", "Create a new file and register it in IntelliJ's VFS", Category.FILE, false, false, true),
        new ToolEntry("delete_file", "Delete File", "Delete a file from the project via IntelliJ", Category.FILE, false, false, true),
        new ToolEntry("rename_file", "Rename File", "Rename a file in place without moving it to a different directory", Category.FILE, false, false, true),
        new ToolEntry("move_file", "Move File", "Move a file to a different directory", Category.FILE, false, false, true),
        new ToolEntry("reload_from_disk", "Reload from Disk", "Force IntelliJ to refresh a file or directory from disk, picking up changes made by external tools", Category.FILE, false, false, true),
        new ToolEntry("open_in_editor", "Open in Editor", "Open a file in the editor, optionally navigating to a specific line", Category.FILE, false, false, true),
        new ToolEntry("show_diff", "Show Diff", "Show a diff viewer comparing a file to proposed content or another file", Category.FILE, false, false, false),

        // -- Search & navigation ----------------------------------------------------------
        new ToolEntry("search_symbols", "Search Symbols", "Search for classes, methods, or fields by name using IntelliJ's symbol index", Category.SEARCH, false, false, false),
        new ToolEntry("search_text", "Search Text", "Search for text or regex patterns across project files using IntelliJ's editor buffers", Category.SEARCH, false, false, false),
        new ToolEntry("find_references", "Find References", "Find all usages of a symbol throughout the project", Category.SEARCH, false, false, false),
        new ToolEntry("go_to_declaration", "Go to Declaration", "Navigate to the declaration of a symbol at a given file and line", Category.SEARCH, false, false, false),
        new ToolEntry("get_file_outline", "Get File Outline", "Get the structure of a file -- classes, methods, and fields with line numbers", Category.SEARCH, false, false, false),
        new ToolEntry("get_class_outline", "Get Class Outline", "Get the full API of any class by fully-qualified name, including library and JDK classes", Category.SEARCH, false, false, false),
        new ToolEntry("get_type_hierarchy", "Get Type Hierarchy", "Show supertypes and/or subtypes of a class or interface", Category.SEARCH, false, false, false),
        new ToolEntry("find_implementations", "Find Implementations", "Find all implementations of a class/interface or overrides of a method", Category.SEARCH, false, false, false),
        new ToolEntry("get_call_hierarchy", "Get Call Hierarchy", "Find all callers of a method with file paths and line numbers", Category.SEARCH, false, false, false),

        // -- Code quality -----------------------------------------------------------------
        new ToolEntry("run_inspections", "Run Inspections", "Run IntelliJ's full inspection engine on the project or a specific scope", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("run_qodana", "Run Qodana", "Run Qodana static analysis and return findings", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("run_sonarqube_analysis", "Run SonarQube", "Run SonarQube for IDE (SonarLint) analysis on the full project or changed files", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("get_problems", "Get Problems", "Get cached editor problems (errors/warnings) for open files", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("get_highlights", "Get Highlights", "Get cached editor highlights for open files", Category.CODE_QUALITY, false, false, false),
        new ToolEntry("get_compilation_errors", "Get Compilation Errors", "Fast compilation error check using cached daemon results -- much faster than a full build", Category.CODE_QUALITY, false, false, false),

        // -- Build / Run / Test -----------------------------------------------------------
        new ToolEntry("build_project", "Build Project", "Trigger incremental compilation of the project or a specific module", Category.BUILD, false, false, false),
        new ToolEntry("run_tests", "Run Tests", "Run tests by class, method, or wildcard pattern via Gradle", Category.BUILD, false, false, false),
        new ToolEntry("get_coverage", "Get Coverage", "Retrieve code coverage data, optionally filtered by file or class", Category.BUILD, false, false, false),
        new ToolEntry("run_configuration", "Run Configuration", "Execute an existing run configuration by name", Category.BUILD, false, false, false),
        new ToolEntry("create_run_configuration", "Create Run Config", "Create a new run configuration (application, JUnit, or Gradle)", Category.BUILD, false, false, false),
        new ToolEntry("edit_run_configuration", "Edit Run Config", "Edit an existing run configuration's arguments, environment, or working directory", Category.BUILD, false, false, false),
        new ToolEntry("delete_run_configuration", "Delete Run Config", "Delete a run configuration by name", Category.BUILD, false, false, false),
        new ToolEntry("list_run_configurations", "List Run Configs", "List all available run configurations in the project", Category.BUILD, false, false, false),

        // -- Terminal & commands ----------------------------------------------------------
        new ToolEntry("run_command", "Run Command", "Run a shell command with paginated output -- prefer this over the built-in bash tool", Category.RUN, false, false, false),
        new ToolEntry("run_in_terminal", "Run in Terminal", "Run a command in IntelliJ's integrated terminal", Category.RUN, false, false, false),
        new ToolEntry("write_terminal_input", "Write Terminal Input", "Send raw text or keystrokes to a running terminal (e.g. answer prompts, send Ctrl-C)", Category.RUN, false, false, false),
        new ToolEntry("read_run_output", "Read Run Output", "Read output from a recent Run panel tab by name", Category.RUN, false, false, false),
        new ToolEntry("read_terminal_output", "Read Terminal", "Read output from an integrated terminal tab", Category.RUN, false, false, false),
        new ToolEntry("list_terminals", "List Terminals", "List active terminal tabs", Category.RUN, false, false, false),

        // -- Git --------------------------------------------------------------------------
        new ToolEntry("git_status", "Git Status", "Show the working tree status (staged, unstaged, untracked files)", Category.GIT, false, false, false),
        new ToolEntry("git_diff", "Git Diff", "Show changes as a diff -- staged, unstaged, or against a specific commit", Category.GIT, false, false, false),
        new ToolEntry("git_log", "Git Log", "Show commit history, optionally filtered by author, branch, file, or date", Category.GIT, false, false, false),
        new ToolEntry("git_commit", "Git Commit", "Commit staged changes with a message; supports amend and auto-staging all modified files", Category.GIT, false, false, false),
        new ToolEntry("git_stage", "Git Stage", "Stage one or more files for the next commit", Category.GIT, false, false, false),
        new ToolEntry("git_unstage", "Git Unstage", "Unstage files that were previously staged", Category.GIT, false, false, false),
        new ToolEntry("git_branch", "Git Branch", "List, create, switch, or delete branches", Category.GIT, false, false, false),
        new ToolEntry("git_stash", "Git Stash", "Push, pop, apply, list, or drop stashed changes", Category.GIT, false, false, false),
        new ToolEntry("git_show", "Git Show", "Show details and diff for a specific commit", Category.GIT, false, false, false),
        new ToolEntry("git_blame", "Git Blame", "Show per-line authorship for a file, optionally restricted to a line range", Category.GIT, false, false, false),
        new ToolEntry("git_push", "Git Push", "Push commits to a remote repository", Category.GIT, false, false, false),
        new ToolEntry("git_remote", "Git Remote", "List, add, remove, or update remote repositories", Category.GIT, false, false, false),
        new ToolEntry("git_fetch", "Git Fetch", "Download objects and refs from a remote without merging", Category.GIT, false, false, false),
        new ToolEntry("git_pull", "Git Pull", "Fetch from remote and integrate changes into the current branch", Category.GIT, false, false, false),
        new ToolEntry("git_merge", "Git Merge", "Merge a branch into the current branch; supports squash, no-ff, ff-only, and abort", Category.GIT, false, false, false),
        new ToolEntry("git_rebase", "Git Rebase", "Rebase current branch onto another; supports abort, continue, skip, onto, and interactive", Category.GIT, false, false, false),
        new ToolEntry("git_cherry_pick", "Git Cherry Pick", "Apply specific commits from another branch onto the current branch", Category.GIT, false, false, false),
        new ToolEntry("git_tag", "Git Tag", "List, create, or delete tags", Category.GIT, false, false, false),
        new ToolEntry("git_reset", "Git Reset", "Reset HEAD to a specific commit (soft, mixed, or hard)", Category.GIT, false, false, false),
        new ToolEntry("get_file_history", "Get File History", "Get the git commit history for a specific file, including renames", Category.GIT, false, false, false),

        // -- Refactoring ------------------------------------------------------------------
        new ToolEntry("refactor", "Refactor", "Rename, extract method, inline, or safe-delete a symbol using IntelliJ's refactoring engine", Category.REFACTOR, false, false, false),
        new ToolEntry("replace_symbol_body", "Replace Symbol Body", "Replace the entire definition of a symbol (method, class, field) by name -- no line numbers needed", Category.REFACTOR, false, false, true),
        new ToolEntry("insert_before_symbol", "Insert Before Symbol", "Insert content before a symbol definition", Category.REFACTOR, false, false, true),
        new ToolEntry("insert_after_symbol", "Insert After Symbol", "Insert content after a symbol definition", Category.REFACTOR, false, false, true),
        new ToolEntry("optimize_imports", "Optimize Imports", "Remove unused imports and organize them according to code style", Category.REFACTOR, false, false, false),
        new ToolEntry("format_code", "Format Code", "Format a file using IntelliJ's configured code style", Category.REFACTOR, false, false, false),
        new ToolEntry("suppress_inspection", "Suppress Inspection", "Insert a suppress annotation or comment for a specific inspection at a given line", Category.REFACTOR, false, false, false),
        new ToolEntry("apply_quickfix", "Apply Quickfix", "Apply an IntelliJ quick-fix at a specific file and line", Category.REFACTOR, false, false, false),
        new ToolEntry("add_to_dictionary", "Add to Dictionary", "Add a word to the project spell-check dictionary", Category.REFACTOR, false, false, false),

        // -- IDE & project ----------------------------------------------------------------
        new ToolEntry("get_project_info", "Get Project Info", "Get project name, SDK, modules, and overall structure", Category.IDE, false, false, false),
        new ToolEntry("list_project_files", "List Project Files", "List files in a project directory, optionally filtered by glob pattern", Category.IDE, false, false, false),
        new ToolEntry("mark_directory", "Mark Directory", "Mark a directory as source root, test root, resources, excluded, etc.", Category.IDE, false, false, false),
        new ToolEntry("edit_project_structure", "Edit Project Structure", "View and modify module dependencies, libraries, SDKs, and project structure", Category.IDE, false, false, false),
        new ToolEntry("get_indexing_status", "Get Indexing Status", "Check whether IntelliJ indexing is in progress; optionally wait until it finishes", Category.IDE, false, false, false),
        new ToolEntry("get_documentation", "Get Documentation", "Get Javadoc or KDoc for a symbol by fully-qualified name", Category.IDE, false, false, false),
        new ToolEntry("download_sources", "Download Sources", "Download library sources to enable source navigation and debugging", Category.IDE, false, false, false),
        new ToolEntry("get_active_file", "Get Active File", "Get the path and content of the currently active editor file", Category.IDE, false, false, false),
        new ToolEntry("get_open_editors", "Get Open Editors", "List all currently open editor tabs", Category.IDE, false, false, false),
        new ToolEntry("list_themes", "List Themes", "List all available IDE themes with their dark/light type", Category.IDE, false, false, false),
        new ToolEntry("set_theme", "Set Theme", "Change the IDE theme by name (e.g., 'Darcula', 'Light')", Category.IDE, false, false, false),

        // -- Other ------------------------------------------------------------------------
        new ToolEntry("get_chat_html", "Get Chat HTML", "Retrieve the live DOM HTML of the JCEF chat panel for debugging", Category.OTHER, false, false, false),
        new ToolEntry("search_conversation_history", "Search Conversation History", "List, read, and search past conversation sessions from the chat history", Category.OTHER, false, false, false),
        new ToolEntry("get_notifications", "Get Notifications", "Get recent IntelliJ balloon notifications", Category.OTHER, false, false, false),
        new ToolEntry("read_ide_log", "Read IDE Log", "Read recent IntelliJ IDE log entries, optionally filtered by level or text", Category.OTHER, false, false, false),
        new ToolEntry("create_scratch_file", "Create Scratch File", "Create a temporary scratch file with the given name and content", Category.OTHER, false, false, false),
        new ToolEntry("list_scratch_files", "List Scratch Files", "List existing scratch files in the IDE scratch directory", Category.OTHER, false, false, false),
        new ToolEntry("http_request", "HTTP Request", "Make an HTTP request (GET/POST/PUT/PATCH/DELETE) to a URL", Category.OTHER, false, false, false)
    ));

    // Tools that only read data and never modify state
    private static final java.util.Set<String> READ_ONLY_TOOLS = java.util.Set.of(
        "intellij_read_file", "open_in_editor", "show_diff", "reload_from_disk",
        "search_symbols", "search_text", "find_references", "go_to_declaration",
        "get_file_outline", "get_class_outline", "get_type_hierarchy",
        "find_implementations", "get_call_hierarchy",
        "run_inspections", "run_qodana", "run_sonarqube_analysis",
        "get_problems", "get_highlights", "get_compilation_errors",
        "get_coverage",
        "list_run_configurations", "read_run_output", "list_terminals", "read_terminal_output",
        "git_status", "git_diff", "git_log", "git_show", "git_blame", "get_file_history",
        "get_project_info", "list_project_files", "get_indexing_status",
        "get_documentation", "download_sources",
        "get_active_file", "get_open_editors", "list_themes",
        "search_conversation_history", "get_notifications", "read_ide_log",
        "list_scratch_files"
    );

    // Tools that can permanently delete or irreversibly modify data
    private static final java.util.Set<String> DESTRUCTIVE_TOOLS = java.util.Set.of(
        "delete_file", "git_reset", "git_push", "git_rebase"
    );

    // Tools that interact with systems outside the IDE
    private static final java.util.Set<String> OPEN_WORLD_TOOLS = java.util.Set.of(
        "run_command", "run_in_terminal", "write_terminal_input",
        "http_request", "git_push", "git_pull", "git_fetch"
    );

    private ToolRegistry() {
    }

    public static List<ToolEntry> getAllTools() {
        return ALL_TOOLS;
    }

    /**
     * Look up a tool by id (exact match). Returns null if not found.
     */
    public static ToolEntry findById(String id) {
        if (id == null) return null;
        for (ToolEntry e : ALL_TOOLS) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /**
     * Returns the IDs of all built-in agent tools (e.g., view, edit, bash).
     * Used to populate {@code excludedTools} in {@code session/new} for agents
     * that support filtering out their native tools.
     */
    @org.jetbrains.annotations.NotNull
    public static List<String> getBuiltInToolIds() {
        List<String> ids = new java.util.ArrayList<>();
        for (ToolEntry e : ALL_TOOLS) {
            if (e.isBuiltIn) ids.add(e.id);
        }
        return ids;
    }

    /**
     * Returns MCP tool annotations for a given tool ID.
     * Annotations follow the MCP 2025-03-26 spec: readOnlyHint, destructiveHint,
     * idempotentHint, openWorldHint. Agents use these hints to decide permission
     * behavior (e.g., auto-allow read-only tools, prompt for destructive ones).
     */
    public static com.google.gson.JsonObject getMcpAnnotations(@org.jetbrains.annotations.NotNull String toolId) {
        ToolEntry entry = findById(toolId);
        com.google.gson.JsonObject ann = new com.google.gson.JsonObject();
        if (entry != null) {
            ann.addProperty("title", entry.displayName);
        }
        ann.addProperty("readOnlyHint", READ_ONLY_TOOLS.contains(toolId));
        ann.addProperty("destructiveHint", DESTRUCTIVE_TOOLS.contains(toolId));
        ann.addProperty("openWorldHint", OPEN_WORLD_TOOLS.contains(toolId));
        return ann;
    }
}
