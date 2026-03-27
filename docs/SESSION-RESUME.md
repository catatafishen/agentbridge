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

| Class                  | Role                                                                |
|------------------------|---------------------------------------------------------------------|
| `SessionSwitchService` | Orchestrator: import → load v2 → export on agent switch             |
| `SessionMessage`       | Universal message record (id, role, parts, createdAt, agent, model) |
| `SessionStoreV2`       | Reads/writes v2 JSONL sessions on disk                              |
| `EntryDataConverter`   | Converts between UI model (`EntryData`) and `SessionMessage`        |
| `V1ToV2Migrator`       | One-shot migration from old `conversation.json` to v2               |
| `GenericSettings`      | Persists `resumeSessionId` per profile via `PropertiesComponent`    |

### Importers (client native → v2 `SessionMessage`)

| Class                     | Agent                   | Source Format                        |
|---------------------------|-------------------------|--------------------------------------|
| `AnthropicClientImporter` | Claude CLI, Kiro, Junie | Anthropic message JSONL              |
| `CopilotClientImporter`   | Copilot CLI             | `events.jsonl` (event stream)        |
| `CodexClientImporter`     | Codex                   | SQLite + rollout JSONL               |
| `OpenCodeClientImporter`  | OpenCode                | SQLite (session/message/part tables) |

### Exporters (v2 `SessionMessage` → client native)

| Class                     | Agent                   | Target Format                 |
|---------------------------|-------------------------|-------------------------------|
| `AnthropicClientExporter` | Claude CLI, Kiro, Junie | Anthropic message JSONL       |
| `CopilotClientExporter`   | Copilot CLI             | `events.jsonl` (event stream) |
| `CodexClientExporter`     | Codex                   | Rollout JSONL + SQLite        |
| `OpenCodeClientExporter`  | OpenCode                | SQLite                        |

## Session Switch Flow (step by step)

When the user switches from agent A to agent B:

1. **`ActiveAgentManager.switchAgent(profileId)`** is called
    - Stops the current agent (`stop()`)
    - Updates the active profile in `PropertiesComponent`
    - Fires switch listeners
    - Calls `SessionSwitchService.onAgentSwitch(prevId, toId)` on a pooled thread

2. **`SessionSwitchService.doExport(fromId, toId)`** executes 2 steps:

   **Step 1 — Load current v2 session** (`loadCurrentV2Session`):
   Reads the active session from `.agent-work/sessions/<uuid>.jsonl`.
   The v2 session is kept up-to-date by the plugin on every conversation save,
   so it is always the authoritative source of truth. If empty or missing, the
   export is aborted.

   **Step 2 — Export to new client** (dispatch by profile ID):
   Converts v2 messages to the target agent's native format AND persists the resume
   identifier so the agent client knows to resume on next `session/new`.

3. **Agent client starts** and calls `AcpClient.createSession()`:
    - `buildNewSessionParams()` reads `resumeSessionId` from `GenericSettings`
    - If set, adds `"resumeSessionId": "<id>"` to the ACP `session/new` request
    - The ACP server (Copilot CLI, Kiro, etc.) loads the session by that ID

## Native Session Locations

| Agent       | Session Storage Path                                                | Format                        |
|-------------|---------------------------------------------------------------------|-------------------------------|
| Claude CLI  | `~/.claude/projects/<dash-separated-cwd>/<uuid>.jsonl`                    | Anthropic messages            |
| Kiro        | `~/.kiro/sessions/<uuid>/session.json` + `messages.jsonl`           | Anthropic messages            |
| Junie       | `~/.junie/sessions/<uuid>/session.json` + `messages.jsonl`          | Anthropic messages            |
| Copilot CLI | `<project>/.agent-work/copilot/session-state/<uuid>/events.jsonl`   | Copilot events                |
| Codex       | `~/.codex/sessions/<thread-id>/rollout.jsonl` + `~/.codex/codex.db` | Rollout JSONL + SQLite        |
| OpenCode    | `~/.local/share/opencode/opencode.db`                               | SQLite (session/message/part) |

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

