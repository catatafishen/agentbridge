# Copilot CLI Built-in Tools

Inventory of the built-in tools of Copilot CLI (checked against 1.0.92, sessions from 1.0.80–1.0.87) and whether each
should be hidden from the model via `--excluded-tools` or kept via a possible `--available-tools` allowlist.
Current wiring: `CopilotClient.DEFAULT_EXCLUDED_BUILT_IN_TOOLS` / `CopilotClient.buildCommand()`.

## How the filters behave

- `--excluded-tools` hides only the listed tools. `--available-tools` hides everything not listed (CLI help:
  "disables all other tools"). Both decide what the model *sees*; `--allow-tool` / `--deny-tool` only control approval
  prompts and never re-expose a filtered tool.
- Both flags are honored in ACP mode (changelog: "apply correctly in ACP mode").
- The filter syntax for MCP tools is **not documented** and is unverified for `--available-tools`. Session events name
  our tools `agentbridge-<tool>` (`mcpServerName=agentbridge`, `mcpToolName=<tool>`), while agent `.md` files use
  `agentbridge/<tool>`.

## Legend

- **Exclude?** — should the name be in `--excluded-tools`? `Yes` / `No` / `?` (undecided).
- **Include?** — should the name be in a `--available-tools` allowlist? `Yes` / `No` / `?` (undecided).
- **Now** — is the name in the current `DEFAULT_EXCLUDED_BUILT_IN_TOOLS`?
- **Evidence** — `log` = seen as `toolName` in `~/.copilot/session-state`, `js` = string present in the CLI bundle,
  `def` = named in a bundled `definitions/*.agent.yaml`, `code` = named in `KNOWN_BUILTIN_TOOL_NAMES`.

## File and search tools (overlap with agentbridge `read_file`, `edit_text`, `write_file`, `search_text`)

| Tool                   | Purpose                 | Evidence      | Now | Exclude? | Include? | Notes                                                           |
|------------------------|-------------------------|---------------|-----|----------|----------|-----------------------------------------------------------------|
| `view`                 | Read file / list dir    | log, js, def  | yes | Yes      | No       |                                                                 |
| `edit`                 | Edit file               | log, js       | yes | Yes      | No       | Bypasses the IDE editor buffer                                  |
| `create`               | Create file             | log, js       | yes | Yes      | No       |                                                                 |
| `apply_patch`          | Apply a patch           | js            | yes | Yes      | No       | Model-specific (OpenAI-style models)                            |
| `str_replace_editor`   | Edit file (legacy name) | js (2 hits)   | yes | Yes      | No       | Unclear whether still exposed; excluded defensively             |
| `glob`                 | Find files by pattern   | log?, js, def | yes | Yes      | No       |                                                                 |
| `grep`                 | Search file contents    | js, def       | yes | Yes      | No       |                                                                 |
| `rg`                   | Ripgrep variant         | log, js       | yes | Yes      | No       | Model-specific name for `grep`                                  |
| `grep_search`          | Search file contents    | log           | yes | Yes      | No       | Not in CLI bundle; origin unclear (model-specific?)             |
| `file_search`          | Find files              | log           | yes | Yes      | No       | Not in CLI bundle; origin unclear                               |
| `search_code_subagent` | Summarising code search | log           | yes | Yes      | No       | AGENTS.md says not to rely on it; origin unclear (host-side?)   |
| `lsp`                  | Language server queries | js, def       | yes | Yes      | No       | Overlaps IDE code intelligence (`get_symbol_info`, etc.)        |

## Shell tools (overlap with agentbridge `run_command`, `run_in_terminal`)

| Tool               | Purpose                      | Evidence     | Now | Exclude? | Include? | Notes                                     |
|--------------------|------------------------------|--------------|-----|----------|----------|-------------------------------------------|
| `bash`             | Run shell command            | log, js, def | yes | Yes      | No       | Still used by sub-agents                  |
| `read_bash`        | Read output of async shell   | log, def     | yes | Yes      | No       | Useless without `bash`; 247 calls in logs |
| `write_bash`       | Send input to shell          | js           | yes | Yes      | No       |                                           |
| `stop_bash`        | Stop a shell session         | def          | yes | Yes      | No       |                                           |
| `list_bash`        | List shell sessions          | log          | yes | Yes      | No       |                                           |
| `powershell`       | Run PowerShell command       | js, def      | yes | Yes      | No       | Windows; excluding is harmless on Linux   |
| `read_powershell`  | Read async PowerShell output | def          | yes | Yes      | No       |                                           |
| `write_powershell` | Send input to PowerShell     | js           | yes | Yes      | No       |                                           |
| `stop_powershell`  | Stop a PowerShell session    | def          | yes | Yes      | No       |                                           |

## Tools with no agentbridge equivalent (keep)

