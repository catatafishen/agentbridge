# Agentic GitHub Copilot for JetBrains — Feature Documentation

> **Version:** 0.2.0-BETA  
> **Platform:** IntelliJ IDEA 2025.1+ (compatible through 2025.3)  
> **Plugin ID:** `com.github.catatafishen.ideagentforcopilot`

---

## Table of Contents

1. [Introduction](#introduction)
2. [Core Chat & Conversation](#core-chat--conversation)
3. [Context Management](#context-management)
4. [MCP Tools — Code Intelligence](#mcp-tools--code-intelligence)
5. [MCP Tools — File Operations](#mcp-tools--file-operations)
6. [MCP Tools — Code Quality & Refactoring](#mcp-tools--code-quality--refactoring)
7. [MCP Tools — Testing](#mcp-tools--testing)
8. [MCP Tools — Git Integration](#mcp-tools--git-integration)
9. [MCP Tools — Project & Build](#mcp-tools--project--build)
10. [MCP Tools — Infrastructure & Terminal](#mcp-tools--infrastructure--terminal)
11. [MCP Tools — Editor & Scratch Files](#mcp-tools--editor--scratch-files)
12. [User Interface](#user-interface)
13. [Configuration & Settings](#configuration--settings)
14. [Permission System](#permission-system)
15. [Reliability & Architecture](#reliability--architecture)
16. [Comparison: This Plugin vs. CLI Copilot vs. Official Copilot Plugin](#comparison)
17. [Summary](#summary)

---

## Introduction

**IDE Agent for Copilot** (also known as *Copilot Bridge*) is an IntelliJ Platform
plugin that embeds GitHub Copilot's full agent capabilities directly into the IDE via the **Agent
Client Protocol (ACP)**. Unlike the official GitHub Copilot plugin — which focuses on inline code
completions — this plugin provides a multi-turn conversational agent with deep IDE integration through
**60 MCP (Model Context Protocol) tools**.

The plugin spawns the GitHub Copilot CLI (`copilot --acp --stdio`) as a subprocess and communicates
via JSON-RPC 2.0. The agent can reason, plan, execute tool calls, and iterate — all while reading
from live editor buffers, writing through IntelliJ's Document API (with full undo support), running
inspections via the PSI engine, executing tests, managing git operations, and more.

### Why This Plugin Exists

Working with GitHub Copilot CLI in a terminal alongside IntelliJ creates friction: the CLI reads
stale files from disk (not live editor buffers), writes bypass the undo stack, and there's no access
to IntelliJ's rich code intelligence (PSI, inspections, refactoring). The official Copilot plugin
offers inline completions but no agentic multi-turn workflow.

This plugin bridges both gaps by giving Copilot's agent direct access to IntelliJ's APIs through a
curated set of MCP tools, while presenting conversations in a native tool window with streaming
responses, plan visualization, and real-time tool call feedback.

### Architecture at a Glance

```
┌──────────────────────────────────────────────────────────────┐
│                 IntelliJ IDEA Plugin (Java 21)               │
│  ┌────────────────┐  ┌──────────────────────────────────────┐│
│  │  ACP Client    │  │  PSI Bridge (HTTP localhost)         ││
│  │  (JSON-RPC)    │  │  ├─ Code Navigation Tools            ││
│  │                │  │  ├─ File I/O Tools                   ││
│  │  ↕ stdin/out   │  │  ├─ Code Quality Tools               ││
│  └───────┬────────┘  │  ├─ Testing Tools                    ││
│          │           │  ├─ Git Tools                         ││
│  ┌───────▼────────┐  │  ├─ Project & Build Tools            ││
│  │  Copilot CLI   │  │  ├─ Terminal Tools                   ││
│  │  (subprocess)  │  │  └─ Editor Tools                     ││
│  │  ↕ MCP stdio   │  └──────────────────────────────────────┘│
│  ┌───────▼────────┐                                          │
│  │  MCP Server    │─── HTTP POST ──► PSI Bridge              │
│  │  (Java JAR)    │                                          │
│  └────────────────┘                                          │
└──────────────────────────────────────────────────────────────┘
```

The MCP Server runs as a standalone JAR subprocess, communicating with the Copilot CLI via stdio and
delegating tool calls to the PSI Bridge — a lightweight HTTP server embedded in the plugin that
provides access to IntelliJ's PSI (Program Structure Interface), Document API, VFS, and execution
framework.

---

## Core Chat & Conversation

### Multi-Turn Conversations

The plugin maintains persistent conversation state through session IDs, enabling multi-turn
interactions without re-authentication. Sessions are created via the ACP `session/new` RPC and
reused across prompts within the same project. Conversation history is serialized to
`.agent-work/conversation.json` and restored on IDE restart, preserving full context across sessions.

### Real-Time Streaming

Responses stream in real-time as the agent generates them. Text chunks arrive via ACP
`agent_message_chunk` notifications and render progressively in the chat console. This provides
immediate feedback — you see the agent's response forming character-by-character rather than waiting
for a complete answer.

### Plan Mode

Toggling the session mode to "Plan" causes the plugin to prefix all prompts with `[[PLAN]]`,
instructing the agent to create a structured implementation plan before taking action. Plans include
markdown checklists, approach descriptions, and considerations. The plan is saved to
`.agent-work/session-state/{id}/plan.md` and visualized in the Session/Plans tab. This is ideal for
complex multi-file changes where you want to review the approach before execution.

### Agent Mode

The default "Agent" mode allows the agent to reason and act immediately — reading files, making
edits, running tests, and iterating. The agent has full access to all permitted MCP tools and can
chain multiple operations in a single turn.

### Model Selection

The plugin supports all models available through your GitHub Copilot subscription. Models are
fetched via the ACP `session/new` response and displayed in a dropdown selector. Switching models
happens instantly via `session/set_model` RPC without restarting the CLI process. Each model has a
usage multiplier (e.g., GPT-4.1 = 1x, Claude Opus = 3x) displayed in the UI for cost awareness.

Available model families include Claude (Sonnet, Opus, Haiku), GPT (4.1, 5.x), and Gemini — with
the exact list determined by your subscription tier.

### Thinking & Reasoning Visibility

When using models that support extended thinking (e.g., Claude), the agent's reasoning steps appear
in collapsible "💭 Thinking" sections. These auto-collapse when the turn completes and convert to
compact chips, keeping the UI clean while preserving full visibility into the agent's decision-making
process.

### Sub-Agent Conversations

The agent can spawn specialized sub-agents (Explore, Task, Code Review, UI Reviewer, General
Purpose) for specific tasks. Each sub-agent conversation appears in a distinct color-coded bubble
(from an 8-color palette) visually nested under the parent message. Sub-agents are isolated: they
cannot perform git write operations (commit, stage, branch) — only the parent agent can modify the
repository.

### Quick-Reply Buttons

Agent responses can include quick-reply suggestions rendered as clickable buttons. These appear as
green-tinted pills below the response and send the selected option back as a new prompt with a
single click, enabling rapid conversational flow without retyping. Buttons support semantic color
suffixes (`:danger`, `:primary`, `:success`, `:warning`) and a `:dismiss` suffix that simply hides
the buttons without sending a message — useful for no-op options like "No thanks".

### Usage & Billing Tracking

The plugin tracks premium request usage against your GitHub Copilot billing quota. A usage display
shows requests consumed vs. entitlement (e.g., "45 / 500"), with projected overage calculations.
Click the display to toggle between monthly and session-level views. Per-turn statistics show elapsed
time, tool call count, and model multiplier.

---

## Context Management

### Attaching Files

Before sending a prompt, you can attach files from your project as context. The "Add Current File"
action adds the entire file currently open in the editor. Attached files appear as removable chips
in the attachments panel above the prompt input, showing the filename and line count.

### Attaching Selections

The "Add Selection to Context" editor action captures the currently selected text, recording the
file path and exact line range (e.g., `Main.java:42-65`). Selection context items are marked
distinctly from full-file attachments, allowing precise code targeting.

### How Context Reaches the Agent

Each context item is converted to an ACP `ResourceReference` containing:

- A `file://` URI with optional `#L{start}-L{end}` fragment for selections
- The MIME type (mapped from file extension)
- The actual file text (read from the live editor buffer, not disk)

These references are sent as structured content blocks *before* the prompt text in the ACP request.
Additionally, code selections are inlined in the prompt as markdown snippets, giving the agent dual
visibility through both structured references and natural language context.

### Validation & Cleanup

File references are validated before sending — checking that files exist, editors are accessible,
and line ranges are within bounds. After sending a prompt, context is automatically cleared to
prevent bleed between turns. The context is ephemeral per-prompt, not persisted across conversation
turns.

---

## MCP Tools — Code Intelligence

The plugin provides 6 code navigation tools that leverage IntelliJ's PSI (Program Structure
Interface) engine for accurate, semantic code understanding.

### `search_symbols`

Searches for classes, interfaces, methods, or fields by name across the entire project. Uses PSI's
`PsiSearchHelper` with `GlobalSearchScope` for accurate symbol resolution — not simple text search.
Parameters: `query` (symbol name), `type` (optional filter: class/method/field/property).

### `get_file_outline`

Returns the structural skeleton of a file: all top-level classes, methods, fields, and inner classes
with line numbers. Uses `PsiRecursiveElementWalkingVisitor` on the parsed PSI tree. Useful for
understanding file organization before making targeted edits.

### `get_class_outline`

Shows constructors, methods, fields, and inner classes of any class by fully qualified name — works
on project classes, library JARs, and JDK classes. Uses `JavaPsiFacade.findClass()` with
`GlobalSearchScope.allScope()`. Parameters: `class_name`, `include_inherited` (boolean to include
superclass members).

### `find_references`

Locates all usages of a symbol across the project. Uses `ReferencesSearch.search()` for precise
PSI-based references. Parameters: `symbol` (name), `file_pattern` (optional glob filter). Essential
for understanding impact before refactoring.

### `list_project_files`

Lists project source files with optional directory scoping and glob filtering. Uses
`ProjectFileIndex.iterateContent()` to enumerate the Virtual File System. Parameters: `directory`,
`pattern` (glob). Capped at 500 files per call.

### `search_text`

Full-text search across project files with regex support. Critically, this reads from
**IntelliJ editor buffers** (via `FileDocumentManager`), not disk — so it always reflects unsaved
changes. Parameters: `query`, `file_pattern` (glob), `regex` (boolean), `case_sensitive` (boolean),
`max_results`.

---

## MCP Tools — File Operations

File operations are the most architecturally significant tools in the plugin. All reads and writes
go through IntelliJ's Document API, providing undo support, live buffer access, and proper VFS
integration.

### `intellij_read_file` / `read_file`

Reads file content from the IntelliJ editor buffer with optional line range filtering. Returns the
live buffer content (including unsaved changes), not the on-disk version. Parameters: `path`,
`start_line`, `end_line`. Using line ranges is strongly encouraged to save tokens on large files.

### `intellij_write_file` / `write_file`

The primary editing tool. Supports three modes:

1. **Full content write** (`content` parameter) — replaces entire file
2. **Partial string replace** (`old_str` + `new_str`) — finds and replaces exactly one occurrence
3. **Line-range replace** (`start_line` + `new_str`, optional `end_line`)

All writes execute through `CommandProcessor.executeCommand()` on the EDT, ensuring they appear in
IntelliJ's undo history. After each write, the file is optionally auto-formatted (optimize imports +
reformat code). The `auto_format` parameter (default: true) controls this behavior.

### `create_file`

Creates a new file with the specified content. The file must not already exist. Uses
`Files.writeString()` followed by `LocalFileSystem.refreshAndFindFileByPath()` to sync with
IntelliJ's Virtual File System, ensuring the new file appears in the project tree and is properly
indexed.

### `delete_file`

Deletes a file from disk via `VirtualFile.delete()` in an EDT write action, keeping the VFS in sync.

### `undo`

Reverts the last N edit actions on a file using IntelliJ's `UndoManager`. Each write + auto-format
counts as 2 undo steps. Parameters: `path`, `count` (number of undo steps, default: 1).

---

## MCP Tools — Code Quality & Refactoring

### `run_inspections`

The primary tool for comprehensive code analysis. Runs IntelliJ's full inspection engine on a file,
directory, or the entire project. Returns warnings, errors, and code issues with severity levels.
Supports pagination (`limit`, `offset`) and severity filtering (`min_severity`). Uses
`GlobalInspectionContextImpl` internally.

### `get_problems`

Shows IDE diagnostics (errors, warnings, hints) for a specific file or all open files. Faster than
`run_inspections` but limited to files with active editor sessions. Uses the daemon code analyzer's
cached results.

### `get_highlights`

Returns cached editor highlights from IntelliJ's `DaemonCodeAnalyzer` — the fastest way to check
for issues in open files. Results are real-time but limited to currently open editors.

### `get_compilation_errors`

Fast compilation error check using the cached compiler daemon results. Much faster than
`build_project`. Useful for quick verification after edits without triggering a full build.

### `optimize_imports`

Removes unused imports and organizes import statements according to project style settings. Uses
`OptimizeImportsProcessor`. Runs automatically after writes when `auto_format` is enabled.

### `format_code`

Applies IntelliJ's code formatter using the current project style settings. Uses
`ReformatCodeProcessor`. Like `optimize_imports`, this runs automatically after writes.

### `apply_quickfix`

Applies an IDE quick-fix by inspection ID at a specific line. For example, applying "unused import"
removal or "add missing method" fixes. Parameters: `file`, `line`, `inspection_id`, `fix_index`.

### `suppress_inspection`

Adds a `@SuppressWarnings` annotation or `//noinspection` comment to suppress a specific inspection
at a given location. Parameters: `file`, `line`, `inspection_id`.

### `add_to_dictionary`

Adds a word to the project's spell-check dictionary, resolving false-positive typo warnings.
Persists to `ProjectDictionaryState`.

### `refactor`

Performs automated refactoring operations:

- **rename** — renames a symbol and updates all references
- **extract_method** — extracts a code block into a new method
- **inline** — inlines a method/variable at all usage sites
- **safe_delete** — deletes a symbol only if it has no remaining usages

Parameters: `operation`, `file`, `symbol`, `line`, `new_name` (for rename).

### `go_to_declaration`

Navigates to the declaration/definition of a symbol. Returns the file path and line number of the
declaration. Uses PSI resolution through `GotoDeclarationHandler` extension points.

### `get_type_hierarchy`

Shows the class hierarchy — supertypes (superclasses and interfaces) and subtypes (subclasses and
implementations). Parameters: `symbol` (class name), `direction` (supertypes/subtypes/both). Uses
`ClassInheritorsSearch` internally.

### `get_documentation`

Extracts JavaDoc/KDoc documentation for a fully qualified symbol. Returns formatted documentation
text via `PsiDocumentationProvider`.

### `run_qodana`

Triggers Qodana static analysis (if configured in the project). Returns findings in paginated format
with severity levels. Parameters: `limit`.

### `run_sonarqube_analysis`

Runs SonarQube for IDE analysis (requires the SonarLint plugin to be installed). Supports full
project or changed-files-only scope. Parameters: `scope` (all/changed), `limit`, `offset`.

---

## MCP Tools — Testing

### `list_tests`

Enumerates test classes and methods in the project by scanning for `@Test` annotations via PSI.
Parameters: `file_pattern` (optional glob to filter test files). Returns test class names and method
signatures.

### `run_tests`

Executes JUnit or Gradle tests by fully qualified class name, method name, or wildcard pattern.
Creates or reuses IntelliJ run configurations via `RunManager` and delegates to the IDE's
`ExecutionManager`. Parameters: `target` (e.g., `MyTest.testFoo` or `*IntegrationTest*`), `module`.

### `get_coverage`

Extracts code coverage metrics (line coverage, branch coverage) from the most recent instrumented
test run. Parameters: `file` (optional filter by file or class name).

---

## MCP Tools — Git Integration

All git tools operate through `ProcessBuilder` executing git commands in the project directory.
Editor buffers are flushed to disk before git operations to ensure consistency. Write operations
(commit, stage, branch) are blocked for sub-agents — only the parent agent can modify the repository.

### `git_status`

Shows the current branch, ahead/behind status, and lists staged, unstaged, and untracked files.
Parameters: `verbose` (boolean for full output).

### `git_diff`

Shows diffs between working directory, staging area, or specific commits. Parameters: `staged`
(boolean), `commit` (compare against), `path` (limit to file), `stat_only` (show only
insertions/deletions counts).

### `git_log`

Retrieves commit history with rich filtering. Parameters: `max_count`, `format`
(oneline/short/medium/full), `author`, `since` (date filter), `path` (file filter), `branch`.

### `git_blame`

Shows per-line attribution (author, date, commit) for a file or line range. Parameters: `path`
(required), `line_start`, `line_end`.

### `git_commit`

Creates a commit from staged changes. Parameters: `message` (required), `amend` (boolean to amend
previous commit), `all` (boolean to auto-stage all modified files).

### `git_stage` / `git_unstage`

Adds or removes files from the staging area. Parameters: `path` (single file), `paths` (array of
files), `all` (boolean for all changes).

### `git_branch`

Lists, creates, switches, or deletes branches. Parameters: `action`
(list/create/switch/delete), `name`, `base` (base ref for create), `all` (include remote branches),
`force` (force delete unmerged).

### `git_stash`

Manages temporary stashes. Parameters: `action` (list/push/pop/apply/drop), `message`,
`include_untracked` (boolean), `index` (stash reference).

### `git_show`

Shows commit details or specific refs. Parameters: `ref` (SHA, branch, or tag), `stat_only`
(boolean), `path` (limit to file).

---

## MCP Tools — Project & Build

### `get_project_info`

Returns comprehensive project metadata: project name, path, IDE version, SDK information, module
list, detected build system (Gradle/Maven/etc.), and language versions. Collects data from
`ProjectRootManager`, `ModuleManager`, and `ApplicationInfo`.

### `build_project`

Triggers an incremental build using the project's build system. Parameters: `module` (optional,
build only a specific module). Uses `CompilerManager.make()` with async completion notification.

### `get_indexing_status`

Checks whether IntelliJ is currently indexing (dumb mode). Parameters: `wait` (boolean to block
until indexing finishes), `timeout` (seconds). Uses `DumbService.isDumb()`. Useful for ensuring
tools that depend on PSI are called only after indexing completes.

### `download_sources`

Downloads source JARs for project dependencies, enabling the agent to read library source code.
Parameters: `library` (optional filter by library name).

### Run Configurations

Four tools manage IntelliJ run configurations:

- **`list_run_configurations`** — Enumerates all run/debug configurations with their types and
  settings.
- **`run_configuration`** — Executes a named run configuration via `ExecutionEnvironmentBuilder`.
- **`create_run_configuration`** — Creates new Application, JUnit, or Gradle configurations with
  parameters like `main_class`, `jvm_args`, `program_args`, `working_dir`, and `env` variables.
- **`edit_run_configuration`** — Modifies existing configurations.

---

## MCP Tools — Infrastructure & Terminal

### `run_command`

Executes shell commands in the project directory with output capture. Parameters: `command`,
`timeout` (default: 60 seconds), `title` (for the Run panel tab), `max_chars` (pagination, default:
8000), `offset` (for pagination). Uses `ProcessBuilder` with stdout/stderr capture.

### `http_request`

Makes HTTP requests to any URL. Parameters: `url`, `method` (GET/POST/PUT/PATCH/DELETE), `body`,
`headers` (key-value object). Uses Java's `HttpURLConnection` with 10-second connect and 30-second
read timeouts. Useful for testing APIs during development.

### `read_ide_log`

Reads IntelliJ's `idea.log` file for debugging IDE issues. Parameters: `lines` (number of recent
lines, default: 50), `filter` (text filter), `level` (INFO/WARN/ERROR). Tail-reads the log file.

### `get_notifications`

Returns active IDE notifications and alerts from `NotificationManager`. Useful for understanding IDE
state and pending warnings.

### `read_run_output`

Reads output from the most recent build, test, or run execution in the Run panel. Parameters:
`tab_name` (specific tab), `max_chars` (default: 8000).

### Terminal Tools

- **`run_in_terminal`** — Executes a command in IntelliJ's built-in terminal (new or existing tab).
  Editor buffers are flushed before execution.
- **`read_terminal_output`** — Captures the content of a terminal tab for agent processing.
- **`list_terminals`** — Enumerates open terminal tabs in the IDE.

---

## MCP Tools — Editor & Scratch Files

### `open_in_editor`

Opens a file in the editor and optionally navigates to a specific line. Uses
`FileEditorManager.openFile()` with `OpenFileDescriptor` for precise navigation. Triggers
`DaemonCodeAnalyzer.restart()` to refresh syntax highlights.

### `show_diff`

Displays a diff viewer for comparing:

- A file against proposed new content (agent changes)
- Two files side-by-side
- A file against its VCS version

Uses `DiffContentFactory` and `DiffManager.showDiff()` for native IntelliJ diff display.

### `create_scratch_file`

Creates temporary scratch files for quick exploration or experimentation. Parameters: `name`
(with extension), `content`. Scratch files are IDE-managed (stored in
`~/.config/JetBrains/scratches/`) and don't pollute the project.

### `list_scratch_files`

Enumerates all scratch files created in the IDE.

---

## User Interface

### Tool Window

The plugin registers a **dedicated tool window** (docked to the right panel by default) with a
single-panel layout — no tabs:

- **Title Bar Actions**: "New Chat" (restart icon) resets the conversation.
- **Chat Console** (top section): A JCEF (Chromium Embedded Framework) browser rendering the
  conversation as styled HTML. Falls back to a plain JBTextArea if CEF is unavailable.
- **Prompt Area** (bottom section): An `EditorTextField` with placeholder text, multi-line support
  (Shift+Enter for new lines, Enter to send), and an attachments panel showing context files as
  removable chips.
- **Toolbar** (between chat and prompt): Two toolbars containing all actions — Send/Stop,
  Attach File, Attach Selection, Model Selector, Mode Selector (Agent/Plan), toggle settings
  (Follow Agent Files, Format After Edit, Build/Test/Commit Before End), Copy Conversation, and
  Help. A processing timer and usage graph display on the right side.

Debug events, timeline, and plugin logs are accessible via **modal dialogs** (not tabs in the main
view), opened from the context menu or keyboard shortcuts.

### Message Bubbles

- **User prompts**: Right-aligned, blue-tinted bubbles with rounded corners. Include clickable
  context file chips (📄 icon) when files are attached.
- **Agent responses**: Left-aligned, green-tinted bubbles. Content renders as rich markdown —
  headers, lists, tables, code blocks with syntax highlighting containers and copy buttons.
- **Sub-agent bubbles**: Color-coded (8-color rotating palette: teal, orange, purple, pink, blue,
  lime, red, sky-blue) with left-margin indentation. The prompt shows `@AgentName` in the
  agent-specific color; results render in a matching-colored response bubble.

### Collapsible Sections & Chips

When an agent turn completes, intermediate sections automatically collapse into compact **chips** in
a metadata row:

| Chip           | Represents                                         |
|----------------|----------------------------------------------------|
| 💭 Thought     | Agent reasoning (thinking blocks)                  |
| 🔧 *tool_name* | Tool call with ✓/✖ status icon                     |
| 📎 N files     | Attached context files                             |
| ❌ Error        | Failed operations                                  |
| *Nx*           | Model multiplier (with tooltip showing model name) |

Clicking a chip expands the corresponding section inline; a close button (✕) re-collapses it.
Sections animate with a smooth 250ms fade + scale transition.

### Code Block Copy Buttons

Every code block (`<pre>` element) gets a "Copy" button that appears on hover. The button uses a
light gray background with 70% opacity, becoming fully opaque on direct hover. Clicking copies the
code to clipboard and shows "Copied!" feedback for 1.5 seconds. Buttons are keyboard-focusable with
proper `role="button"` attributes.

### Dark/Light Theme Support

The plugin dynamically extracts IDE theme colors and injects them as CSS custom properties:

| Variable    | Source                        | Purpose                |
|-------------|-------------------------------|------------------------|
| `--fg`      | Label.foreground              | Text color             |
| `--bg`      | ToolWindow background         | Chat background        |
| `--user`    | Component.linkColor           | User bubble accent     |
| `--agent`   | VersionControl.GitGreen       | Agent bubble accent    |
| `--tool`    | EditorTabs.selectedForeground | Tool call accent       |
| `--think`   | Label.disabledForeground      | Thinking section color |
| `--code-bg` | Editor.backgroundColor        | Code block background  |

When the IDE theme changes, a `LafManagerListener` re-injects all CSS variables instantly — no page
reload needed.

### Processing Indicators

- **Streaming text**: Agent responses render progressively with auto-scroll to keep the latest
  content visible.
- **Spinner**: An animated pulse icon on thinking and tool call sections while they're active.

### Session Separator & Lazy Loading

- **Session separator**: A centered divider (`───── New session 📅 HH:MM ─────`) marks conversation
  boundaries.
- **Lazy loading**: For conversations with >5 turns, earlier messages are deferred behind a
  clickable banner ("▲ Load earlier messages") with automatic progressive loading via
  `IntersectionObserver` on scroll.

### Accessibility

- `tabindex="0"` and keyboard activation (Enter/Space) on all interactive elements
- `role="button"` on interactive divs
- `aria-expanded="true"/"false"` on collapsible headers
- `:focus-visible` outlines (2px solid green with offset) for keyboard navigation
- `@media (prefers-reduced-motion: reduce)` disables all animations for motion-sensitive users
- Fallback plain text display if JCEF is unavailable

### Custom Scrollbar

A thin 6px scrollbar styled to match IntelliJ's design language — transparent track, semi-transparent
rounded thumb that brightens on hover.

---

## Configuration & Settings

### Agent Behavior Settings

| Setting                     | Default       | Range   | Description                                                                                                                                       |
|-----------------------------|---------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| **Inactivity Timeout**      | 300 seconds   | 30–600s | Stops the agent if no activity (no streaming chunks or tool calls) for this duration. Prevents hung agents from consuming resources indefinitely. |
| **Max Tool Calls Per Turn** | 0 (unlimited) | 0–500   | Limits tool invocations per turn. `0` means unlimited. Prevents runaway loops where the agent calls the same tool repeatedly.                     |
| **Max Requests Per Turn**   | 0 (unlimited) | 0–500   | Limits model requests per turn. `0` means unlimited. Each approved tool call triggers another request; this caps the total model invocations.     |

### Workflow Automation Settings

| Setting               | Default | Description                                                                    |
|-----------------------|---------|--------------------------------------------------------------------------------|
| **Format After Edit** | `true`  | Automatically run optimize imports + reformat code after every file write.     |
| **Build Before End**  | `false` | Instruct the agent to run `build_project` before completing a task.            |
| **Test Before End**   | `false` | Instruct the agent to run `run_tests` before completing a task.                |
| **Commit Before End** | `false` | Instruct the agent to run `git_stage` + `git_commit` before completing a task. |

When workflow settings are enabled, corresponding instructions are injected into the agent's prompt
(e.g., "Before ending, please: build the project, run tests, and commit your changes").

### Editor Integration

| Setting                | Default | Description                                                                 |
|------------------------|---------|-----------------------------------------------------------------------------|
| **Follow Agent Files** | `false` | Automatically open files in the editor when the agent reads or writes them. |

### Model & Session

| Setting            | Description                                                                                          |
|--------------------|------------------------------------------------------------------------------------------------------|
| **Selected Model** | Persisted model choice (e.g., `claude-sonnet-4.6`). Applied to new sessions via `session/set_model`. |
| **Session Mode**   | `"agent"` (default) or `"plan"`. Plan mode prefixes prompts with `[[PLAN]]` for structured planning. |

### Settings Persistence

All settings use IntelliJ's `PropertiesComponent` for global persistence. Changes take effect
immediately — no IDE restart required.

---

## Permission System

The plugin implements a sophisticated permission system that controls which tools the agent can use,
with three tiers: **Deny**, **Ask**, and **Allow**.

### Automatic Denials (Built-in CLI Tools)

Five built-in Copilot CLI tool permissions are **always denied** at runtime:

| Denied Tool     | Reason                 | Redirect To                                 |
|-----------------|------------------------|---------------------------------------------|
| `edit`          | Reads stale disk files | `intellij_write_file` (live buffers + undo) |
| `create`        | Bypasses VFS indexing  | `create_file` (VFS-integrated)              |
| `read`          | Reads stale disk files | `intellij_read_file` (live buffers)         |
| `execute`       | No output capture      | `run_command` (paginated output)            |
| `runInTerminal` | No integration         | `run_in_terminal` (IDE terminal)            |

**Why?** GitHub Copilot CLI has a known bug (#556) where tool filtering doesn't work in ACP mode.
The plugin works around this by denying permissions at runtime and sending corrective guidance to
redirect the agent toward IntelliJ MCP tools.

### Denial & Auto-Retry Flow

When a tool is denied:

1. **Pre-rejection guidance** is sent to the agent explaining which MCP tool to use instead
2. The denial is logged to the Debug tab
3. If the turn ends with denials, the plugin **auto-retries** up to 3 times with accumulated
   guidance, giving the agent a chance to switch to correct tools

### Abuse Detection

The plugin detects and blocks misuse patterns:

| Pattern                 | Detection                                           | Action                              |
|-------------------------|-----------------------------------------------------|-------------------------------------|
| **run_command abuse**   | Shell commands for tasks covered by dedicated tools | Deny + redirect                     |
| **CLI tool abuse**      | Agent targeting project files with CLI tools        | Deny + redirect                     |
| **Sub-agent git write** | Sub-agent (Task tool) attempting git writes         | Deny (only parent agent may commit) |

### Auto-Approval

All IntelliJ MCP tool calls (`intellij-code-tools-*` prefix) are automatically approved without
user prompting. Read-only operations (view, grep, glob) also auto-execute but trigger guidance
nudging the agent toward IntelliJ tools for subsequent calls.

---

## Reliability & Architecture

### Auto-Restart with Exponential Backoff

If the Copilot CLI process dies unexpectedly, the plugin auto-restarts it with exponential backoff
(delays: 1s → 2s → 4s, maximum 3 attempts). The reader thread detects process exit and triggers
reconnection. On successful restart, the retry counter resets; on exhaustion, a notification prompts
the user to restart the IDE.

### Lazy Initialization

The ACP process doesn't start at IDE launch — it starts on first use when `getClient()` is called.
The PSI Bridge, however, starts immediately (via `StartupActivity.DumbAware`) so MCP tools are ready
the moment you need them. This split reduces IDE startup overhead.

### Activity-Based Timeout

Instead of a fixed timeout, the plugin polls every 5 seconds and checks `lastActivityTimestamp`.
If the agent goes inactive for the configured timeout period, it's terminated gracefully. Tool calls
and streaming chunks reset the timestamp, so genuinely long-running operations continue
uninterrupted while idle agents are terminated.

### Thread Safety

- `ConcurrentHashMap<Long, CompletableFuture<JsonObject>>` for pending requests — safe concurrent
  access without locking
- `ReentrantLock` for writer synchronization — prevents message interleaving on stdout
- `CopyOnWriteArrayList` for notification listeners — safe iteration during dispatch
- `AtomicInteger` for tool call counting — lock-free increment

### Deferred Auto-Formatting

Rather than formatting after every individual write, the plugin accumulates modified files in a
`pendingAutoFormat` set during a turn and formats them all at once when the turn completes. This
prevents formatting churn during multi-file edit sessions and improves performance.

### Graceful Shutdown

Both `CopilotService` and `CopilotAcpClient` implement `Disposable` with synchronized lifecycle
management. On IDE shutdown, the ACP process gets a 5-second grace period to terminate cleanly
before force-kill. All pending request futures are failed with a consistent error to prevent hanging.

### MCP Server Isolation

The MCP Server runs as a separate JAR process, communicating via stdio. If it crashes, only the
current tool call fails — not the entire plugin. The PSI Bridge HTTP server listens only on
`127.0.0.1` (localhost), preventing network exposure.

---

## Comparison

### This Plugin vs. GitHub Copilot CLI (Terminal)

| Aspect                 | This Plugin                                          | CLI in Terminal                                                 |
|------------------------|------------------------------------------------------|-----------------------------------------------------------------|
| **File reads**         | Live editor buffers (includes unsaved changes)       | Disk files (may be stale)                                       |
| **File writes**        | IntelliJ Document API (full undo/redo support)       | Direct filesystem (no undo, triggers "file changed externally") |
| **Code intelligence**  | PSI-based symbol search, inspections, type hierarchy | Text-based grep/find                                            |
| **Test execution**     | IDE test runner with coverage metrics                | Shell-based (`./gradlew test`)                                  |
| **Git operations**     | Integrated with VFS buffer flushing                  | Direct git commands                                             |
| **UI**                 | Native tool window with streaming, plans, timeline   | Terminal JSON output                                            |
| **Context**            | Structured file/selection references with MIME types | Manual paste                                                    |
| **Formatting**         | Auto-format + optimize imports after writes          | None                                                            |
| **Error recovery**     | Auto-restart with exponential backoff                | Manual restart                                                  |
| **Permission control** | Granular per-tool permissions with abuse detection   | None                                                            |

**When to use the CLI instead:** If you prefer working entirely in the terminal, need to use Copilot
outside of IntelliJ, or want to avoid the plugin overhead.

### This Plugin vs. Official GitHub Copilot IntelliJ Plugin

| Aspect                      | This Plugin                                        | Official Plugin                |
|-----------------------------|----------------------------------------------------|--------------------------------|
| **Primary function**        | Multi-turn agentic conversation with 60 tools      | Inline code completions & chat |
| **Agent reasoning**         | Full agent loop — plan, execute, iterate           | No agentic capabilities        |
| **Tool calling**            | 60 MCP tools (code navigation, testing, git, etc.) | No tool calling                |
| **File operations**         | Document API with undo support                     | Inline suggestion acceptance   |
| **Multi-turn conversation** | Yes, with session persistence and plan mode        | Limited chat (no persistence)  |
| **Sub-agents**              | Specialized sub-agents (Explore, Task, Review)     | None                           |
| **Project awareness**       | Full (build system, modules, run configs, SDKs)    | Current file context           |
| **Inspections/refactoring** | Agent can run inspections and apply refactorings   | None                           |
| **Test execution**          | Agent can run and analyze tests                    | None                           |
| **Model selection**         | All available models with cost multiplier display  | Fixed model                    |
| **Code completion**         | Not supported (different use case)                 | Primary feature                |
| **Inline suggestions**      | Not supported                                      | Primary feature                |

**When to use the Official Plugin instead:** For inline code completions while typing, tab-to-accept
suggestions, and lightweight code assistance that doesn't require multi-turn conversation. The two
plugins are complementary — you can use both simultaneously.

### Key Differentiators Summary

1. **IDE-Native File Operations** — Writes through Document API with undo; reads from live buffers
2. **PSI-Based Code Intelligence** — Semantic symbol search, not text grep
3. **60 Specialized MCP Tools** — Inspections, refactoring, testing, run configs, terminal
4. **Permission System** — Prevents agent from using stale filesystem tools
5. **Sub-Agent Architecture** — Isolated sub-agents with restricted git access
6. **Deferred Auto-Formatting** — Batch format at turn end, not per-write
7. **Activity-Based Timeouts** — Smart inactivity detection, not fixed wall-clock
8. **Plan Mode** — Structured planning before execution for complex tasks
9. **Usage Tracking** — Per-turn and monthly billing visibility with model multipliers
10. **Conversation Persistence** — Full history restored on IDE restart with lazy loading

---

## Summary

Agentic GitHub Copilot for JetBrains transforms IntelliJ into a fully agentic development
environment. It takes the reasoning power of GitHub Copilot's agent and gives it deep access to
IntelliJ's code intelligence, testing, and project management APIs through 60 MCP tools.

The plugin handles the complexity of bridging two architectures — the Copilot CLI's ACP protocol
and IntelliJ's PSI/VFS/Document APIs — while providing a polished UI with streaming responses,
collapsible sections, sub-agent conversations, and theme-aware styling.

For developers who want more than inline completions but need tighter IDE integration than a terminal
can provide, this plugin occupies a unique position: the agentic capabilities of the CLI with the
code intelligence of the IDE.

**Total capabilities:** 60 MCP tools • 12 configurable settings • 8-color sub-agent palette •
Multi-model support • Plan & Agent modes • Full undo/redo • Auto-format • Usage tracking •
Auto-restart • Permission control • Conversation persistence
