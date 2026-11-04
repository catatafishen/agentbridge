# Agent Definition Formats

Different ACP agents support custom agent definitions with tool filtering, but use different formats. This is a
reference for people adding or maintaining an agent integration; see [ADDING-A-NEW-AGENT.md](ADDING-A-NEW-AGENT.md)
for the full checklist.

## Copilot CLI

**Location**: `~/.copilot/agents/*.md`  
**Format**: YAML frontmatter with `tools:` array

```yaml
---
name: intellij-explore
description: "Fast codebase explorer using IntelliJ code intelligence"
model: claude-haiku-4.5
tools:
  - agentbridge/read_file
  - agentbridge/search_text
  - agentbridge/search_symbols
  - agentbridge/get_file_outline
  - agentbridge/list_project_files
---

System prompt goes here...
```

**Bundled Agents**:

- `intellij-explore.md` — fast codebase exploration (read-only MCP tools)
- `ide-task.md` — task execution with IntelliJ tools

**Status**: ✅ Working (agent definitions loaded), but built-in tool filtering is broken
([bug #556](https://github.com/github/copilot-cli/issues/556))

## OpenCode

**Location**: `.opencode/agent/*.md` or JSON config via `OPENCODE_CONFIG_CONTENT`  
**Format**: YAML frontmatter with `permission:` object (recommended) or deprecated `tools:` object

### Recommended Format (permission-based)

```yaml
---
name: explore
description: "Fast codebase explorer"
mode: subagent
model: claude-haiku-4.5
permission:
  "*": deny              # Deny all tools by default
  grep: allow
  glob: allow
  list: allow
  read: allow
---

System prompt goes here...
```

### Deprecated Format (tools object)

```yaml
---
name: triage
mode: primary
model: opencode/minimax-m2.5
tools:
  "*": false              # Deny all tools (wildcard)
  "github-triage": true   # Allow specific tool
---

System prompt goes here...
```

**Key Differences from Copilot**:

- Uses **object format** (`{"tool": true/false}`) instead of array
- Supports **wildcard deny** (`"*": false` or `"*": "deny"`)
- Prefers `permission:` field over deprecated `tools:` field
- Permission values: `"allow"`, `"deny"`, `"ask"`

**Bundled Agents**:

- `ide-general` — general-purpose agent with most IntelliJ tools enabled (default)
- `intellij-explore` — read-only exploration agent with code intelligence

Injected via `OPENCODE_CONFIG_CONTENT` JSON config (not `.md` files).

**Status**: ✅ Working (permission config + agent definitions fully supported)

## Junie CLI

**Location**: Not supported  
**Format**: N/A

**Status**: ❌ No agent definition support. No built-in tool filtering. All tools auto-execute without permission
requests.

**Workaround**: Prompt engineering via `session/message` startup instructions.
See [JUNIE-TOOL-WORKAROUND.md](JUNIE-TOOL-WORKAROUND.md).

## Kiro CLI

**Location**: `.agent-work/.kiro/agents/*.json`  
**Format**: JSON configuration with `tools` and `allowedTools` arrays

```json
{
  "name": "intellij-agent",
  "tools": [
    "web_search",
    "@agentbridge/*"
  ],
  "allowedTools": [
    "@agentbridge/read_file",
    "web_search"
  ]
}
```

**Bundled Agents**:

- `intellij-agent` — created dynamically by the plugin

**Status**: ⚠️ **Experimental** (agent definitions supported via `allowedTools`, but hangs on non-allowed tool prompts)

## Hermes Agent

**Location**: `~/.hermes/skills/*` (skills, not agent definitions)  
**Format**: N/A — Hermes does not use ACP-side agent-definition files for tool filtering. Tool gating happens through
Hermes's own toolset/skill system in `~/.hermes/config.yaml`.

**Bundled Agents**: 0 (Hermes integrates as a single agent; sub-agents are spawned via its own `delegate_task` tool, not
ACP-level agent definitions)

**MCP Tool Prefix**: `mcp_agentbridge_` (Hermes names MCP tools as `mcp_<server>_<tool>`)

**Status**: ✅ Working (HTTP MCP server injected via `session/new`; requires `hermes acp --accept-hooks` because the
plugin launches Hermes without a TTY for hook prompts)

## Mistral Vibe

**Location**: Not yet investigated  
**Format**: N/A — Vibe's agent definition support has not been investigated yet.

**Bundled Agents**: 0

**MCP Tool Prefix**: `agentbridge_` (Vibe names MCP tools as `{server_name}_{tool_name}`)

**Status**: ⚠️ **Experimental** (basic ACP integration works; permission requests and agent definitions not yet tested)

## Summary Table

| Agent    | MCP Tool Prefix    | Agent Definition Support          | Tool Filtering Format                                      | Permission Requests     | Bundled Agents                      | Status                     |
|----------|--------------------|-----------------------------------|------------------------------------------------------------|-------------------------|-------------------------------------|----------------------------|
| Copilot  | `agentbridge-`     | ✅ `~/.copilot/agents/*.md`       | YAML array: `tools: [tool1, tool2]`                        | ✅ For write tools      | 2 (intellij-explore, intellij-task) | Working (filtering broken) |
| OpenCode | `agentbridge_`     | ✅ `.opencode/agent/*.md` or JSON | YAML object: `permission: {"*": "deny", "tool1": "allow"}` | ✅ Yes                  | 2 (ide-general, intellij-explore)   | ✅ Working                 |
| Junie    | `agentbridge-`     | ❌ No support                     | N/A                                                        | ❌ No (auto-executes)   | 0                                   | Prompt workaround only     |
| Kiro     | `@agentbridge/`    | ✅ `.agent-work/.kiro/agents/`    | JSON: `allowedTools: ["tool1"]`                            | ⚠️ Hangs on prompts     | 1 (intellij-agent)                  | ⚠️ Experimental (hangs)    |
| Hermes   | `mcp_agentbridge_` | ❌ No ACP-side definitions        | N/A (gating via `~/.hermes/config.yaml` toolsets/skills)   | ✅ Via `--accept-hooks` | 0 (sub-agents via `delegate_task`)  | ✅ Working                 |
| Vibe     | `agentbridge_`     | ❌ Not yet investigated           | N/A                                                        | ⚠️ Not yet tested       | 0                                   | ⚠️ Experimental            |
