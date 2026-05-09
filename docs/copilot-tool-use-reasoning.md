# Copilot Tool-Use Reasoning

A collection of introspective case studies on how the Copilot agent decides which
tool to use. These are used to identify gaps in the guardrail layers and improve
AgentBridge's instructions and plugin behaviour.

---

## Case 1: GitHub PR management via native `bash` + `gh` (2026-05-09)

### Task

Close PR #559 and open a new one for the same branch.

### What the agent did

Used the native `bash` tool three times:

```
gh pr view 559 ...
gh pr close 559 ...
gh pr create ...
```

### What it should have done

Option A — `agentbridge-run_command` with the same `gh` commands (shell stays
in the IDE Run panel, audit hooks fire).

Option B — `agentbridge-http_request` directly to the GitHub REST API
(`PATCH /repos/.../pulls/559` + `POST /repos/.../pulls`), with no CLI dependency.

### Why the agent chose native `bash`

**1. Environment context advertised the tools**

The Copilot CLI injected an `<environment_context>` block into the system prompt:

```
Available tools: git, curl, gh
```

Seeing `gh` listed as "available" created a strong prior that using it via `bash`
was the intended path. This happens even when PATH stripping is enabled, because
the CLI generates the environment context block before the stripped PATH takes
effect, or because `gh` lives in a protected system directory that stripping can't
hide.

**2. Pattern match: "GitHub task → `gh` → `bash`"**

GitHub PR operations are a natural fit for `gh`. The agent's training strongly
associates `gh pr create` with `bash`. Without an explicit MCP alternative (there
is no `agentbridge-github_pr` tool), the reflex fired before any deliberate
tool selection happened.

**3. Contradictory instructions**

Two instruction blocks competed:

- AgentBridge block: *"Do NOT use bash, glob, grep, read, write, edit, or
  run_command."*
- `<tool_preferences>` block: *"Use built-in tools instead of bash tools
  whenever possible."*

The phrase "built-in tools" is ambiguous — it can mean the Copilot CLI's own
built-in tools (including `bash`) rather than the IDE-integrated MCP tools the
instruction intended. This contradiction reduced the signal strength of both.

**4. `agentbridge-run_command` description not prominent enough**

The `agentbridge-run_command` tool description says *"Prefer this over the
built-in bash tool"* — a correct and actionable signal — but it is only visible
when the agent scans the tool list. Under multi-step reasoning, the agent
pattern-matched to `bash` before scanning tool descriptions.

**5. No positive routing for GitHub operations**

The instructions say "don't use `bash`" but don't say "for GitHub API calls,
use `agentbridge-http_request` or `agentbridge-run_command` with `gh`." The
negative prohibition without a positive routing hint leaves a gap the training
data fills with the native tool.

**6. Reprimand nudge arrived too late**

The nudge was injected into the result of the *next MCP tool call*, which was a
`read_file` call three turns later. By then all three bash calls had already
completed. The nudge is reactive, not preventive.

### Root cause of PATH stripping failure (S1 deep dive)

PATH stripping IS applied correctly — the `ProcessBuilder` launches the Copilot
CLI with the sanitized PATH. The stripping fails for a different reason:

**`/usr/bin` is in `ESSENTIAL_SYSTEM_DIRS` and cannot be removed.**

```java
private static final Set<String> ESSENTIAL_SYSTEM_DIRS = Set.of(
        "/usr/bin", "/bin", "/usr/sbin", "/sbin"
);
```

On Linux, system package managers (`apt`, `dnf`, `pacman`) install tools into
`/usr/bin`. So `git`, `gh`, and `curl` all live in `/usr/bin` — a directory
the sanitizer must keep for basic OS function. The sanitizer removes
Homebrew/nvm/cargo paths (e.g. `~/.local/bin`, `/home/linuxbrew/.linuxbrew/bin`)
but cannot touch `/usr/bin`.

On **macOS with Homebrew**, tools go to `/opt/homebrew/bin` or `/usr/local/bin`
which CAN be stripped — PATH stripping is effective there.

On **Linux with apt/dnf**, PATH stripping is effectively a no-op for the tools
we care most about. The Copilot CLI scans its (still-full) PATH, finds
`gh`/`git`/`curl` in `/usr/bin`, and injects them into `<environment_context>`.

**The plugin cannot intercept `<environment_context>` directly.** The block is
generated internally by the Copilot CLI and injected into the model prompt
without passing through the ACP layer that the plugin controls. The only plugin-
controlled text that reaches the model is the agent definition + startup
instructions.

