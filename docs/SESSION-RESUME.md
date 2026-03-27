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
| Claude CLI  | `~/.claude/projects/<dash-separated-cwd>/<uuid>.jsonl`              | Anthropic messages            |
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

| Path              | Status | Notes                                                          |
|-------------------|--------|----------------------------------------------------------------|
| Copilot → Claude  | ❌      | Claude fails to respond; `~/.claude/projects/` is empty        |
| Claude → Copilot  | ❌      | Copilot starts fresh session; exported events have 0 user msgs |
| Copilot → Copilot | ❌      | Same-agent resume after IDE restart also gives blank context   |

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

### Bug 11: Stale `resumeSessionId` reused on subsequent Copilot launches (commit `f181688`)

**Symptom**: After a successful resume, subsequent Copilot launches would still pass the
old `--resume=<id>` flag, attempting to resume an already-resumed (consumed) session.

**Root cause**: `CopilotClient.buildCommand()` read `resumeSessionId` from
`GenericSettings` but never cleared it. Every subsequent launch kept appending
`--resume` with the same stale ID.

**Fix**: `buildCommand()` now clears the stored `resumeSessionId` immediately after
reading it, ensuring the flag is consumed exactly once per intended resume.

---

### ✅ Copilot → Claude Resume: Confirmed Working (2026-03-27)

After all ten bugs above were fixed (commits `db56f7c` through `f181688`), the
**Copilot → Claude** resume path was confirmed working end-to-end:

- Switched from Copilot CLI to Claude CLI with "Resume session" selected
- Claude CLI started with `--resume <id>` pointing to the exported session
- Claude responded to the first prompt with full context from the previous Copilot
  conversation intact
- Verified: the agent (Claude) confirmed awareness of prior session content without
  any re-prompting

**What made it work** (the two critical fixes):

1. `ClaudeCliExporter` (Bug 10) — correct envelope format with `uuid`, `parentUuid`,
   `sessionId`, `cwd`, and proper `queue-operation` events
2. `mergeConsecutiveSameRole()` (Bug 8) — prevents consecutive user messages that
   cause Claude API errors

| Path              | Status | Notes                              |
|-------------------|--------|------------------------------------|
| Copilot → Claude  | ✅      | Confirmed working as of 2026-03-27 |
| Claude → Copilot  | ✅      | Confirmed working as of 2026-03-27 |
| Copilot → Copilot | ❓      | Not yet tested after Bug 9 fix     |

### ✅ Claude → Copilot Resume: Confirmed Working (2026-03-27)

Immediately after the Copilot → Claude test above, the reverse path was tested:

- Switched from Claude CLI back to Copilot CLI with "Resume session" selected
- Copilot CLI started with `--resume <id>` pointing to the exported session
- Copilot (powered by Claude Opus 4.6) responded with full awareness of the
  previous conversation — correctly recalling Bug 9, Bug 10, Bug 11, the two
  final commits (`b202e0fe`, `f1816888`), and the SESSION-RESUME.md update
- No re-prompting was needed; the agent had complete context from the Claude session

This confirms **bidirectional resume** between Copilot and Claude is fully working.

---

## OpenCode Resume: Bug Investigation (2026-03-27)

Testing revealed that **OpenCode resume was completely broken** — both switching TO and FROM
OpenCode failed silently. Investigation uncovered 7 new bugs (12-18).

### Bug 12: OpenCode exporter schema mismatch (CRITICAL)

**Symptom**: Switching TO OpenCode shows a fresh conversation — no history carried over.

**Root cause**: `OpenCodeClientExporter.ensureTables()` created a simplified schema:

```sql
CREATE TABLE session
(
    id,
    directory,
    title,
    time_created,
    time_updated
)
```

But OpenCode's actual schema (managed by drizzle migrations) has many more required columns:

```sql
CREATE TABLE session
(
    id,
    project_id
    NOT
    NULL,
    slug
    NOT
    NULL,
    directory
    NOT
    NULL,
    title
    NOT
    NULL,
    version
    NOT
    NULL,
    time_created
    NOT
    NULL,
    time_updated
    NOT
    NULL,
    .
    .
    .
)
```