| Agent      | Resume Mechanism                                            | Persisted Where                                                      |
|------------|-------------------------------------------------------------|----------------------------------------------------------------------|
| Copilot    | `--resume=<id>` CLI flag (ACP `resumeSessionId` is ignored) | `GenericSettings("copilot", project)`                                |
| Kiro       | `resumeSessionId` in ACP `session/new`                      | `GenericSettings("kiro", project)`                                   |
| Junie      | `resumeSessionId` in ACP `session/new`                      | `GenericSettings("junie", project)`                                  |
| Claude CLI | `--resume <id>` CLI flag                                    | `cliSessionIds` map (in-memory only) + `cliResumeSessionId` property |
| Codex      | `codexThreadId`                                             | `PropertiesComponent` (profile-prefixed)                             |
| OpenCode   | (no resume ID — exports directly to SQLite)                 | N/A                                                                  |

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

---

## Debugging Log: Session Resume Issues (2026-03-27)

This section documents all bugs found and fixes attempted while debugging cross-client
session resume. The issues were discovered while switching between Copilot CLI and Claude CLI.

### Bug 1: Race condition — `resumeSessionId` read before export completes

**Symptom**: Switching agents resulted in blank context. The ACP `session/new` request
included `resumeSessionId`, but it pointed to a stale or nonexistent session because
the async export hadn't finished writing the new session files yet.

**Root cause**: `SessionSwitchService.doExport()` runs on a pooled thread via
`CompletableFuture.runAsync()`. Meanwhile, `AcpClient.createSession()` on the main
flow read `resumeSessionId` from `GenericSettings` before the export had a chance to
write the new session ID.

**Fix** (commits `e16826f`, `be067e9`): Added `awaitPendingExport(timeout)` in
`ActiveAgentManager.start()` and `fetchModelsWithRetry()`. The ACP client now blocks
(up to 10s) waiting for any pending export to complete before reading `resumeSessionId`
and sending `session/new`.

### Bug 2: Copilot CLI `resumeSessionId` ignored — needs `--resume` flag

**Symptom**: Even after fixing the race condition, the Copilot CLI created a new session
instead of resuming. The `session/new` ACP request included `resumeSessionId` but the
CLI returned a different session ID.

**Root cause**: Copilot CLI does not honor the `resumeSessionId` parameter in the ACP
`session/new` request. It requires a `--resume=<id>` CLI flag passed at process startup.

**Fix** (commit `2c6a112`): `CopilotClient.buildCommand()` now checks `GenericSettings`
for a stored `resumeSessionId` and passes it as `--resume=<id>` in the CLI arguments.
The property is consumed (cleared) after reading to prevent stale resume IDs.

### Bug 3: `exportToCopilot` didn't create session folder or set resume ID

**Symptom**: The exported `events.jsonl` was written but the Copilot CLI couldn't find
it because the UUID session folder didn't exist, and the `resumeSessionId` was never
persisted for the next agent start.

**Fix** (commit `db56f7c`): `exportToCopilot()` now creates the UUID session folder
(`.agent-work/copilot/session-state/<uuid>/`) before writing `events.jsonl`, and sets
`resumeSessionId` in `GenericSettings` so `CopilotClient` can read it on next start.

### Bug 4: Wrong agent label in session picker after import

**Symptom**: After importing from another agent, the session picker showed the wrong
agent name because `sessions-index.json` wasn't updated during imports.

**Fix** (commit `be067e9`): `writeV2Session()` now calls `updateSessionAgent()` to
update the agent label in `sessions-index.json` during every import.

### Bug 5: `archive()` orphans v2 session during agent switches

**Symptom**: When switching agents twice in quick succession (A→B→C), the second switch's
`doExport` found `.current-session-id` deleted and created a new empty v2 session —
losing all imported conversation history.

**Root cause**: `buildAndShowChatPanel()` calls `archive()` on first connection, which
deleted `.current-session-id`. Two separate `SessionStoreV2` instances
(`ChatToolWindowContent.conversationStore` and `SessionSwitchService.sessionStore`)
competed for the same `.current-session-id` file with no synchronization.

**Timeline of the race**:
1. `switchAgent(A→B)` → spawns `doExport` → completes, writes session
2. `fetchModelsWithRetry()` → awaits export (done) → starts agent B
3. `buildAndShowChatPanel()` (first connection) → `archive()` →
   `Files.deleteIfExists(.current-session-id)` — **UUID deleted!**
4. Next `getCurrentSessionId()` call finds file missing → creates new empty UUID
5. `switchAgent(B→C)` → `doExport` loads new empty UUID → exports nothing

**Fix** (commit `490ca3e`): `archive()` no longer deletes `.current-session-id`.
A new `resetCurrentSessionId()` method handles deletion and is only called from
`resetSession()` ("New Conversation" button), not during normal agent switches.

