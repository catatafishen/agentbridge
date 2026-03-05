# Release Notes

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