When the database already existed (normal case), our INSERTs failed with NOT NULL constraint
violations on `project_id`, `slug`, and `version`. The `message` table also required
`time_updated`, and `part` required `session_id` and `time_updated`.

**Fix**: Rewrote `OpenCodeClientExporter` to match OpenCode 1.2.x schema:

- Finds or creates a `project` record using SHA-1(worktree) as the ID
- Creates sessions with all required fields (`project_id`, `slug`, `version`)
- Includes `time_updated` in message and part records
- Includes `session_id` in part records

### Bug 13: OpenCode export didn't set `resumeSessionId`

**Symptom**: Even if SQLite export succeeded, OpenCode started a fresh ACP session.

**Root cause**: `SessionSwitchService.exportToOpenCode()` wrote to SQLite but never called
`GenericSettings.setResumeSessionId()`. When the ACP client sent `session/new`, it had no
`resumeSessionId`, so OpenCode created a fresh session.

**Fix**: Added `GenericSettings(OPENCODE_PROFILE_ID, project).setResumeSessionId(sessionId)`
after successful SQLite export.

### Bug 14: OpenCode ID format mismatch

**Symptom**: Exported sessions had plain UUIDs, but OpenCode uses prefixed IDs.

**Root cause**: The exporter generated UUIDs like `a1b2c3d4-...` but OpenCode uses
`ses_XXXX` for sessions, `msg_XXXX` for messages, and `prt_XXXX` for parts.

**Fix**: Added `generateId(prefix)` that produces OpenCode-style IDs (e.g., `ses_abc123...`).

### Bug 15: OpenCode message data too sparse

**Symptom**: Even if resume worked, OpenCode might reject messages with incomplete data.

**Root cause**: The exporter wrote `{"role":"user"}` for message data, but OpenCode expects
richer JSON: `{"role":"user","time":{"created":...},"summary":{"diffs":[]},...}`.

**Fix**: `buildMessageData()` now writes `role`, `time.created`, `time.completed` (for
assistant), and model info matching OpenCode's expected format.

### Bug 16: OpenCode tool part format different

**Symptom**: Tool call history lost during round-trip conversion.

**Root cause**: V2 stores tool calls as `{"type":"tool-invocation","toolInvocation":{...}}`
but OpenCode uses `{"type":"tool","callID":"...","tool":"...","state":{...}}`.

**Fix**: `convertV2PartToOpenCodePart()` in the exporter now converts to OpenCode's native
format. `convertOpenCodePartToV2()` in the importer does the reverse, including handling
of `step-start`/`step-finish` ephemeral parts.

### Bug 17: OpenCode importer model extraction wrong

**Symptom**: Model information lost on import from OpenCode.

**Root cause**: `extractModel()` looked for `metadata.assistant.modelID` but OpenCode stores
model as top-level `modelID` (assistant messages) or `model.modelID` (user messages).

**Fix**: Updated `extractModel()` to check all three locations with proper fallback order.

### Bug 18: OpenCode project table not handled

**Symptom**: Session FK constraint violation when inserting into existing database.

**Root cause**: OpenCode sessions reference a `project` table via `project_id` (SHA-1 hex
of worktree path). Our exporter didn't create or reference project records.

**Fix**: `findOrCreateProject()` computes SHA-1 of the worktree path and creates a project
record if one doesn't exist, matching OpenCode's native format.

### Bug 19: SQLite JDBC driver not found at runtime (CRITICAL)

**Symptom**: Switching Copilot → OpenCode showed "no context" — the exported session was
never written to the OpenCode database.

**Root cause**: `java.sql.DriverManager` discovers drivers via the system classloader, but
IntelliJ plugins load under a custom classloader that `DriverManager` doesn't search. The
`org.xerial:sqlite-jdbc` dependency is on the classpath, but the driver class is never
registered with `DriverManager`. Tests pass because the test classloader makes the driver
visible.

**Fix**: Added `OpenCodeClientExporter.openSqlite(Path)` helper that calls
`Class.forName("org.sqlite.JDBC")` before `DriverManager.getConnection()`, and also applies
`PRAGMA journal_mode=WAL`. Both the exporter and importer now use this shared method,
eliminating duplicated connection boilerplate.