### Bug 6: Claude CLI project path mismatch

**Symptom**: Sessions exported for Claude CLI were written to the wrong directory.
Claude CLI couldn't find the exported session file when resuming.

**Root cause**: The plugin used `SHA-1(projectPath)` as the directory name under
`~/.claude/projects/`, but Claude CLI uses the project path with slashes replaced
by dashes (e.g., `/home/user/proj` → `-home-user-proj`).

**Fix** (commit `490ca3e`): `claudeProjectDir()` now uses the dash-separated path
format matching Claude CLI's actual convention.

### Still Broken After All Fixes (as of 2026-03-27 ~13:40)

After building and deploying the plugin with all six fixes above, both resume paths
still fail:

#### Copilot Resume: Still Blank Context

**Test**: Switched from Claude → Copilot with "Resume session" selected.

**Observed behavior**: Copilot CLI started, received the `--resume=<id>` flag AND
`resumeSessionId` in `session/new`, but created a brand new session (different UUID
returned). The agent had no context from the previous conversation.

**Investigation findings**:
- The exported `events.jsonl` existed and contained 83 events
- However, the events had **0 `user.message` events** — only `assistant.message`,
  `tool.execution_complete`, `assistant.turn_end`, and `session.start` events
- The `CopilotClientExporter.writeUserMessage()` silently drops user messages where
  `extractText()` returns empty string (no `"text"` parts or empty text)
- The v2 session DID have user messages with text parts (verified: 10 user messages
  with proper text content in the current v2 session)
- **Suspected cause**: The v2 session at export time may have had different content
  than the current v2 session (import-overwrite logic, or the export read a stale v2
  file). The exact cause of 0 user messages in the export is still undiagnosed.
- Additionally: Even if `events.jsonl` were correct, it's unclear whether the Copilot
  CLI actually supports `--resume=<id>` for loading exported sessions vs. only its own
  natively-created sessions. The CLI may only resume sessions it created itself.

#### Claude Resume: Completely Broken

**Test**: Switched from Copilot → Claude with "Resume session" selected.

**Observed behavior**: Claude CLI started but failed to respond to the user's prompt.
The turn ended early with no output.

**Investigation findings**:
- The `~/.claude/projects/` directory was **completely empty** — no project directories
  exist at all (not the old SHA1-based path, not the new dash-separated path)
- This means either: (a) `AnthropicClientExporter` failed silently and never wrote the
  file, (b) the directory was cleaned up by Claude CLI or another process, or (c) the
  export path computation still doesn't match what Claude CLI expects
- The fix in commit `490ca3e` changed from SHA1 to dash-separated paths, but this
  hasn't been verified to work because the directory is empty
- Claude CLI may have started without any resume data and then crashed or hung when
  trying to process the session, possibly due to receiving a `--resume <id>` flag
  pointing to a nonexistent session file

### Open Questions for Next Debugging Session

1. **Why does `CopilotClientExporter` produce 0 user messages?**
   - Check what the v2 session contained at the exact moment of export (not after)
   - Add logging to `writeUserMessage()` to trace which messages are skipped and why
   - Verify that `extractText()` correctly handles the parts structure from
     `AnthropicClientImporter` (the source of the v2 data)

2. **Does Copilot CLI actually support `--resume` for externally-created sessions?**
   - The CLI may only resume sessions it created itself (where it controls the
     `events.jsonl` format and internal state)
   - Test by creating a session in Copilot, stopping it, then resuming with `--resume`
     to verify the mechanism works at all

3. **Why is `~/.claude/projects/` empty?**
   - Add debug logging to `AnthropicClientExporter` to confirm the exact path written
   - Check file permissions and whether `mkdirs()` succeeded
   - Verify the dash-separated path format matches Claude CLI's actual convention
     (inspect a real Claude CLI session to see what path it uses)

4. **Does Claude CLI crash on malformed session data?**
   - If the exported JSONL is malformed (wrong message structure, missing required
     fields), Claude CLI may crash silently instead of starting a fresh session
   - Validate the exported JSONL against Claude's expected format

5. **Is the `resumeSessionId` in `session/new` actually used by any agent?**
   - Copilot CLI ignores it (needs `--resume` flag instead)
   - Claude CLI uses `--resume <id>` flag
   - Only Kiro/Junie may actually use the ACP `resumeSessionId` parameter
   - Consider whether the ACP parameter should be removed to avoid confusion

### Current Situation (2026-03-27 ~14:00)

