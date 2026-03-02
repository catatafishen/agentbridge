# IDE Agent for Copilot

A lightweight IntelliJ Platform plugin that embeds GitHub Copilot's agent capabilities directly into your IDE via the
**Agent Client Protocol (ACP)**.

It provides **MCP-based code intelligence tools** that leverage IntelliJ's native APIs for symbol search, code
formatting, test execution, git operations, and file operations.

## Status

**Working** — Plugin is functional with full Copilot agent integration.

### What Works

- Multi-turn conversation with GitHub Copilot agent
- 69 IntelliJ-native MCP tools (symbol search, file outline, references, test runner, code formatting, git,
  infrastructure, terminal, etc.)
- Built-in file operations redirected through IntelliJ Document API (undo support, no external file conflicts)
- Auto-format (optimize imports + reformat code) after every write
- Model selection with usage multiplier display
- Context management (attach files/selections to prompts)
- Session info panel with plan visualization and timeline
- Real-time streaming responses

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                 IntelliJ IDEA Plugin (Java 21)               │
│  ┌────────────────┐  ┌──────────────────────────────────────┐│
│  │   Tool Window   │  │          CopilotAcpClient            ││
│  │    (Swing)      │  │  - JSON-RPC 2.0 over stdin/stdout   ││
│  │                 │  │  - Permission handler (deny edits)   ││
│  │  - Chat console │  │  - Retry with MCP tool instruction   ││
│  │  - Toolbar      │  │  - Streaming chunk delivery          ││
│  │  - Prompt input │  └──────────────┬───────────────────────┘│
│  │                 │                 │ spawns                  │
│  └─────────────────┘                 │                         │
│                                      ▼                         │
│  ┌──────────────────┐    ┌───────────────────────┐            │
│  │ PsiBridgeService │◄───│  Copilot CLI (--acp)  │            │
│  │  (HTTP server)   │    │                       │            │
│  │  69 MCP tools    │    │  - Agent reasoning    │            │
│  │  - read/write    │    │  - Tool selection     │            │
│  │  - format        │    │  - Permission reqs    │            │
│  │  - search        │    └───────────┬───────────┘            │
│  │  - test runner   │               │                         │
│  └──────────────────┘               ▼                         │
│                          ┌──────────────────────┐             │
│                          │  MCP Server (JAR)    │             │
│                          │  intellij-code-tools │             │
│                          │  (stdio bridge)      │             │
│                          └──────────────────────┘             │
└──────────────────────────────────────────────────────────────┘
```

### Key Design: IntelliJ-Native File Operations

Built-in Copilot file edits are **denied** at the permission level. The agent automatically retries using
`intellij_write_file` MCP tool, which:

- Writes through IntelliJ's Document API (supports undo/redo)
- Auto-runs optimize imports + reformat code after every write
- Changes appear immediately in the editor (no "file changed externally" dialog)
- New files are created through VFS for proper project indexing

### Module Structure

```
intellij-copilot-plugin/
├── plugin-core/          # Main plugin (Java 21)
│   └── src/main/java/com/github/catatafishen/ideagentforcopilot/
│       ├── ui/           # Tool Window (Swing)
│       ├── services/     # CopilotService, CopilotSettings
│       ├── bridge/       # CopilotAcpClient (ACP protocol)
│       └── psi/          # PsiBridgeService (69 MCP tools)
├── mcp-server/           # MCP stdio server (bundled JAR)
│   └── src/main/java/com/github/copilot/mcp/
│       └── McpServer.java
└── integration-tests/    # (placeholder)
```

## MCP Tools (69 tools)

| Category            | Tools                                                                                                                                                                                                                   |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Code Navigation** | `search_symbols`, `get_file_outline`, `get_class_outline`, `find_references`, `list_project_files`, `search_text`                                                                                                       |
| **File I/O**        | `intellij_read_file`, `intellij_write_file`, `create_file`, `delete_file`, `undo`, `reload_from_disk`                                                                                                                   |
| **Code Quality**    | `get_problems`, `get_highlights`, `run_inspections`, `apply_quickfix`, `suppress_inspection`, `optimize_imports`, `format_code`, `add_to_dictionary`, `get_compilation_errors`, `run_qodana`, `run_sonarqube_analysis`* |
| **Refactoring**     | `refactor`, `go_to_declaration`, `get_type_hierarchy`, `get_documentation`                                                                                                                                              |
| **Testing**         | `list_tests`, `run_tests`, `get_coverage`                                                                                                                                                                               |
| **Project**         | `get_project_info`, `build_project`, `get_indexing_status`, `download_sources`, `mark_directory`, `list_run_configurations`, `run_configuration`, `create_run_configuration`, `edit_run_configuration`                  |
| **Git**             | `git_status`, `git_diff`, `git_log`, `git_blame`, `git_commit`, `git_stage`, `git_unstage`, `git_branch`, `git_stash`, `git_show`, `git_push`, `git_remote`                                                             |
| **Infrastructure**  | `http_request`, `run_command`, `read_ide_log`, `get_notifications`, `read_run_output`                                                                                                                                   |
| **Terminal**        | `run_in_terminal`, `write_terminal_input`, `read_terminal_output`                                                                                                                                                       |
| **Editor**          | `open_in_editor`, `show_diff`, `create_scratch_file`, `list_scratch_files`, `get_active_file`, `get_open_editors`, `get_chat_html`, `list_themes`, `set_theme`                                                                                      |

*\* `run_sonarqube_analysis` only available when SonarLint plugin is installed.*

## Requirements

- **JDK 21** (for plugin development)
- **IntelliJ IDEA 2025.1+** (any JetBrains IDE, through 2025.3)
- **GitHub Copilot CLI** (`npm install -g @anthropic-ai/copilot-cli` or `winget install GitHub.Copilot`)
- **GitHub Copilot Subscription** (active)

## Quick Start

### Building

```powershell
$env:JAVA_HOME = "path\to\jdk-21"
.\gradlew.bat :plugin-core:clean :plugin-core:buildPlugin
```

### Installing

Install via **Settings → Plugins → ⚙ → Install Plugin from Disk**, selecting the built ZIP.

**Or manually:**

**Windows (PowerShell):**

```powershell
# Close IntelliJ first, then:
Remove-Item "$env:APPDATA\JetBrains\IntelliJIdea2025.3\plugins\plugin-core" -Recurse -Force
Expand-Archive "plugin-core\build\distributions\plugin-core-*.zip" `
    "$env:APPDATA\JetBrains\IntelliJIdea2025.3\plugins" -Force
```