**Actionable fix:** Add an explicit instruction in the agent definition or
startup instructions that directly addresses the `<environment_context>` block:
> *"You will see an `<environment_context>` block listing tools like `git`,
> `gh`, `curl`. That list reflects the host OS PATH — these tools are
> deliberately restricted in this session. Do NOT use them. Use the
> `agentbridge-*` MCP tools exclusively."*

This is the only lever the plugin controls that can counteract the block.

### Gaps identified

| Gap                                                               | Layer affected                 | Severity |
|-------------------------------------------------------------------|--------------------------------|----------|
| PATH stripping is ineffective on Linux (tools live in `/usr/bin`) | Layer 1                        | High     |
| `<environment_context>` cannot be intercepted by the plugin       | Layer 1                        | High     |
| Agent definitions don't mention `<environment_context>` by name   | Layer 3                        | Medium   |
| "built-in tools" phrasing is ambiguous                            | Layer 2 (startup instructions) | Medium   |
| `agentbridge-run_command` not mentioned in startup instructions   | Layers 2+3                     | Medium   |
| Nudge is reactive (post-hoc), not preventive                      | Layer 4                        | Medium   |

### Suggested improvements

See the "Suggested improvements" section at the bottom of this document.

---

## Suggested improvements (aggregate)

### S1 — Address `<environment_context>` in agent definitions (Linux gap)

PATH stripping cannot remove `/usr/bin`-installed tools on Linux. The only
remaining lever is instructions. Add to the agent definition system prompt:
> *"You will see an `<environment_context>` block listing system tools like
> `git`, `gh`, `curl`. That list reflects the host OS — these tools are
> deliberately restricted in this session. Do NOT use them. Use
> `agentbridge-*` MCP tools exclusively."*

Naming `<environment_context>` explicitly is important — it removes the
implicit permission the block grants.

### S2 — Fix the "built-in tools" phrasing

In `<tool_preferences>`: replace *"Use built-in tools instead of bash tools
whenever possible"* with something unambiguous, e.g.:
*"For file search and reading, prefer `agentbridge-search_text`,
`agentbridge-glob`, and `agentbridge-read_file` over the native `bash` tool."*

### S3 — Positive routing (out of scope for generic plugin)

Adding tool-specific routing ("`gh` → use `agentbridge-run_command`") would
bake in assumptions about the user's local tools. The plugin should stay
generic. Instead, local `~/.copilot/agents/intellij-default.md` overrides or
per-project copilot-instructions.md can add this routing for specific setups.

### S4 — Mention `agentbridge-run_command` explicitly in startup instructions

*"When you need to run a shell command, ALWAYS use `agentbridge-run_command` —
never the native `bash` tool."* Currently its tool description says this but
startup instructions don't, so the signal only reaches the model if it actively
scans tool descriptions.

### S5 — Proactive nudge: what was tried and why it was abandoned

The intended approach was to fail native tool calls with a synthetic error,
forcing the agent to re-plan and pick the MCP equivalent. **This was tried and
abandoned** because:

- Copilot treats a tool call failure as a terminal state and ends the turn
  immediately, leaving the user to re-prompt.
- The current reactive approach (inject nudge into the *next MCP result*) is
  a deliberate compromise: it lets the current turn complete (with the wrong
  tool) but steers the next turn correctly.
- The fundamental tension: interrupting mid-turn is disruptive; waiting until
  the next MCP call may be too late when the agent chains all native tools.
- No better mechanism is currently available in the ACP protocol.

### S6 — MCP hooks are opaque by design

The plugin silently injects bot identity tokens and short-lived OAuth tokens
into outgoing requests via MCP hooks. Agents don't need to know about this and
shouldn't — keeping hooks opaque lets agents focus on development work while
deterministic infrastructure concerns are handled transparently.

**Open question:** Should hooks be advertised to the agent? Explicit knowledge
(e.g. "your HTTP requests to github.com automatically use the bot token") could
help the agent make better routing decisions. A boolean "advertise hooks" setting
could expose this when transparency is preferred, while keeping the default
silent for cleaner prompts.

### S6.5 — Local agent instructions can leverage hook transparency

Without changing the plugin, per-setup agent instructions
(`~/.copilot/agents/intellij-default.md`) can tell the agent:
> *"For GitHub API calls, use `agentbridge-http_request` or
> `agentbridge-run_command` with `gh`. Authentication is handled automatically
> by the plugin — you do not need to manage tokens."*

This gives the positive routing benefit without making the plugin non-generic.
