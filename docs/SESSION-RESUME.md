# Cross-Client Session Resume

This document describes the session resume feature that preserves conversation context when
switching between agent clients (e.g., Claude CLI → Copilot, Copilot → Kiro).

## Overview

Each agent client has its own native session format (JSONL, SQLite, etc.). The plugin maintains
a **universal v2 JSONL format** as the intermediary. When the user switches agents, the pipeline
is:

```
Previous Client (native format)
        │
        ▼  import
Universal v2 JSONL  (.agent-work/sessions/<uuid>.jsonl)
        │
        ▼  export
New Client (native format) + resumeSessionId
```

The orchestrator is `SessionSwitchService`, triggered by `ActiveAgentManager.switchAgent()`.

## Architecture

### Key Classes

| Class | Role |
|-------|------|
| `SessionSwitchService` | Orchestrator: import → load v2 → export on agent switch |
| `SessionMessage` | Universal message record (id, role, parts, createdAt, agent, model) |
| `SessionStoreV2` | Reads/writes v2 JSONL sessions on disk |
| `EntryDataConverter` | Converts between UI model (`EntryData`) and `SessionMessage` |
| `V1ToV2Migrator` | One-shot migration from old `conversation.json` to v2 |
| `GenericSettings` | Persists `resumeSessionId` per profile via `PropertiesComponent` |

### Importers (client native → v2 `SessionMessage`)

| Class | Agent | Source Format |
|-------|-------|---------------|
| `AnthropicClientImporter` | Claude CLI, Kiro, Junie | Anthropic message JSONL |
| `CopilotClientImporter` | Copilot CLI | `events.jsonl` (event stream) |
| `CodexClientImporter` | Codex | SQLite + rollout JSONL |
| `OpenCodeClientImporter` | OpenCode | SQLite (session/message/part tables) |

### Exporters (v2 `SessionMessage` → client native)

| Class | Agent | Target Format |
|-------|-------|---------------|
| `AnthropicClientExporter` | Claude CLI, Kiro, Junie | Anthropic message JSONL |
| `CopilotClientExporter` | Copilot CLI | `events.jsonl` (event stream) |
| `CodexClientExporter` | Codex | Rollout JSONL + SQLite |
| `OpenCodeClientExporter` | OpenCode | SQLite |

## Session Switch Flow (step by step)

When the user switches from agent A to agent B:

1. **`ActiveAgentManager.switchAgent(profileId)`** is called
   - Stops the current agent (`stop()`)
   - Updates the active profile in `PropertiesComponent`
   - Fires switch listeners
   - Calls `SessionSwitchService.onAgentSwitch(prevId, toId)` on a pooled thread

2. **`SessionSwitchService.doExport(fromId, toId)`** executes 3 steps:

   **Step 1 — Import from previous client** (`importFromPreviousClient`):
   Best-effort import of the outgoing agent's native session files into v2 storage.
   This handles the case where the user ran the client directly outside the plugin
   (e.g., ran `claude` from the terminal). The import compares `imported.size()` vs
   current v2 message count and only overwrites if the import has more messages.

   **Step 2 — Load current v2 session** (`loadCurrentV2Session`):
   Reads the active session from `.agent-work/sessions/<uuid>.jsonl`.
   If empty or missing, the export is aborted.

   **Step 3 — Export to new client** (dispatch by profile ID):
   Converts v2 messages to the target agent's native format AND persists the resume
   identifier so the agent client knows to resume on next `session/new`.

3. **Agent client starts** and calls `AcpClient.createSession()`:
   - `buildNewSessionParams()` reads `resumeSessionId` from `GenericSettings`
   - If set, adds `"resumeSessionId": "<id>"` to the ACP `session/new` request
   - The ACP server (Copilot CLI, Kiro, etc.) loads the session by that ID

## Native Session Locations

| Agent | Session Storage Path | Format |
|-------|---------------------|--------|
| Claude CLI | `~/.claude/projects/<sha1-of-cwd>/<uuid>.jsonl` | Anthropic messages |
| Kiro | `~/.kiro/sessions/<uuid>/session.json` + `messages.jsonl` | Anthropic messages |
| Junie | `~/.junie/sessions/<uuid>/session.json` + `messages.jsonl` | Anthropic messages |
| Copilot CLI | `<project>/.agent-work/copilot/session-state/<uuid>/events.jsonl` | Copilot events |
| Codex | `~/.codex/sessions/<thread-id>/rollout.jsonl` + `~/.codex/codex.db` | Rollout JSONL + SQLite |
| OpenCode | `~/.local/share/opencode/opencode.db` | SQLite (session/message/part) |