**Linux:**

```bash
# Toolbox-managed: plugins are direct subfolders (no /plugins parent)
PLUGIN_DIR=~/.local/share/JetBrains/IntelliJIdea2025.3
rm -rf "$PLUGIN_DIR/plugin-core"
unzip -q plugin-core/build/distributions/plugin-core-*.zip -d "$PLUGIN_DIR"
```

### Running Tests

```powershell
.\gradlew.bat test    # All tests (unit + MCP)
```

## Technology Stack

- **Plugin**: Java 21, IntelliJ Platform SDK 2025.x, Swing
- **Protocol**: ACP (Agent Client Protocol) over JSON-RPC 2.0 / stdin+stdout
- **MCP Tools**: Model Context Protocol over stdio
- **Build**: Gradle 8.x with Kotlin DSL
- **Testing**: JUnit 5

## Known Copilot Platform Issues

Tracked issues on the Copilot CLI side that affect this plugin. When an issue is resolved upstream, the workaround can
be removed and the entry marked as ✅.

| # | Issue                                                                                         | Status  | Impact                                                                                                                                                                 | Workaround                                                                                                                                                                                       |
|---|-----------------------------------------------------------------------------------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | [#1486](https://github.com/github/copilot-cli/issues/1486) — MCP `instructions` field ignored | 🔴 Open | Copilot ignores the `instructions` field from the MCP `initialize` response, so the agent never sees our tool-usage guidance.                                          | Plugin prepends instructions to `copilot-instructions.md` on project open ([PsiBridgeStartup.kt](plugin-core/src/main/java/com/github/catatafishen/ideagentforcopilot/psi/PsiBridgeStartup.kt)). |
| 2 | [#556](https://github.com/github/copilot-cli/issues/556) — Tool filtering not respected       | 🔴 Open | `--available-tools` / `--excluded-tools` CLI flags and `tools/remove` MCP capability are all ignored. Built-in tools (`view`, `edit`, `bash`, etc.) cannot be removed. | Permission denial via ACP + redirect messages. See [CLI-BUG-556-WORKAROUND.md](docs/CLI-BUG-556-WORKAROUND.md).                                                                                  |

## Documentation

- [Development Guide](DEVELOPMENT.md) — Build, deploy, architecture details
- [Quick Start](QUICK-START.md) — Fast setup instructions
- [Testing](TESTING.md) — Test running and coverage
- [Roadmap](ROADMAP.md) — Project phases and future work
- [Release Notes](RELEASE_NOTES.md) — Current release details

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Security

To report security vulnerabilities, please see [SECURITY.md](SECURITY.md).

## License

Copyright 2026 Henrik Westergård

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

See [LICENSE](LICENSE) for the full text.