**Branch**: `feat/codex`, ~30 commits ahead of origin.

**What was done**: Six bugs identified and fixed across commits `db56f7c` through `490ca3e`.
All fixes are committed and the plugin was rebuilt and restarted twice for testing.

**What still doesn't work**:

| Path                | Status | Notes                                                          |
|---------------------|--------|----------------------------------------------------------------|
| Copilot → Claude    | ❌      | Claude fails to respond; `~/.claude/projects/` is empty        |
| Claude → Copilot    | ❌      | Copilot starts fresh session; exported events have 0 user msgs |
| Copilot → Copilot   | ❌      | Same-agent resume after IDE restart also gives blank context    |

**Last test (14:00)**: User switched from Claude → Copilot with "Resume session".
Copilot started a brand new session with no context. Agent confirmed blank context.

**Next steps**: See "Open Questions" above. The most promising investigation paths are:
1. Add logging to `CopilotClientExporter.writeUserMessage()` to understand why 0 user
   messages are exported despite the v2 session having user messages with text content
2. Verify `~/.claude/projects/` path convention by inspecting a real Claude CLI session
   (run `claude` manually from terminal and check what directory it creates)
3. Test whether Copilot CLI's `--resume` flag works at all for externally-created sessions
   (try manually creating an `events.jsonl` and resuming it)

### Bug 7: Copilot CLI requires `workspace.yaml` for resume (2026-03-27 ~14:30)

**Symptom**: Copilot CLI received `--resume=6219334c` correctly, but created a new
session (`f8fba59d`) instead of resuming. The exported session had only `events.jsonl`.

**Root cause**: A real Copilot session directory contains `workspace.yaml` (session
metadata), `session.db`, and subdirectories (`checkpoints/`, `files/`, `research/`).
The CLI silently ignores `--resume` when `workspace.yaml` is missing.

**Fix**: `exportToCopilot()` now creates `workspace.yaml` (with session ID, cwd,
git_root, branch, timestamps) and the three required subdirectories.

### Bug 8: Claude CLI exported JSONL has consecutive user messages (2026-03-27 ~14:30)

**Symptom**: Claude CLI loaded the exported session via `--resume` but "failed to
respond" on the first prompt.

**Root cause**: `AnthropicClientExporter.toAnthropicMessages()` creates a user message
with `tool_result` blocks after each assistant turn (to carry tool results). When the
next v2 message is also a user message (text), the result is two consecutive user
messages. Claude's API requires strict user/assistant alternation — consecutive
same-role messages cause API errors or silent failures.

**Fix**: Added `mergeConsecutiveSameRole()` post-processing step that combines
consecutive messages with the same role into a single message with merged content
blocks. This ensures the exported JSONL always has proper alternation.

### Bug 9: Copilot events.jsonl missing required event metadata (2026-03-27 ~14:50)

**Symptom**: Copilot CLI received `--resume=<id>` with a valid session directory
(including `workspace.yaml`), but still created a new session.

**Root cause**: Real Copilot CLI events have `id`, `timestamp`, and `parentId` fields
at the top level of every event, plus rich `data` fields (`sessionId`, `version`,
`producer`, `context`, `interactionId`, `messageId`, `turnId`). The exported events
only had `type` and `data` — the CLI silently rejected them due to missing metadata.

**Fix**: `CopilotClientExporter` now generates a proper event chain with UUIDs for
`id`, ISO timestamps, `parentId` references forming a sequential chain, and all
required `data` fields per event type. Added `assistant.turn_start` events before
each assistant turn.

### Bug 10: Claude CLI export used wrong JSONL format entirely (2026-03-27 ~14:50)

**Symptom**: Claude CLI loaded the exported session via `--resume` but "failed to
respond" on the first prompt.

**Root cause**: `AnthropicClientExporter` writes bare Anthropic API messages
(`{"role":"user","content":[...]}`), but Claude CLI's session JSONL uses a completely
different event format with envelope fields: `type` (`"user"`/`"assistant"`),
`uuid`, `parentUuid`, `sessionId`, `timestamp`, `cwd`, and a nested `message` field
containing the Anthropic payload. The bare API format was being rejected by the CLI.

**Fix**: Created `ClaudeCliExporter` that wraps Anthropic messages in Claude CLI event
envelopes (`queue-operation` events, user/assistant events with proper metadata).
`AnthropicClientExporter` is preserved for Kiro/Junie which DO use the bare Anthropic
format.

