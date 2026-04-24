# Copilot CLI Tool Filtering Workaround

> **See Also:** [Junie Built-In Tool Workaround](JUNIE-TOOL-WORKAROUND.md) — A similar but more severe limitation where
> Junie doesn't send permission requests at all, making protocol-level blocking impossible.

## The Issue

**GitHub Issue:** https://github.com/github/copilot-cli/issues/556  
**Title:** Copilot CLI --agent use does not respect tool filtering  
**Status:** OPEN (assigned, labeled as bug)  
**Reported:** Nov 13, 2025  
**Last Updated:** Jan 5, 2026

### Description

**Important:** `excludedTools` in `session/new` is **NOT part of the official ACP spec
** ([session-setup](https://agentclientprotocol.com/protocol/session-setup))
— it's a custom extension that some agents support (e.g., OpenCode). However, Copilot CLI's own filtering mechanisms
also don't work:

- CLI flags: `--available-tools`, `--excluded-tools` - IGNORED
- Session params: `availableTools`, `excludedTools` in `session/new` - IGNORED
- Agent configs: `tools: ["read"]` in agent Markdown files - IGNORED

**Result:** Agent receives ALL tools regardless of filtering attempts.

While ignoring `excludedTools` is technically spec-compliant, the CLI's own documented flags also failing is a bug.

### Affects All SDKs

Since all SDKs (Python, Go, TypeScript, .NET, Java) communicate with the same CLI server underneath, none can work
around this bug. The Java SDK's `setAvailableTools()` API is correct, but the CLI ignores it.

## Our Workaround

Since we can't filter tools at the CLI level, we enforce it at runtime via **permission denial**.

### Implementation

**File:** `plugin-core/src/main/java/com/github/copilot/intellij/bridge/CopilotAcpClient.java`

**Denied Permission Kinds:**

```java
private static final Set<String> DENIED_PERMISSION_KINDS = Set.of(
        "edit",          // CLI built-in view tool - deny to force write_file
        "create",        // CLI built-in create tool - deny to force write_file
        "read",          // CLI built-in view tool - deny to force read_file
        "execute",       // Generic execute - doesn't exist, agent invents it
        "runInTerminal"  // Generic name - actual tool is run_in_terminal
);
```

**Retry Message:**

```
❌ Tool denied. Use tools with 'agentbridge-' prefix instead.
```

### Why This Works

1. Agent tries CLI built-in tool (e.g., `view`)
2. Plugin denies permission request
3. Agent receives simple guidance: "Use agentbridge- prefix"
4. Agent retries with correct tool (e.g., `agentbridge-read_file`)

### Benefits of IntelliJ Tools

- ✅ Read **live editor buffers** (with unsaved changes)
- ✅ Integrated with IntelliJ's Document API (undo/redo, VCS tracking)
- ✅ AST-based search (not pattern matching)
- ❌ CLI tools read **stale disk files** (don't see unsaved edits)

## Experiment: `--deny-tool` Flag (Feb 2026)

### Hypothesis

While `--available-tools` and `--excluded-tools` operate at the **tool filtering layer**
(broken in ACP mode per bug #556), `--deny-tool` operates at the **permission layer** —
it auto-denies permission requests for specified tools. This is the same mechanism as our
`DENIED_PERMISSION_KINDS` workaround, but enforced by the CLI process itself.

If `--deny-tool` works in ACP mode, it would be a stronger defense because:

- The CLI denies tools **before** they reach our permission handler
- It may also block platform-invoked tools that bypass `request_permission` entirely
- It's a single CLI flag vs. our multi-layer runtime workaround

### What We Added

```
--deny-tool view edit create grep glob bash
```

Added to `buildAcpCommand()` in `CopilotAcpClient.java`.

### What to Verify

1. **Does the flag work in ACP mode?** — Check if tools are denied without reaching `handlePermissionRequest()`
2. **Does it block platform-invoked tools?** — The key question: do `view`/`edit`/`bash` still bypass when the platform
   invokes them directly?
3. **Error messages** — Does the CLI provide useful guidance, or do we still need our custom retry messages?

### Test Results (Feb 26, 2026)

**Status:** FAILED — `--deny-tool` does NOT work in ACP mode.

Results from live testing:

| Tool     | Has permission step? | Blocked by `--deny-tool`? | Blocked by our workaround?          |
|----------|----------------------|---------------------------|-------------------------------------|
| `bash`   | ✅ Yes                | ❌ No                      | ✅ Yes — `DENIED_PERMISSION_KINDS`   |
| `edit`   | ✅ Yes                | ❌ No                      | ✅ Yes — `DENIED_PERMISSION_KINDS`   |
| `create` | ✅ Yes                | ❌ No                      | ✅ Yes — `DENIED_PERMISSION_KINDS`   |
| `view`   | ❌ No — auto-executes | ❌ No                      | ❌ No — post-execution guidance only |
| `grep`   | ❌ No — auto-executes | ❌ No                      | ❌ No — post-execution guidance only |
| `glob`   | ❌ No — auto-executes | ❌ No                      | ❌ No — post-execution guidance only |

**Key finding:** The CLI has two classes of built-in tools:

1. **Write/Execute tools** (`bash`, `edit`, `create`) — require `request_permission` → our
   `DENIED_PERMISSION_KINDS` catches them ✅
2. **Read-only tools** (`view`, `grep`, `glob`) — auto-execute without permission → unblockable ❌

**Side effect:** `--deny-tool` uses variadic argument parsing. Adding it before `--config-dir`
and `--additional-mcp-config` likely caused the CLI to consume those flags as tool names,
breaking MCP server registration entirely (MCP tools disappeared from the agent's tool set).

**Decision:**

- **Removed** `--deny-tool` flags (no effect + breaks MCP registration)
- **Removed** `DENIED_PERMISSION_KINDS` static set (replaced by per-tool ToolPermission settings)
- **Removed** `tool_call` interception for read-only tools (see below)
- All three CLI filtering mechanisms (`--available-tools`, `--excluded-tools`, `--deny-tool`)
  confirmed broken in ACP mode — this is all the same CLI bug #556

## Sub-Agent Limitations (Mar 2026)

### The Problem

Sub-agents (explore, task, general-purpose) launched via the Copilot `task` tool run in their
own internal context within the CLI. They do **not** receive:

- `.github/copilot-instructions.md` custom instructions
- `session/message` guidance notifications from the plugin

### What We Tried

1. **`sendSubAgentGuidance()`** — sent comprehensive IntelliJ tool instructions via
   `session/message` when a sub-agent was detected. Result: completely ignored.
2. **`interceptBuiltInToolCall()`** — detected every built-in tool call (view, grep, glob)
   via `tool_call` notifications and sent corrective guidance via `session/message`.
   Result: 40+ guidance messages sent in a single sub-agent turn with zero behavioral change.
3. **`classifyBuiltInTool()`** — classified tool calls by title patterns and returned
   targeted guidance. Never had any effect because the delivery mechanism was broken.

### Why It Failed

`session/message` is a fire-and-forget JSON-RPC notification. It either:

- Gets silently discarded by the CLI
- Goes to the main agent's message queue, not the sub-agent's execution context
- Gets queued for a future turn, not the current one

### What Still Works

- **Permission denial** for write/execute tools (`edit`, `create`, `bash`) — these require
  `request_permission`, and our denial forces retry with MCP tools ✅
- **Sub-agent git write blocking** — `detectSubAgentGitWrite()` prevents destructive git
  operations from sub-agents ✅
- **Sub-agent built-in write blocking** — `detectSubAgentWriteTool()` denies ALL built-in
  write/execute tools (`edit`, `create`, `bash`, `write`, `execute`, `runInTerminal`) when
  a sub-agent is active, regardless of per-tool permission settings ✅

### What Cannot Be Intercepted

- **Read-only built-in tools** (`view`, `grep`, `glob`) — auto-execute without permission
  and cannot be blocked or redirected ❌
- **Sub-agent custom instructions** — sub-agents don't see `.github/copilot-instructions.md` ❌

### Practical Impact

For saved files, built-in read-only tools give identical results to IntelliJ MCP tools.
The only difference is for unsaved editor buffers, which is an edge case for sub-agents
(they typically don't edit files). This is an acceptable limitation until CLI bug #556 is fixed.

### Code Removed

- `sendSubAgentGuidance()` — proactive guidance on sub-agent start
- `interceptBuiltInToolCall()` — post-hoc detection and guidance for built-in tool calls
- `classifyBuiltInTool()` — title-based tool classification for targeted guidance
- `BUILT_IN_TOOL_WARNING_PREFIX` constant
- `DENIED_PERMISSION_KINDS` static set (replaced by per-tool permissions)
- `CREATE_KIND` constant

## When to Remove This Workaround

Monitor https://github.com/github/copilot-cli/issues/556 for updates.

Once fixed:

1. **Test:** Verify `availableTools` session param actually filters tools
2. **Update:** Switch from permission denial to proper filtering
3. **Keep:** Still deny `execute`/`runInTerminal` (non-existent tools)
4. **Remove:** This documentation file

## Revalidation: CLI v1.0.3 GA (Mar 10, 2026)

### Motivation

A GitHub collaborator commented on the issue (Jan 3, 2026): *"I cannot repro this as of 0.0.374"*.
The CLI has since reached v1.0.3 GA. We retested all four tool filtering mechanisms.

### Test Results

| Mechanism                                  | How tested                                                                         | Result                                      |
|--------------------------------------------|------------------------------------------------------------------------------------|---------------------------------------------|
| `excludedTools` in `session/new` params    | Sent `["view","edit","create","bash","grep","glob"]`                               | ❌ **IGNORED** — all 6 tools still present   |
| `--excluded-tools` CLI flag                | `copilot --acp --stdio --excluded-tools view edit create bash grep glob`           | ❌ **IGNORED** — all 6 tools still present   |
| `--available-tools` CLI flag (whitelist)   | `copilot --acp --stdio --available-tools task web_fetch report_intent update_todo` | ❌ **IGNORED** — all 105 tools still present |
| `--agent` with `allowed-tools` frontmatter | `.github/agents/ide-task.md` with `allowed-tools: [Intellij-*]`                    | ❌ **IGNORED** — all 105 tools still present |

### Conclusion

Bug #556 is **NOT fixed in ACP mode** as of CLI v1.0.3 GA. The collaborator's "cannot repro"
comment likely referred to interactive CLI mode (`copilot --agent`), not ACP mode (`copilot --acp`).

All tool filtering mechanisms — session params, CLI flags, and agent definition frontmatter — are
still completely ignored when running in ACP mode. Our permission-denial workaround remains the
only viable approach.

### New Mitigation: Sub-Agent Write Blocking

Added `detectSubAgentWriteTool()` which unconditionally denies ALL built-in write/execute tools
(`edit`, `create`, `bash`, `write`, `execute`, `runInTerminal`) when `subAgentActive` is true.
This prevents sub-agents from writing through the CLI's built-in tools (which bypass IntelliJ's
editor buffer), forcing them to use MCP tools or fail gracefully.

Combined with the existing `detectSubAgentGitWrite()`, sub-agents are now blocked from:

- All file write operations via built-in tools ✅
- All git write operations via MCP tools ✅
- Read-only built-in tools (`view`, `grep`, `glob`) — still unblockable ❌

### Forward Compatibility: Agent Definitions

When bug #556 is eventually fixed for ACP mode, the `.github/agents/` definitions will
automatically take effect. Prepare agent definition files with `allowed-tools` restrictions
so they activate without code changes once the CLI respects them.

## Revalidation: CLI v1.0.31 (Apr 17, 2026)

### Motivation

Check whether CLI 1.0.31 (which added Claude Opus 4.7 support) has fixed any tool-filtering
mechanism, and whether our bundled agent definitions now take effect in `--acp` mode.

### Method

Reusable ACP probe at `.agent-work/experiments/acp-tool-filter-probe.mjs` — drives
`initialize` → `session/new` → `session/prompt`, auto-approves permission requests, captures
the tool list the model reports and any `tool_call` notifications. Run in an isolated cwd so
nothing else leaks into context. Model defaulted to `claude-sonnet-4.5`; Opus 4.7 spot-checked
as case 6.

### Test Results (Apr 17, 2026)

All cases ran against Copilot CLI 1.0.31 in `--acp` mode.

| # | Flag / agent                                                                     | Model             | Result                                              |
|---|----------------------------------------------------------------------------------|-------------------|-----------------------------------------------------|
| 1 | (none, baseline)                                                                 | claude-sonnet-4.5 | 34 tools — full built-in set exposed                |
| 2 | `--excluded-tools view,edit,create,bash,grep,glob,task`                          | claude-sonnet-4.5 | Identical 34-tool list — **flag ignored**           |
| 3 | `--available-tools web_search,report_intent`                                     | claude-sonnet-4.5 | Identical 34-tool list — **whitelist ignored**      |
| 4 | `--deny-tool view,grep,glob`                                                     | claude-sonnet-4.5 | Identical 34-tool list — **flag ignored**           |
| 5 | `--agent test-agent` with valid `tools: [web_search, report_intent]` frontmatter | claude-sonnet-4.5 | Identical 34-tool list — **agent `tools:` ignored** |
| 6 | `--excluded-tools view,edit,create,bash,grep,glob`                               | claude-opus-4.7   | Identical 34-tool list — flag still ignored         |

**Conclusion:** No change vs. v1.0.3 GA. `--excluded-tools`, `--available-tools`, `--deny-tool`,
and per-agent `tools:` whitelists are all silently no-ops in ACP mode on CLI 1.0.31. Opus 4.7
sees the same tool list as Sonnet 4.5 — what differs is only the model's willingness to ignore
in-prompt nudges, not the tool surface.

Evidence: `.agent-work/experiments/bug-556-retest-1.0.31-results.md` (raw probe output).

### Upstream bug status (Apr 17, 2026)

Summary of the GitHub issues we depend on, all still **open** with no merged fix:

| Issue                                                      | Title / scope                                                                    | Last activity                                                                    | Impact on us                                                             |
|------------------------------------------------------------|----------------------------------------------------------------------------------|----------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| [#556](https://github.com/github/copilot-cli/issues/556)   | ACP mode ignores `--excluded-tools`, `--available-tools`, and per-agent `tools:` | Maintainer assigned; last comment Jan 5 2026 ("will check today" — no follow-up) | Primary — blocks tool filtering entirely                                 |
| [#1574](https://github.com/github/copilot-cli/issues/1574) | Custom JSON-RPC `tools` in `session/new` params silently ignored                 | Open                                                                             | Cannot filter via the ACP session-create extension                       |
| [#1607](https://github.com/github/copilot-cli/issues/1607) | ACP spec has no session-level permission primitive                               | Upstream ACP spec                                                                | Structural — `request_permission` is the only hook we have               |
| [#1969](https://github.com/github/copilot-cli/issues/1969) | No `systemPrompt` in `session/new`                                               | Open                                                                             | Forces us to use `prependInstructionsTo` + MCP `initialize.instructions` |
| [#2059](https://github.com/github/copilot-cli/issues/2059) | ACP mode ignores most CLI flags (`--model`, `--agent`, etc.)                     | Open                                                                             | Agent-file `tools:` route is therefore also dead                         |

**Collateral:** Our own `BundledAgentDeployer.deployAgent()` writes an HTML comment before the
YAML frontmatter, which Copilot's agent loader then rejects as "malformed YAML frontmatter".
That bug is orthogonal (fixing it does not make #556 work — case 5 above used a correctly-
formatted file and still failed) but is logged in `.agent-work/clarity-backlog.md` for a
follow-up.

### Current mitigation

Given none of the CLI filter surfaces work, the only remaining lever is **in-prompt policy**,
delivered via `plugin-core/src/main/resources/default-startup-instructions.md` through two
channels:

1. `InstructionsManager.ensureInstructions` splices it into `.github/copilot-instructions.md`
   (via Copilot's `AgentProfile.prependInstructionsTo`).
2. `McpProtocolHandler.initialize` returns it in the MCP `instructions` field.

The file was rewritten on Apr 17, 2026 with a hard, explicit tool-policy block tuned for
aggressive models (Opus 4.7 in particular): lowercase forbidden names exactly as the CLI
exposes them, and a replacement mapping per capability. `InstructionsManager` was also
upgraded to splice the block in-place so upgrades refresh existing installs rather than
stacking duplicate blocks.

## Bug Root Cause: `--available-tools` / `--excluded-tools` Silently Ignored in ACP Mode

**GitHub Issue:** https://github.com/github/copilot-cli/issues/2948  
**Title:** `--available-tools` and `--excluded-tools` are silently ignored when used with `--acp`  
**Status:** OPEN  
**Reported:** Apr 25, 2026

### What We Found

The ACP startup path in `index.js` branches on `Jpt.newSession()` / `Jpt.loadSession()` and
returns early **before** calling `session.updateOptions({ availableTools, excludedTools })`.
The interactive (`Mto`) and non-interactive (`Cul`) code paths both call `updateOptions`, but the
ACP path does not.

```
ACP startup path:
  session = await Jpt.newSession(...)   // or Jpt.loadSession(...)
  return { session, ... }               // ← EARLY RETURN — options never applied

Interactive path (Mto):
  session = await Jpt.newSession(...)
  session.updateOptions({ availableTools, excludedTools })  // ← applied

Non-interactive path (Cul):
  session = await Jpt.newSession(...)
  session.updateOptions({ availableTools, excludedTools })  // ← applied
```

### What `--deny-tool` Does (Different Mechanism)

`--deny-tool` operates at the **permission layer** (`rules.denied`), not the tool filtering layer.
It auto-denies `session/request_permission` requests before they reach the ACP client.

When tools have a permission step (`bash`, `edit`, `create`), `--deny-tool` does block them
**even in ACP mode**. It does **not** hide the tools from the model's tool list (the model can
see and attempt them; the attempt just fails). See the "Experiment: `--deny-tool` Flag" section below for the history of
why it appeared
broken in early tests (wrong argument ordering, not a bug in the mechanism itself).

### Current Mitigation

We pass both `--deny-tool` and `--excluded-tools` with the same tool list:

- `--deny-tool` blocks tools at the permission layer today (works for permission-gated tools)
- `--excluded-tools` will hide tools from the model entirely once bug #2948 is fixed

See `CopilotClient.BUILTIN_TOOLS_TO_SUPPRESS` for the current list.

## References

- CLI Bug #556 (tool filtering ignored in ACP mode): https://github.com/github/copilot-cli/issues/556
- CLI Bug #2948 (--available-tools/--excluded-tools silently ignored in ACP — root cause
  found): https://github.com/github/copilot-cli/issues/2948
- CLI `--deny-tool` flag: discovered in `copilot --help` output, not documented in bug #556
- Retest evidence: `.agent-work/experiments/bug-556-retest-1.0.31-results.md`
- Probe script: `.agent-work/experiments/acp-tool-filter-probe.mjs`
- Related upstream issues: #1574, #1607, #1969, #2059
- Checkpoint: `.copilot/session-state/.../checkpoints/020-tool-filtering-investigation.md`
- Checkpoint: `.copilot/session-state/.../checkpoints/021-permission-denial-simplification.md`