## Universal v2 Format

**Disk layout:**
```
<project>/.agent-work/
  sessions/
    sessions-index.json      ← JSON array of SessionRecord {id, agent, createdAt, updatedAt}
    .current-session-id      ← text file with active session UUID
    <uuid>.jsonl             ← one SessionMessage per line
```

**`SessionMessage` fields:**
- `id` — UUID identifying this message
- `role` — `"user"`, `"assistant"`, or `"separator"`
- `parts` — ordered list of `JsonObject` content parts
- `createdAt` — Unix epoch milliseconds
- `agent` — optional agent display name
- `model` — optional model ID

**Part types** (each part is a `JsonObject` with a `"type"` field):
- `"text"` — plain text content
- `"reasoning"` — thinking/chain-of-thought blocks
- `"tool-invocation"` — tool call (toolCallId, toolName, args, result, state)
- `"subagent"` — sub-agent delegation (agentType, description, prompt, result, status)
- `"status"` — status indicator (icon, message)
- `"file"` — attached file (filename, path)

## Resume Identifier Per Client

Each export method must persist a resume identifier so the agent client can request
continuation of the exported session. The mechanism varies by client:

| Agent | Resume Mechanism | Persisted Where |
|-------|-----------------|-----------------|
| Copilot | `--resume=<id>` CLI flag (ACP `resumeSessionId` is ignored) | `GenericSettings("copilot", project)` |
| Kiro | `resumeSessionId` in ACP `session/new` | `GenericSettings("kiro", project)` |
| Junie | `resumeSessionId` in ACP `session/new` | `GenericSettings("junie", project)` |
| Claude CLI | `--resume <id>` CLI flag | `cliSessionIds` map (in-memory only) + `cliResumeSessionId` property |
| Codex | `codexThreadId` | `PropertiesComponent` (profile-prefixed) |
| OpenCode | (no resume ID — exports directly to SQLite) | N/A |

**Critical:** If the exporter writes session files but does NOT set the resume ID,
the new agent will start a blank session and the exported files are orphaned.

## UI Session Picker

The connect panel (`AcpConnectPanel.kt`) provides a "Resume session" dropdown:
- **Latest** — keeps the existing `resumeSessionId` (default)
- **None** — clears `resumeSessionId` → fresh session
- **Older** — switches `.current-session-id` to the selected session, clears `resumeSessionId`

The `applySessionChoice()` method in `AcpConnectPanel` sets the `resumeSessionId` in
`GenericSettings` before triggering `onConnect()`.

## Debugging Checklist

When session resume isn't working for a specific agent switch path:

1. **Check the export method** in `SessionSwitchService`:
   - Does it write session files to the correct location?
   - Does it set `resumeSessionId` (or equivalent) for the target profile?
   - Without setting the resume ID, the new agent starts blank.

2. **Check the v2 session** exists and has content:
   - Look in `.agent-work/sessions/<uuid>.jsonl`
   - Check `.agent-work/sessions/.current-session-id` points to the right UUID

3. **Check the import** from the previous agent:
   - Does the previous agent's native session directory exist?
   - Did the import produce more messages than the existing v2 session?

4. **Check the ACP `session/new` request** (in IDE logs):
   - Does it include `resumeSessionId`?
   - Is the ID a valid session that the target CLI recognizes?

5. **Check for race conditions**:
   - `doExport()` runs async on a pooled thread
   - The agent connection might start before the export completes
   - Look for log entries: "Exported v2 session to ..." vs "session/new" timing

6. **Check `GenericSettings` key**:
   - Keys are `<profileId>.resumeSessionId` (e.g., `copilot.resumeSessionId`)
   - Stored in `PropertiesComponent` at the project level
   - `loadResumeSessionId()` in `AcpClient` reads from `ActiveAgentManager.getSettings()`

## Token Budget

`AnthropicClientExporter` applies a token budget (default 20,000 tokens) when exporting,
selecting the most recent messages that fit. This prevents oversized context windows when
restoring long conversations.
