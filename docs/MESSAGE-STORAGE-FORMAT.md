# Message Storage Format

Internal reference for how chat messages are stored, serialized, and restored.

---

## Entry Types (`EntryData` sealed class)

The in-memory model uses `EntryData` (defined in `ChatDataModel.kt`) with 8 subtypes:

### `Prompt` — user message
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `text` | `String` | *(required)* | No | ✅ | ✅ (`text`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (`timestamp`) |
| `contextFiles` | `List<Triple<name,path,line>>?` | `null` | No | ✅ (`ctxFiles`) | ✅ (`contextFiles` array) |
| `id` | `String` | `""` | No | ✅ (`id`) | ✅ (`id`) |
| `entryId` | `String` | UUID or `id` | No | ✅ (`eid`) | ✅ (`entryId`) |

### `Text` — assistant text response
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `raw` | `StringBuilder` | `StringBuilder()` | Content appended during streaming | ✅ (`raw`) | ✅ (`raw`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (`timestamp`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (`agent`) |
| `model` | `String` | `""` | No | ❌ | ✅ (`model`) |
| `entryId` | `String` | UUID | No | ✅ (`eid`) | ✅ (`entryId`) |

### `Thinking` — reasoning/thinking block
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `raw` | `StringBuilder` | `StringBuilder()` | Content appended during streaming | ✅ (`raw`) | ✅ (`raw`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (`timestamp`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (`agent`) |
| `model` | `String` | `""` | No | ❌ | ✅ (`model`) |
| `entryId` | `String` | UUID | No | ✅ (`eid`) | ✅ (`entryId`) |

### `ToolCall` — tool invocation and result
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `title` | `String` | *(required)* | No | ✅ (`title`) | ✅ (`title`) |
| `arguments` | `String?` | `null` | No | ✅ (`args`) | ✅ (`arguments`) |
| `kind` | `String` | `"other"` | Yes | ✅ (`kind`) | ✅ (`kind`) |
| `result` | `String?` | `null` | Yes | ✅ (`result`) | ✅ (`result`) |
| `status` | `String?` | `null` | Yes | ✅ (`status`) | ✅ (`status`) |
| `description` | `String?` | `null` | Yes | ✅ (`description`) | ✅ (`description`) |
| `filePath` | `String?` | `null` | Yes | ✅ (`filePath`) | ✅ (`filePath`) |
| `autoDenied` | `Boolean` | `false` | Yes | ✅ (`autoDenied`) | ✅ (`autoDenied`) |
| `denialReason` | `String?` | `null` | Yes | ✅ (`denialReason`) | ✅ (`denialReason`) |
| `mcpHandled` | `Boolean` | `false` | Yes | ✅ (`mcpHandled`) | ✅ (`mcpHandled`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (`timestamp`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (`agent`) |
| `model` | `String` | `""` | No | ❌ | ✅ (`model`) |
| `entryId` | `String` | UUID | No | ✅ (`eid`) | ✅ (`entryId`) |

### `SubAgent` — sub-agent invocation
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `agentType` | `String` | *(required)* | No | ✅ | ✅ (`agentType`) |
| `description` | `String` | *(required)* | No | ✅ | ✅ (`description`) |
| `prompt` | `String?` | `null` | No | ✅ | ✅ (`prompt`) |
| `result` | `String?` | `null` | Yes | ✅ | ✅ (`result`) |
| `status` | `String?` | `null` | Yes | ✅ | ✅ (`status`) |
| `colorIndex` | `Int` | `0` | Yes | ✅ | ✅ (`colorIndex`) |
| `callId` | `String?` | `null` | No | ✅ (`callId`) | ✅ (`callId`) |
| `autoDenied` | `Boolean` | `false` | Yes | ✅ | ✅ (`autoDenied`) |
| `denialReason` | `String?` | `null` | Yes | ✅ | ✅ (`denialReason`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (`timestamp`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (`agent`) |
| `model` | `String` | `""` | No | ❌ | ✅ (`model`) |
| `entryId` | `String` | UUID | No | ✅ (`eid`) | ✅ (`entryId`) |

### `ContextFiles` — attached file references (transient)
| Field | Type | Persisted V1 | Persisted V2 |
|-------|------|:------------:|:------------:|
| `files` | `List<Pair<name, path>>` | ✅ (`context`) | ✅ (`files` array) |
| `entryId` | `String` | ✅ (`eid`) | ✅ (`entryId`) |

### `Status` — status indicator (transient)
| Field | Type | Persisted V1 | Persisted V2 |
|-------|------|:------------:|:------------:|
| `icon` | `String` | ✅ | ✅ (`icon`) |
| `message` | `String` | ✅ | ✅ (`message`) |
| `entryId` | `String` | ✅ (`eid`) | ✅ (`entryId`) |

### `SessionSeparator` — session boundary marker
| Field | Type | Persisted V1 | Persisted V2 |
|-------|------|:------------:|:------------:|
| `timestamp` | `String` | ✅ | ✅ (`timestamp`) |
| `agent` | `String` | ✅ | ✅ (`agent`) |
| `entryId` | `String` | ✅ (`eid`) | ✅ (`entryId`) |

### `TurnStats` — per-turn statistics (metadata only)

Metadata-only entry appended after each completed turn. Not rendered in the chat UI.
Used to restore session-level aggregates (elapsed time, tokens, lines changed) on session resume.

| Field | Type | Default | Description |
|---|---|---|---|
| `type` | `"turnStats"` | — | Type discriminator |
| `turnId` | string | — | Matches the Prompt ID (e.g., `"t0"`, `"t1"`) |
| `durationMs` | long | 0 | Elapsed time for this turn (ms) |
| `inputTokens` | long | 0 | Input tokens consumed this turn |
| `outputTokens` | long | 0 | Output tokens consumed this turn |
| `costUsd` | double | 0.0 | Cost in USD for this turn |
| `toolCallCount` | int | 0 | Number of tool calls this turn |
| `linesAdded` | int | 0 | Lines of code added this turn |
| `linesRemoved` | int | 0 | Lines of code removed this turn |
| `model` | string | `""` | Model ID (e.g., `"claude-opus-4.6"`) |
| `multiplier` | string | `""` | Billing multiplier (e.g., `"1x"`, `"5x"`) |
| `totalDurationMs` | long | 0 | Running aggregate: total elapsed time |
| `totalInputTokens` | long | 0 | Running aggregate: total input tokens |
| `totalOutputTokens` | long | 0 | Running aggregate: total output tokens |
| `totalCostUsd` | double | 0.0 | Running aggregate: total cost |
| `totalToolCalls` | int | 0 | Running aggregate: total tool calls |
| `totalLinesAdded` | int | 0 | Running aggregate: total lines added |
| `totalLinesRemoved` | int | 0 | Running aggregate: total lines removed |
| `entryId` | string | UUID | Unique entry identifier |

Example:
```json
{"type":"turnStats","turnId":"t0","durationMs":45230,"inputTokens":1200,"outputTokens":3500,"costUsd":0.015,"toolCallCount":8,"linesAdded":42,"linesRemoved":7,"model":"claude-opus-4.6","multiplier":"5x","totalDurationMs":45230,"totalInputTokens":1200,"totalOutputTokens":3500,"totalCostUsd":0.015,"totalToolCalls":8,"totalLinesAdded":42,"totalLinesRemoved":7,"entryId":"abc-123"}
```

Zero/default fields are omitted in the compact serialization.

---

## Persistence Formats

### V1 — JSON Array (`ConversationSerializer`) ⚠️ Deprecated

**File:** `<projectBase>/.agent-work/conversation.json`

> **Deprecated.** V1 is no longer written during normal save operations.
> The save path now goes directly from `EntryData → V2 JSONL` via `SessionStoreV2.saveEntries()`.
> V1 format is kept only for `V1ToV2Migrator` backward compatibility.

A flat JSON array where each element is one `EntryData`. Type discriminator: `"type"` field.

```json
[
  {"type":"prompt","text":"Hello","ts":"2026-04-01T10:00:00Z","id":"p1"},
  {"type":"text","raw":"Response","ts":"2026-04-01T10:00:01Z","agent":"copilot"},
  {"type":"tool","title":"read_file","args":"{}","kind":"read","result":"content",
   "status":"completed","ts":"2026-04-01T10:00:02Z","agent":"copilot"},
  {"type":"subagent","agentType":"explore","description":"Find code",
   "prompt":"search","result":"found","status":"completed","colorIndex":0,
   "callId":"call-1","ts":"2026-04-01T10:00:03Z","agent":"copilot"},
  {"type":"separator","timestamp":"2026-04-01T11:00:00Z","agent":"copilot"}
]
```

**Type values:** `prompt`, `text`, `thinking`, `tool`, `subagent`, `context`, `status`, `separator`

### V2 — JSONL (`SessionStoreV2` + `EntryDataJsonAdapter`)

**Directory:** `<projectBase>/.agent-work/sessions/`
- `sessions-index.json` — array of session metadata
- `<uuid>.jsonl` — one `EntryData` per line (1:1 mapping)

#### `sessions-index.json` fields

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | UUID | Unique session identifier |
| `createdAt` | long | — | Session creation timestamp (epoch ms) |
| `turns` | int | 0 | Number of prompt turns in the session |
| `name` | string | `""` | Auto-generated session name (first prompt text, max 60 chars) |

Session dropdown display format: `"2026-04-05 — Fix the auth bug (12 turns)"`

Each line is a single `EntryData` entry serialized directly via `EntryDataJsonAdapter`.
The `"type"` discriminator determines the entry subtype. Field names match Kotlin property names.
Null/empty/false/zero-default fields are omitted to keep JSON compact.

```json
{"type":"prompt","text":"Hello","timestamp":"2026-04-01T10:00:00Z","contextFiles":[{"name":"Main.java","path":"/src/Main.java","line":42}],"id":"p1","entryId":"eid-1"}
{"type":"text","raw":"Response","timestamp":"2026-04-01T10:00:01Z","agent":"copilot","model":"claude-sonnet-4-6","entryId":"eid-2"}
{"type":"thinking","raw":"Let me think...","timestamp":"2026-04-01T10:00:01Z","agent":"copilot","model":"claude-sonnet-4-6","entryId":"eid-3"}
{"type":"tool","title":"read_file","arguments":"{}","kind":"read","result":"content","status":"completed","description":"Read a file","filePath":"/src/Main.java","mcpHandled":true,"timestamp":"2026-04-01T10:00:02Z","agent":"copilot","model":"claude-sonnet-4-6","entryId":"eid-4"}
{"type":"subagent","agentType":"explore","description":"Find code","prompt":"search","result":"found","status":"completed","colorIndex":2,"callId":"call-1","timestamp":"2026-04-01T10:00:03Z","agent":"copilot","model":"gpt-4o","entryId":"eid-5"}
{"type":"context","files":[{"name":"A.java","path":"/src/A.java"}],"entryId":"eid-6"}
{"type":"status","icon":"ℹ","message":"Processing...","entryId":"eid-7"}
{"type":"separator","timestamp":"2026-04-01T11:00:00Z","agent":"copilot","entryId":"eid-8"}
{"type":"turnStats","turnId":"t0","durationMs":45230,"inputTokens":1200,"outputTokens":3500,"costUsd":0.015,"toolCallCount":3,"linesAdded":42,"linesRemoved":7,"model":"claude-sonnet-4-6","totalDurationMs":45230,"totalInputTokens":1200,"totalOutputTokens":3500,"totalCostUsd":0.015,"totalToolCalls":3,"totalLinesAdded":42,"totalLinesRemoved":7,"entryId":"eid-9"}
```

**Type values:** `prompt`, `text`, `thinking`, `tool`, `subagent`, `context`, `status`, `separator`, `turnStats`

#### Backward Compatibility (Legacy SessionMessage JSONL)

Files written before Phase 4 use the older `SessionMessage` format where each line has
`"role"` instead of `"type"`. On load, format is auto-detected per line:
- Line contains `"type":` → new EntryData format → `EntryDataJsonAdapter.deserialize()`
- Line contains `"role":` → legacy SessionMessage format → `SessionStoreV2.convertLegacyMessages()`

Legacy format (for reference — no longer written):
```json
{"id":"m1","role":"user","parts":[{"type":"text","text":"Hello","ts":"...","eid":"..."}],"createdAt":1743505200000,"agent":null,"model":null}
{"id":"m2","role":"assistant","parts":[{"type":"text","text":"Reply","ts":"...","eid":"..."}],"createdAt":1743505201000,"agent":"copilot","model":"claude-sonnet-4-6"}
```

---

## JS Event Types (SSE / Event Log)

Events pushed to JCEF and SSE clients via `ChatWebServer.pushJsEvent()`:

| Event | Description |
|-------|-------------|
| `addUserMessage(text, ts, bubbleHtml, turnId)` | New user message |
| `showWorkingIndicator()` | Show typing indicator |
| `appendAgentText(turnId, agentId, text, ts)` | Streaming text chunk |
| `finalizeAgentText(turnId, agentId, html)` | Finalize streamed text → rendered HTML |
| `addThinkingText(turnId, agentId, text)` | Streaming thinking chunk |
| `collapseThinking(turnId, agentId, html)` | Finalize thinking → collapsed chip |
| `addToolCall(turnId, agentId, domId, label, params, kind, isExternal)` | New tool call |
| `updateToolCall(domId, status, ...)` | Tool call status update |
| `addSubAgent(turnId, agentId, domId, displayName, colorIndex, promptHtml, ts)` | New sub-agent |
| `updateSubAgent(domId, status, resultHtml)` | Sub-agent completion |
| `addSubAgentToolCall(saDomId, toolDomId, label, params, kind, isExternal)` | Sub-agent internal tool call |
| `addSessionSeparator(ts, agent)` | Session boundary |
| `finalizeTurn(turnId, statsJson)` | Turn complete |
| `restoreBatch(base64Html)` | Full conversation restore (pre-rendered HTML) |
| `clear()` | Clear conversation (also clears event log) |

### Event Log Compaction

The event log size is configurable via **Settings → AgentBridge → Chat History → History Limits → Web event log size** (default: 600).
Streaming events are compacted when their finalizer arrives:
- `appendAgentText` events → removed when `finalizeAgentText` arrives for the same turn+agent
- `addThinkingText` events → removed when `collapseThinking` arrives for the same turn+agent

---

## Sub-agent Internal Tool Calls

Sub-agent internal tool calls (tools invoked *by* a sub-agent during its execution) are:
1. Added to the `entries` list with proper timestamp and agent
2. Registered in `toolCallEntries` runtime map for DOM click-to-expand panels
3. Serialized to both V1 and V2 formats as regular `ToolCall` entries

On batch restore, sub-agent tool calls are reconstructed from the persisted entries and
displayed as tool chips within the sub-agent's section.

---

## Configurable History Limits

All history limits are configurable via **Settings → AgentBridge → Chat History → History Limits**.
Persisted in `chatHistory.xml` per project via `ChatHistorySettings`.

| Setting | Default | Description |
|---|---|---|
| Web event log size | 600 | Max JS events in the in-memory FIFO buffer (SSE reconnect / PWA `/state`) |
| DOM message limit | 80 | Max `<chat-message>` elements visible in the DOM before older ones are trimmed |
| Recent turns on restore | 5 | Number of recent prompt turns loaded immediately when restoring a session |
| Load-more batch size | 3 | Number of prompt turns loaded per "Load More" click |

The event log size and DOM message limit are also sent to web/PWA clients:
- Event log size: controls server-side FIFO cap in `ChatWebServer.pushJsEvent()`
- DOM message limit: sent via `/state` response (`domMessageLimit` field) and via `ChatController.setDomMessageLimit()` JS call

---

## Session Restore

When a session is restored, the last `TurnStats` entry's aggregate fields are used to
initialize `ProcessingTimerPanel`'s session-level counters (elapsed time, tokens, cost,
tool calls, lines changed, turn count). This means session statistics survive IDE restarts.

---

## Data Flow

### Save Path (current)

```
EntryData (in memory)
  → EntryDataJsonAdapter.serialize() per entry
  → GSON.toJson() per JsonObject
  → UUID.jsonl (V2 JSONL on disk, one entry per line)
```

`ChatToolWindowContent.saveConversation()` → `SessionStoreV2.saveEntriesAsync()`.
No `SessionMessage` intermediary. No V1 intermediary. Direct 1:1 serialization.

### Load Path (current)

```
UUID.jsonl (V2 JSONL on disk)
  → auto-detect per line ("type:" = new, "role:" = legacy)
  → EntryDataJsonAdapter.deserialize() per line (new format)
    OR SessionStoreV2.convertLegacyMessages() (legacy format)
  → List<EntryData> (in memory)
  → ConversationReplayer.loadAndSplit()
  → recent / deferred split
```

`ChatToolWindowContent.restoreConversation()` → `SessionStoreV2.loadEntries()`.
Falls back to V1 `conversation.json` if V2 JSONL is absent (legacy installs).

### Export Path (external formats)

```
EntryData (in memory)
  → CopilotClientExporter / OpenCodeClientExporter / etc.
  → client-specific JSONL / SQLite
```

Each exporter works directly with `List<EntryData>` — no intermediate format.

### V1→V2 Migration (one-shot)

On first load after upgrade, `V1ToV2Migrator.migrateIfNeeded()` reads V1 JSON and
archives, converts them to V2 JSONL, writes V2 files, and creates `sessions-index.json`.
Subsequent loads use V2 directly.