| Tool                | Purpose                            | Evidence           | Now | Exclude? | Include? | Notes                                                         |
|---------------------|------------------------------------|--------------------|-----|----------|----------|---------------------------------------------------------------|
| `web_fetch`         | Fetch a URL                        | log, js            | no  | No       | Yes      | Our `http_request` is more limited                            |
| `web_search`        | Web search                         | log, js            | no  | No       | Yes      |                                                               |
| `sql`               | Session SQL DB (todos table, etc.) | log, js            | no  | No       | Yes      | Believed to back the internal todo list; not confirmed        |
| `session_store_sql` | Query past-session store           | log, js            | no  | No       | Yes      |                                                               |
| `task`              | Launch a sub-agent                 | log, js, def, code | no  | No       | Yes      | Was in the old exclude list; sub-agents use `bash` etc.       |
| `read_agent`        | Read a sub-agent's result          | log, js            | no  | No       | Yes      |                                                               |
| `write_agent`       | Send a message to a sub-agent      | log, js            | no  | No       | Yes      |                                                               |
| `list_agents`       | List sub-agents                    | log, js            | no  | No       | Yes      |                                                               |
| `skill`             | Invoke a skill                     | log, js, code      | no  | No       | Yes      |                                                               |
| `tool_search_tool`  | Load deferred tools on demand      | log                | no  | No       | Yes      | 525 calls; if blocked, deferred MCP tools may never surface   |
| `report_intent`     | Report current intent to the UI    | code               | no  | No       | Yes      | Not seen in recent logs; was in the very first exclude list   |
| `task_complete`     | Signal task completion             | js, code           | no  | No       | Yes      | Not seen in recent logs                                       |
| `ask_user`          | Ask the user a question            | log, js            | no  | ?        | ?        | Overlaps agentbridge `prompt_user`; check how ACP surfaces it |
| `exit_plan_mode`    | Leave plan mode                    | js                 | no  | No       | ?        | Only relevant if plan mode is used                            |
| `store_memory`      | Persist a memory                   | none               | no  | ?        | ?        | Not found anywhere; may not exist in 1.0.92                   |

## MCP tools

| Source                      | Evidence | Exclude? | Include? | Notes                                                                             |
|-----------------------------|----------|----------|----------|-----------------------------------------------------------------------------------|
| `agentbridge` MCP server    | log      | No       | Yes      | **Filter name form unverified** (`agentbridge-x`, `agentbridge/x`, `agentbridge`) |
| `github-mcp-server`         | js       | ?        | ?        | Already removed with `--disable-builtin-mcps`                                     |
| User-configured MCP servers | log      | No       | ?        | An allowlist would hide them unless the user adds them (e.g. a DB tool)           |
| `Intellij-*` (old name)     | log      | No       | No       | Legacy server name from older plugin versions                                     |

## Unknown-tool prompt

When Copilot calls a tool AgentBridge has never classified, a balloon notification offers **Exclude `<tool>`** or
**Keep available**. It never blocks the agent, appears at most once per session and tool, and is skipped while a
session's history is being replayed. Accepting appends the tool to the Copilot profile's excluded list; it applies
the next time Copilot starts because `--excluded-tools` is read at launch.

- A name counts as classified if it is in `CopilotBuiltInTools.DEFAULT_EXCLUDED`, in `CopilotBuiltInTools.KNOWN_KEPT`
  (the "keep" table above), or in the user's configured list. Add newly identified tools to one of the first two.
- **Detection limit:** the ACP `tool_call` has no separate tool-name field, only a display title. Tools with a custom
  title (`sql` shows as e.g. "Update review todo", `skill` as "Using skill: x", `web_fetch` as "Fetching host/path")
  cannot be recognised, so only titles shaped like a snake_case tool name (`read_bash`, `list_agents`, ...) are
  considered. The authoritative name is `toolName` in `~/.copilot/session-state/<id>/events.jsonl`, a CLI-internal
  format we do not read.
- Settings → GitHub Copilot → *Excluded built-in tools* has a **Reset to Defaults** button. A saved custom list
  replaces the default entirely, so it does not pick up tools added to the default later.

## Open questions

1. Which name form does `--available-tools` accept for MCP tools, and does a bare server name cover all its tools?
2. Do `--excluded-tools` / `--available-tools` apply inside sub-agents? Logs show sub-agents (`parentToolCallId` set,
   `claude-haiku-4.5`) still calling `bash`, and bundled `task` / `research` / `code-review` agents declare
   `tools: "*"`.
3. Is the internal todo list really `sql`, or is there a separate tool?
4. Are `grep_search`, `file_search`, `search_code_subagent` produced by the CLI, or by a model/host layer outside the
   bundle? Are `str_replace_editor` and `store_memory` still exposed?
5. How should `ask_user` be treated relative to our `prompt_user`?
6. Is `tool_search_tool` needed for our MCP tools to be reachable, or only for large tool sets?