### Bug 20: Wrong CWD in exported Copilot session.start event

**Symptom**: Switching OpenCode → Copilot launched Copilot with `--resume=<id>` (correct)
but Copilot showed "no context".

**Root cause**: `CopilotClientExporter.sessionStartData()` used
`System.getProperty("user.dir")` for the CWD, which returns the JVM launch directory
(`/home/user`) rather than the project directory. Native Copilot sessions have the project
directory as CWD. The mismatch may cause Copilot CLI to silently reject the session.

**Fix**: `exportToFile()` now accepts a `basePath` parameter which is threaded to
`sessionStartData()` for the `cwd` and `gitRoot` fields. `SessionSwitchService.exportToCopilot()`
passes the project's base path.

### Bug 21: `importFromPreviousClient` is dead code

**Symptom**: None directly — discovered during investigation.

**Root cause**: `SessionSwitchService.importFromPreviousClient()` has zero callers. It was
designed to import from a native client format into v2 before exporting, but was never wired
into the `doExport()` flow. The v2 session is maintained during all conversations via
`saveConversation()`, so this is not needed for normal operation.

**Status**: Documented. Not causing failures. May be useful if importing sessions from
clients that ran outside the plugin.

### Current Status (2026-03-27)

| Route                   | Status | Notes                                    |
|-------------------------|--------|------------------------------------------|
| Copilot → Universal v2  | ✅      | CopilotClientImporter confirmed working  |
| Claude → Universal v2   | ✅      | ClaudeClientImporter confirmed working   |
| Codex → Universal v2    | ❓      | CodexClientImporter implemented, untested |
| OpenCode → Universal v2 | 🔧     | Bugs 12-18 fixed, driver fix (Bug 19) applied |
| Junie → Universal v2    | ❓      | AnthropicClientImporter, untested        |
| Kiro → Universal v2     | ❓      | AnthropicClientImporter, untested        |
| Universal v2 → Copilot  | 🔧     | CWD fix (Bug 20) applied, awaiting retest |
| Universal v2 → Claude   | ✅      | Confirmed working                        |
| Universal v2 → Codex    | ❓      | CodexClientExporter implemented, untested |
| Universal v2 → OpenCode | 🔧     | Bugs 12-19 fixed, driver fix applied     |
| Universal v2 → Junie    | ❓      | AnthropicClientExporter, untested        |
| Universal v2 → Kiro     | ❓      | AnthropicClientExporter, untested        |

### Bug 22: `buildCommand()` clear-after-read loses resume ID (2026-03-27)

**Symptom**: Copilot can't restore its own sessions after IDE restart. The CLI receives
`--resume=<id>` pointing to an export-created session directory that has `workspace.yaml`
but no `events.jsonl`, so it creates a fresh session instead.

**Root cause**: `CopilotClient.buildCommand()` read the `resumeSessionId` from
`GenericSettings` and then **immediately cleared it** (`settings.setResumeSessionId(null)`).
The clear was meant to be "one-shot" (Bug 11 fix), but it created a fragile window:

1. `buildCommand()` reads and clears → resume ID is now null in memory
2. CLI process launch, transport, authentication, `session/new` — any failure here…
3. `persistResumeSessionId(newId)` — only runs if `createSession()` succeeds

If anything between steps 1 and 3 fails, the resume ID is **permanently lost**. On the
next attempt, `buildCommand()` reads null → no `--resume` flag → fresh session.

Additionally, if the IDE is killed (crash, deploy restart) before the in-memory null is
flushed to `PropertiesComponent` (backed by `.idea/workspace.xml`), the on-disk value may
revert to a stale export-created session ID (from `exportToCopilot()`). That stale ID
points to an exported session directory that may lack `events.jsonl`, causing the CLI to
silently create a fresh session.

**Fix**: Removed `settings.setResumeSessionId(null)` from `buildCommand()`. The explicit
clear is redundant because `persistResumeSessionId(newId)` in `createSession()` naturally
overwrites the old value. If `createSession()` fails, the old ID is preserved for retry.

