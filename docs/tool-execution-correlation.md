# Tool Call Tracking Architecture

## Overview

Tool calls in the plugin flow through **two independent channels** that arrive in any order:

| Channel | Source | Path |
|---------|--------|------|
| **ACP** (Agent Control Protocol) | Agent → `session/update` → `tool_call` event | `PromptOrchestrator.handleStreamingToolCall` |
| **MCP** (Model Context Protocol) | Agent → `tools/call` JSON-RPC | `McpProtocolHandler` → `PsiBridgeService.runToolExecution` |

ACP tells us *what* the agent wants to do (title, args, routing type).
MCP tells us *how* it was executed (confirmed tool name, result, timing).

The **`ToolCallTracker`** service is the single source of truth that merges
both channels into one `ToolCallRecord` per tool call.

## ToolCallRecord

Each tool call is represented by a `ToolCallRecord` with a stable UUID-based `recordId`
(`tc-{8 hex chars}`). The record accumulates data from whichever channel reports first:

```
┌─────────────────────────────────────────────────────┐
│ ToolCallRecord (recordId: "tc-a1b2c3d4")            │
├──────────────────┬──────────────────────────────────┤
│ ACP side         │ MCP side                         │
│  acpClientId     │  mcpToolName (confirmed name)    │
│  acpTitle        │  mcpArgs                         │
│  acpArgs         │  mcpResult                       │
│  routingType     │  mcpSuccess                      │
│  acpSequence     │  mcpStartedAt / mcpCompletedAt   │
├──────────────────┴──────────────────────────────────┤
│ Shared: argsHash, state, kind, displayName,         │
│         correlated (bool), resultBytes              │
└─────────────────────────────────────────────────────┘
```

### States

| State | Meaning |
|-------|---------|
| `PENDING` | ACP reported the call, MCP has not started |
| `RUNNING` | MCP execution in progress |
| `COMPLETED` | MCP execution finished successfully |
| `FAILED` | MCP execution finished with error |
| `EXTERNAL` | ACP reported a call that is not an MCP tool (built-in agent tool) |

### Routing Types

| Type | Meaning |
|------|---------|
| `REGULAR` | Normal tool call |
| `SUB_AGENT` | Agent launched a sub-agent task |
| `SUB_AGENT_INTERNAL` | Internal call within a sub-agent |
| `TASK_COMPLETE` | Sub-agent task completion notification |

## Correlation

When both ACP and MCP report the same tool call, the record becomes **correlated**.
Two correlation strategies are tried in priority order:

### Priority 0: toolUseId (direct match)

Some ACP clients include a `toolUseId` field (e.g. Claude's tool use ID). If the MCP
request also carries this ID, correlation is instant and exact.

### Priority 1: Deterministic args hash

When `toolUseId` is unavailable, correlation falls back to a deterministic hash of the
tool call arguments (`ToolCallTracker.computeHash(JsonObject)`). This works because
the same args object is forwarded from ACP to MCP.

**Late-arriving args**: ACP sometimes sends `tool_call` without args, then provides them
later in `tool_call_update` with status=running. `acpProvideArgs(acpClientId, args)`
updates the record's hash and retries correlation against orphan MCP records.

### Correlation result

When a record becomes correlated:
- `isCorrelated()` returns `true`
- `getEffectiveToolName()` returns the confirmed MCP tool name (preferred over ACP title)
- `onCorrelated` event fires to listeners (e.g. UI updates chip border style)

## Registration Flow

### ACP-first (typical)

```
1. PromptOrchestrator receives tool_call from ACP stream
2. Calls tracker.acpRegister(acpClientId, title, args, hash, routingType, toolUseId)
3. Tracker creates record with PENDING state, fires onAcpRegistered
4. If args hash matches an existing MCP-only record → merge + fire onCorrelated
5. PromptOrchestrator passes recordId to ChatConsolePanel for DOM chip creation
```

### MCP-first (happens when MCP executes before ACP notification arrives)

```
1. PsiBridgeService receives tools/call from MCP
2. Calls tracker.mcpRegister(toolName, args, hash, toolUseId)
3. Tracker creates record with RUNNING state, fires onMcpRegistered
4. If args hash matches an existing ACP-only record → merge + fire onCorrelated
5. When execution completes: tracker.mcpComplete(hash, result, success)
```

## Event System

`ToolCallTracker.Listener` fires events on the EDT via `invokeLater`:

| Event | When | Typical consumer action |
|-------|------|------------------------|
| `onAcpRegistered` | ACP reports a new tool call | Create DOM chip |
| `onMcpRegistered` | MCP starts executing a tool | (internal bookkeeping) |
| `onCorrelated` | ACP↔MCP match found | Update chip to solid border |
| `onMcpCompleted` | MCP execution finished | Set chip to complete/failed state |
| `onAcpCompleted` | ACP reports completion | Flush the record |
| `onFlushed` | Record removed from live set | DOM cleanup if needed |

### ChatConsolePanel listener

The chat panel's `trackerListener` subscribes to `onCorrelated` and `onMcpCompleted`:
- `onCorrelated` → calls `markMcpHandled(recordId)` to give the chip a solid border
- `onMcpCompleted` → calls `setToolCallState(recordId, complete/failed)`

The `recordId` maps directly to a DOM element ID via `domId(recordId)`.

## Flush Logic

Records are removed from the live set when they are no longer needed:

1. **ACP-correlated calls**: flushed immediately when `acpComplete(acpClientId, success)`
   is called. ACP is the ground truth for when an agent is done with a call.

2. **MCP-only calls** (no ACP counterpart): flushed when a newer ACP-correlated call
   arrives. Since ACP reports calls in order, if call N has been correlated and an
   MCP-only call has `acpSequence < N`, it will never be correlated. This is handled
   by `flushOlderUncorrelatedMcpRecords(currentSequence)`.

3. **`clear()`**: called at session end to flush all remaining records.

## Lookup Methods

| Method | Use case |
|--------|----------|
| `findByAcpId(acpClientId)` | PromptOrchestrator needs to update a call by ACP ID |
| `findByRecordId(recordId)` | UI needs to read record data by stable ID |
| `findByMcpCall(toolName, args)` | MCP tab: find ACP metadata for an MCP execution |
| `getStoredResult(hash)` | Retrieve cached MCP result by args hash |

## Client-Specific Tool ID Resolution

ACP clients resolve tool names differently. The `resolveToolId` method in each client
maps the protocol title to an internal tool ID:

- **MCP tools**: Strip the MCP prefix (e.g. `agentbridge-read_file` → `read_file`)
- **Known built-in tools**: Normalize to lowercase (e.g. `Bash` → `bash`)
- **Unrecognized titles**: Pass through as-is — `ToolCallTracker` will correct the
  display name to the confirmed MCP tool name once execution is correlated

## File Reference

| File | Responsibility |
|------|----------------|
| `services/ToolCallTracker.java` | Single source of truth, correlation, events |
| `services/ToolCallRecord.java` | Mutable record aggregating ACP + MCP data |
| `ui/PromptOrchestrator.kt` | ACP-side registration (`acpRegister`, `acpComplete`) |
| `psi/PsiBridgeService.java` | MCP-side registration (`mcpRegister`, `mcpComplete`) |
| `ui/ChatConsolePanel.kt` | UI listener for chip state updates |
| `services/ToolCallHasher.java` | Deterministic JSON hashing utility |
