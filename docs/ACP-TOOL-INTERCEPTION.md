# ACP Tool Interception — Investigation Postmortem

**Status: Investigated but not adopted**  
**Date:** Apr 25, 2026  
**Related PRs:** [#320](https://github.com/catatafishen/agentbridge/pull/320) (closed without merge)  
**Root bug:** [copilot-cli#2948](https://github.com/github/copilot-cli/issues/2948)

---

## Background

The Copilot CLI exposes native built-in tools (`view`, `grep`, `glob`, `edit`, `create`, `bash`,
`task`, `web_fetch`, etc.) to the agent alongside the MCP tools registered by this plugin.
CLI flags to control which tools are exposed (`--available-tools`, `--excluded-tools`) silently
do nothing in ACP mode (bug [#556](https://github.com/github/copilot-cli/issues/556),
root cause filed as [#2948](https://github.com/github/copilot-cli/issues/2948)).

**The goal:** when native `grep`/`glob` tools are executed, redirect them so the agent reads
IntelliJ's live editor buffers instead of the raw file system. This would fix the `read_file`
buffer-vs-disk divergence for sub-agents and parallel edits.

---

## What We Tried

### Approach 1 — `session/request_permission` denial

Deny native tools at the permission layer so the agent falls back to MCP equivalents.

**Result:** Only works for write/execute tools (`bash`, `edit`, `create`). Read-only tools
(`view`, `grep`, `glob`) execute without any permission step — they bypass the
`session/request_permission` protocol entirely. Permanently not fixable via this channel.

### Approach 2 — PATH shim (PR #320)

Intercept native `rg` and `glob` via a shell script injected into `PATH`. The shim inspects
the arguments and either calls through to the real binary or rewrites the call.

**Result:** Intercepted ~30% of invocations. Native `rg`/`glob` tools send `tool_call` /
`tool_call_update` as JSON-RPC notifications (informational, not executable requests). For
`view`/`grep` the CLI calls `rg` via `node-pty` internal spawn, which does NOT consult the
agent's `PATH` — it uses an absolute path or the CLI's own bundled binary. PATH shim only
fires for the subset of invocations that go through a real child shell (`execvp` + PATH lookup),
which is the minority path.

**Why this edge-case-only:** The Copilot CLI's native tools are implemented in JS using
`node-pty` or direct filesystem APIs. They do not shell out through a user-visible `PATH`
environment. The shim catches commands the model explicitly runs in a bash block (when a
sub-agent runs `rg` literally), but not the CLI's own internal search calls.

**Code added and later reverted:**

- `CopilotClient.buildShimPath()` — created a temp bash script shadowing `rg` and `patch`
- `ShimLauncher` service — lifecycle management for the shim binary
- `AcpClient.interceptBuiltInToolCall()` — post-hoc notification handler (had no effect,
  see Sub-Agent Limitations section of `CLI-BUG-556-WORKAROUND.md`)

---

## What We Kept

### `patch` tool passthrough

The `patch` tool is kept in `BUILTIN_TOOLS_TO_SUPPRESS` as a **denied tool** but we also pass
`--deny-tool patch` so it is blocked at the permission layer. If the CLI ever exposes a `patch`
native tool, we want it denied by default.

### `--deny-tool` + `--excluded-tools` dual flags

`CopilotClient.buildCommand()` now passes both:

- `--deny-tool <list>` — works today at the permission layer (blocks execution)
- `--excluded-tools <list>` — will hide tools from the model once bug #2948 is fixed

This forward-compatibility ensures the fix lands automatically without a plugin release.

---

## Architecture: How Native Tool Calls Flow

```
Agent decides to call "grep"
         │
         ▼
CLI intercepts in JS (not via ACP)
         │
         ├── Sends tool_call / tool_call_update NOTIFICATION to ACP client (informational only)
         │   └── Plugin receives it but CANNOT block or redirect
         │
         ├── Spawns rg internally (node-pty or direct Node fs API)
         │   └── Ignores agent PATH; uses CLI-bundled binary
         │
         └── Returns result directly to model context
```

```
Agent runs "grep" inside a bash block
         │
         ▼
CLI spawns bash shell (uses agent PATH)
         │
         └── bash PATH lookup → could hit a shim here ← limited interception window
                  │
                  └── Even if intercepted: result returned via bash stdout, not IntelliJ buffers
```

---

## Interception Matrix

| Tool        | Delivery                     | Permission step? | PATH shim works? | Blockable? |
|-------------|------------------------------|------------------|------------------|------------|
| `view`      | Native JS (node-pty)         | No               | No               | ❌          |
| `grep`      | Native JS (node-pty)         | No               | No               | ❌          |
| `glob`      | Native JS (node-pty)         | No               | No               | ❌          |
| `edit`      | `session/request_permission` | Yes              | N/A              | ✅ deny     |
| `create`    | `session/request_permission` | Yes              | N/A              | ✅ deny     |
| `bash`      | `session/request_permission` | Yes              | N/A              | ✅ deny     |
| `task`      | `session/request_permission` | Yes              | N/A              | ✅ deny     |
| `web_fetch` | `session/request_permission` | Yes              | N/A              | ✅ deny     |

---

## Conclusion

There is no viable interception hook for read-only native tools (`view`, `grep`, `glob`) in the
current CLI architecture. The only reliable path is CLI-side filtering once bug #2948 is fixed.

Until then:

- Write/execute tools are blocked via `--deny-tool` (forces agent to use MCP alternatives)
- Read-only tools remain unblockable (results come from disk, not IntelliJ buffers)
- For saved files this is acceptable (disk = buffer for committed files)
- For unsaved buffers it remains a known limitation

See `docs/bugs/CLI-BUG-556-WORKAROUND.md` for the full workaround history.
