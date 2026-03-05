# IDE Agent for Copilot — Features

> **66 tools** across 10 categories — the most comprehensive AI agent integration for JetBrains IDEs.

IDE Agent for Copilot connects GitHub Copilot's agentic mode directly to IntelliJ's internal APIs.
Every file edit, refactoring, inspection, and git operation goes through IntelliJ's own engine — not
raw file I/O — so undo, formatting, indexing, and VCS all work correctly.

---

## Code Intelligence & Navigation

Deep integration with IntelliJ's code analysis engine — the agent can navigate your codebase the
same way you do.

- **`search_symbols`** — Search classes, methods, and fields by name using IntelliJ's symbol index
- **`search_text`** — Search text or regex patterns across all project files (reads from editor buffers, always
  up-to-date)
- **`find_references`** — Find all usages of any symbol across the project
- **`go_to_declaration`** — Jump to the declaration of a symbol
- **`get_file_outline`** — Get file structure — classes, methods, fields with line numbers
- **`get_class_outline`** — Get the full API of any class by fully qualified name (works on project, library, and JDK
  classes)
- **`get_type_hierarchy`** — Show supertypes and subtypes of any class or interface

---

## Code Quality & Inspections

Run the same inspections you see in the editor — plus Qodana and SonarQube — all from the chat.

- **`run_inspections`** — Run IntelliJ's full inspection engine on the project or a specific scope
- **`run_qodana`** — Run Qodana static analysis
- **`run_sonarqube_analysis`** — Run SonarQube for IDE analysis (requires SonarLint plugin)
- **`get_problems`** — Get cached errors and warnings for open files
- **`get_highlights`** — Get cached editor highlights (errors, warnings, info)
- **`get_compilation_errors`** — Fast compilation error check using cached daemon results
- **`apply_quickfix`** — Apply an IntelliJ quick-fix at a specific file and line
- **`suppress_inspection`** — Suppress an inspection finding with the appropriate annotation or comment
- **`optimize_imports`** — Remove unused imports and organize by code style
- **`format_code`** — Format a file using IntelliJ's configured code style
- **`add_to_dictionary`** — Add a word to the project spell-check dictionary

---

## File Operations

All file operations go through IntelliJ's Document API and Virtual File System — every edit is
undoable, auto-formatted, and instantly visible in the editor.

- **`intellij_read_file`** — Read file content from IntelliJ's editor buffer (always reflects unsaved changes)
- **`intellij_write_file`** — Write or edit files with three modes: full replace, find-and-replace (`old_str`/
  `new_str`), or line-range replace
- **`create_file`** — Create a new file registered in IntelliJ's VFS
- **`delete_file`** — Delete a file from the project
- **`reload_from_disk`** — Refresh IntelliJ's VFS to pick up external changes
- **`open_in_editor`** — Open a file in the editor, optionally at a specific line
- **`show_diff`** — Open IntelliJ's diff viewer to compare current content with proposed changes
- **`undo`** — Undo the last edit operation (each write + auto-format = 2 undo steps)

**Key behavior:** Built-in Copilot CLI file edits are automatically intercepted and redirected
through `intellij_write_file` — so you always get proper undo, formatting, and no
"file changed externally" dialogs.

---

## Refactoring

Structural refactoring operations powered by IntelliJ's refactoring engine — safe renames that
update all references, extract method with proper scope analysis, and more.

- **`refactor`** — Perform rename, extract method, inline, or safe-delete operations
- **`get_documentation`** — Retrieve Javadoc/KDoc for any symbol by fully qualified name

---

## Testing & Coverage

Run tests, check results, and measure coverage — all without leaving the chat.

- **`list_tests`** — Discover tests by class, method, or file pattern
- **`run_tests`** — Run tests by class, method, or wildcard pattern via Gradle (with coverage)
- **`get_coverage`** — Retrieve code coverage results, optionally filtered by file or class

---

## Build & Project Management

Access project structure, trigger builds, and manage source roots.

- **`build_project`** — Trigger incremental compilation of the project or a specific module
- **`get_project_info`** — Get project name, SDK, modules, IDE version, and OS info
- **`get_indexing_status`** — Check if IntelliJ indexing is in progress (can block until finished)
- **`download_sources`** — Download library source JARs for navigation and debugging
- **`mark_directory`** — Mark a directory as source root, test root, resources, excluded, or generated

---

## Run Configurations

Create, edit, and execute IntelliJ run configurations from the chat — launch apps, run Gradle
tasks, or execute test suites.

