# Release Notes

## 1.12.0

### Renamed to AgentBridge
The plugin is now called **AgentBridge** — reflecting its evolution from a Copilot-specific
integration into a general-purpose ACP & MCP bridge for any AI coding agent.

### Multi-Agent Support
Connect any ACP-compatible agent — including GitHub Copilot, opencode, and custom configurations:
- Agent profile selector in the connection panel — switch agents with one click
- Per-profile settings: connection command, tool permissions, built-in tool blocking, custom instructions
- Built-in profiles for GitHub Copilot and opencode, plus fully custom profiles
- Agent and sub-agent names shown in chat bubble headers
- Profile-specific message coloring in chat

### Comprehensive Settings UI
Settings reorganized into structured sections:
- **MCP** — Server configuration and individual tool enable/disable
- **ACP** — Agent settings, profiles, and tool permissions
- **Other** — Scratch file types, project files, and billing data
- Plugin version and build info shown in the settings page footer and root connection panel

### New MCP Tools (92 total)
- `undo` / `redo` — undo and redo file changes
- `edit_text` — surgical find-and-replace within a file
- `rename_file` / `move_file` — file renaming and moving via IntelliJ VFS
- `find_implementations` — find all implementations of a class/interface or method overrides
- `get_call_hierarchy` — find all callers of a method
- `get_file_history` — git history for a specific file (including renames)
- `read_build_output` — read from the Build tool window
- `list_terminals` / `read_terminal_output` — terminal tab management
- `list_project_files` now supports sorting, size filters, and date filters

### Permission System
- Three-way permission prompt: Deny / Allow / Allow for Session
- MCP tool annotations for granular permission control
- Sub-agent built-in write tools automatically blocked via permission denial
- Agent-level permission injection per profile

### Follow Agent Mode Improvements
- Status bar feedback during search operations
- `build_project` no longer steals editor focus when Follow Agent is off

### MCP Instructions
Plugin instructions are now sent via the MCP `initialize` response, with a fallback to
`copilot-instructions.md` for agents that don't support it.

### Code Quality
- Gradle compile abuse detection — prevents runaway compilation tasks
- Extensive SonarQube finding fixes across the codebase
- Reduced cognitive complexity in core tool handlers
- Extracted QodanaAnalyzer from CodeQualityTools for better separation

### UI Cleanup
- Removed debug, timeline, and help panels — cleaner tool window
- Version number shown on the root connection panel and settings page

### Bug Fixes
- Profile names no longer gain "(Copy)" suffix on every settings open
- MCP tools now work during modal dialogs
- Fixed paste-to-scratch file bugs
- Fixed `git_reset` path+commit handling
- Fixed `read_run_output` for Gradle consoles and JUnit runners
- Fixed backtick escaping in chat messages
- Fixed .kts file type misclassification
- Fixed missing imports in several files

---

## 1.5.0

### Follow Agent Mode
The IDE now visually follows the agent as it works, enabled by default:
- Highlight code as the agent reads/edits it
- Editor shows "Agent is reading/editing" inlay labels
- Project explorer marks files with read/write indicators
- Git Log opens and selects commits after the agent commits

### Redesigned Chat UI
Chat now looks and feels native to JetBrains:
- JetBrains-style markdown — tables with bottom-border rows, accent-bordered code blocks, heading hierarchy, blockquote support
- Quick-reply buttons — clickable suggestions with semantic colors and dismiss support
- Code block enhancements — language labels, "Open in Scratch" button, word wrap toggle, clipboard icon
- Native Swing banners — status/auth/git banners use JetBrains InlineBanner instead of HTML

### Scratch File Execution
Run code directly from chat — Java, JavaScript, TypeScript, Kotlin Script, Groovy, and Python scratch files can be created and executed.

### Project Structure Management
New edit_project_structure tool lets the agent manage module dependencies, libraries, and SDKs programmatically.

### Conversation History
New search_conversation_history tool to search and recall past conversations across sessions.

### Git Enhancements
- 7 new git tools: fetch, pull, merge, rebase, cherry-pick, tag, reset
- Commit hashes in chat are clickable links that navigate to the VCS Log
- Git operations routed through IntelliJ Git4Idea infrastructure
- Agent commits automatically appear in the Git Log

### Theme and Terminal Tools
- list_themes / set_theme — agent can switch your IDE theme
- write_terminal_input — agent can interact with terminal sessions

### Multi-IDE Compatibility
Java-specific code isolated so the plugin can run in WebStorm, PyCharm, and other non-Java IDEs.

### Reliability
- Auto-retry on stale Copilot sessions
- Graceful handling of Copilot process crashes
- Chat saved incrementally during streaming
- Reduced rendering artifacts during streaming

---

## 1.0.0 — Initial Release

- Introduces IDE Agent for Copilot
- Dynamic model discovery — automatically supports all Copilot models without plugin updates
- Enables multi-step task execution inside the IDE
- Project navigation and context awareness
- Multi-file editing capabilities
- Integration with inspections and refactorings
- Test and build execution support
- Git operation support
- Local-first design with no telemetry