- **`list_run_configurations`** — List all available run configurations
- **`run_configuration`** — Execute a run configuration by name
- **`create_run_configuration`** — Create a new run config (Application, JUnit, or Gradle)
- **`edit_run_configuration`** — Modify arguments, environment variables, or working directory
- **`delete_run_configuration`** — Remove a run configuration

---

## Git Operations

Full git workflow support — stage, commit, diff, blame, branch, stash, and push without leaving the
conversation.

- **`git_status`** — Show working tree status (staged, unstaged, untracked files)
- **`git_diff`** — Show diff — staged, unstaged, or against a specific commit or branch
- **`git_log`** — View commit history with filters for author, branch, file, and date
- **`git_commit`** — Commit staged changes (supports amend and auto-stage all)
- **`git_stage`** — Stage files for the next commit
- **`git_unstage`** — Unstage previously staged files
- **`git_branch`** — List, create, switch, or delete branches
- **`git_stash`** — Push, pop, apply, list, or drop stashed changes
- **`git_show`** — Show commit details and file diffs
- **`git_blame`** — Show per-line authorship (with optional line-range filtering)
- **`git_push`** — Push commits to a remote
- **`git_remote`** — List, add, remove, or configure remote repositories
- **`git_revert`** — Revert a commit (with optional no-commit mode)

---

## Terminal & Shell

Run shell commands with output capture, or use IntelliJ's integrated terminal.

- **`run_command`** — Run a shell command with paginated output in the Run panel
- **`run_in_terminal`** — Run a command in IntelliJ's integrated terminal
- **`read_terminal_output`** — Read output from a terminal tab
- **`read_run_output`** — Read output from a Run panel tab

---

## IDE & Editor

Access the editor state, create scratch files for quick prototyping, and inspect IDE internals.

- **`get_active_file`** — Get path and content of the currently focused editor tab
- **`get_open_editors`** — List all open editor tabs
- **`create_scratch_file`** — Create a scratch file with any extension and content
- **`list_scratch_files`** — List all existing scratch files
- **`run_scratch_file`** — Execute a scratch file. Works reliably with Kotlin Script (.kts), Kotlin (.kt), Java (.java — filename must match class name), Groovy (.groovy), and JavaScript (.js). TypeScript (.ts) needs Node 22.6+ or tsx. Python (.py) needs the Python plugin.
- **`get_chat_html`** — Retrieve the live DOM of the chat panel (for debugging)

---

## Infrastructure

Plugin management, HTTP requests, and IDE diagnostics.

- **`http_request`** — Make HTTP requests (GET, POST, PUT, PATCH, DELETE) to any URL
- **`read_ide_log`** — Read recent IDE log entries with optional level and text filtering
- **`get_notifications`** — Get recent IntelliJ balloon notifications

---

## Chat & Workflow

The chat interface is a full-featured agent console built on JCEF (Chromium).

- **Streaming markdown** with syntax-highlighted code blocks
- **Context attachments** — attach files, selections, and symbols to prompts
- **Plan visualization** — tree view of agent steps with real-time progress indicators
- **Timeline view** — reasoning steps, tool calls, and sub-agent activity
- **Quick-reply buttons** for fast follow-up responses
- **Conversation history** maintained across IDE sessions
- **Export** — save conversations for reference

---

## Permissions & Safety

Fine-grained control over what the agent can do.

- **Per-tool permissions** — Allow, Ask, or Deny for each of the 66 tools
- **Path-based rules** — different permissions for project files vs. files outside the project
- **Built-in edit interception** — Copilot CLI file edits are redirected through IntelliJ's document API so every change
  is undoable
- **Settings panel** — enable/disable individual tools and configure permissions visually

---

## Model Selection & Billing

Choose the right model for the task and track usage in real time.

- **Multiple model families** — Claude (Sonnet, Opus, Haiku), GPT (5.x, Codex), Gemini
- **Real-time billing graph** — live cost estimates and monthly cycle tracking
- **Usage multipliers** — see per-model cost multipliers before selecting
- **One-click model switching** — change models mid-conversation

---

## Sub-Agents

Specialized agents that run in parallel for focused tasks.

- **Explore** — Fast codebase exploration and question answering
- **Task** — Execute commands (builds, tests, lints) with clean output
- **General-purpose** — Complex multi-step tasks with full tool access
- **Code Review** — High signal-to-noise code review — only surfaces real issues

---

## Requirements

- **GitHub Copilot subscription** (Individual, Business, or Enterprise)
- **Copilot CLI** installed and authenticated (`gh copilot` or standalone)
- **IntelliJ IDEA 2025.1** or later (compatible through 2025.3)
- **Java 21+** runtime
