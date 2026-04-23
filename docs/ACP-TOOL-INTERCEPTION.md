# ACP Tool Interception

> Redirect built-in agent tools (`view`, `edit`, `bash`, …) to MCP equivalents
> at the ACP layer, **before** they execute. Solves long-standing pain points
> that `--excluded-tools` and friends could never fix.

**Status:** shipped in PR #319 on branch `feat/acp-tool-interception-v2`.
**Applies to:** every ACP client we ship (Copilot, Junie, Kiro, OpenCode).
**Not applicable to:** Claude / Codex (non-ACP — they call MCP directly).

---

## Why

For months we tried to make ACP agents stop using their built-in tools because
those tools (a) read stale disk files instead of unsaved editor buffers and
(b) bypass the IDE's VCS/undo plumbing. Every approach failed:

| Mechanism | Result |
|---|---|
| `--excluded-tools` / `--available-tools` (Copilot) | Ignored in ACP mode — bug [copilot-cli#556](https://github.com/github/copilot-cli/issues/556). Still broken in v1.0.31. |
| `excludedTools` in `session/new` (custom ACP extension) | Ignored by Copilot, OpenCode. Junie ≥ v888.212 honours it. |
| `--deny-tool` (Copilot) | Ignored in ACP mode. |
| Per-agent `tools:` frontmatter | Ignored in ACP mode. |
| Permission denial (`DENIED_PERMISSION_KINDS`) | Only catches *write* tools — `view`, `grep`, `glob` auto-execute with no permission step. |
| Reprimand: auto-approve + inject "[System notice]" into next prompt | Lets the wrong action happen first; only corrects future turns. |
| Junie pre-launch `.junie/allowlist.json` | Works only for Junie ≥ v888.212. |

See [`docs/bugs/CLI-BUG-556-WORKAROUND.md`](bugs/CLI-BUG-556-WORKAROUND.md)
and [`docs/JUNIE-TOOL-WORKAROUND.md`](JUNIE-TOOL-WORKAROUND.md) for the full
history.

## The insight

Built-in agent tools **don't execute inside the agent process**. When Copilot
runs `view`, `edit`, or `bash`, it round-trips back to us as the standard ACP
methods `fs/read_text_file`, `fs/write_text_file`, and `terminal/create`.

We already dispatch those in `AcpClient.handleAgentRequest()`. So the fix is
not to filter, deny, or reprimand — it is simply to **redirect** at the ACP
layer before dispatching. No PTY proxy, no shell hijacking, no agent
cooperation needed.

## Architecture

```
Agent calls a built-in tool (e.g. bash "cat foo.txt")
  └── ACP: terminal/create
        → AcpClient.handleAgentRequest()
            ├── AcpToolInterceptor.tryInterceptTerminalCreate(...)
            │     ├── matched + MCP succeeded
            │     │     → synthetic terminalId, return immediately
            │     └── unmatched OR MCP error
            │           → return null (fall through)
            └── falls through to AcpTerminalHandler
                  → VisibleProcessRunner
                        ├── Project + Application present
                        │     → OSProcessHandler + ConsoleViewImpl
                        │     → registered in IDE Run tool window
                        └── headless / test
                              → daemon thread reading process stdout
```

The same pattern wraps `fs/read_text_file` and `fs/write_text_file`. On any
MCP error the interceptor returns `null`, and `AcpClient` falls back to the
real `fsHandler` — so a bug in our MCP layer never breaks the agent.

## What gets intercepted (1:1, no shell parsing risk)

| ACP method | Built-in trigger | Redirected to | Win |
|---|---|---|---|
| `fs/read_text_file` | `view` / `read` | MCP `read_file` | Sees unsaved editor edits |
| `fs/write_text_file` | `edit` / `write` / `create` | MCP `write_file` | Undo stack + format + VCS sync |
| `terminal/create cat <file>` | `bash cat` | MCP `read_file` | Editor buffer sync |
| `terminal/create head [-n N] <file>` | `bash head` | MCP `read_file` (lines 1..N, default 10) | Same |
| `terminal/create grep -F/-E <pat> [file]`, `egrep`, `fgrep` | `bash grep` | MCP `search_text` | Regex + IDE index, POSIX exit codes |
| `terminal/create rg <pat>` (with `-F`/`-i`/`--glob`) | `bash rg` | MCP `search_text` | Same |
| `terminal/create git status` | `bash git status` | MCP `git_status` | Branch context, no shell parse |
| `terminal/create git diff [--staged] [--stat] [path]` | `bash git diff` | MCP `git_diff` | Auto-fetch on `origin/*` refs |
| `terminal/create git log [--oneline] [-n N] [path]` | `bash git log` | MCP `git_log` | Same |
| `terminal/create git branch [-a]` | `bash git branch` | MCP `git_branch` | List remote/local |

Plain `grep` (without `-F` or `-E`) is **not** intercepted — POSIX BRE has
incompatible semantics with Java regex (used by `search_text`). Agents that
run plain `grep` get the real OS process via the visible terminal.

`ls`, `find`, and `tail` are **not** intercepted: `list_project_files` is
recursive and file-only (can't model `ls file`), `find` has a huge flag
surface, and `tail` would need a 2-step read (file length, then range)
because `read_file` truncates large files. All three fall through cleanly.

## What deliberately falls through

Anything we cannot precisely emulate runs as a real OS process via the visible
runner:

- Any shell metacharacter: `|`, `;`, `&&`, `||`, `$( )`, `<`, `>`, backticks, …
- Any flag we don't replicate: `cat -n`, `tail` without explicit `-n N`, …
- Mutating git ops: `commit`, `push`, `rebase`, `merge`, `reset`, …
- Build tools, package managers, network, anything else.

`ShellCommandSplitter` is the gatekeeper — it returns `null` for any input
containing a metacharacter we haven't whitelisted, which forces fall-through.

## Visible fall-through terminal

Per the original request, fall-through commands no longer hide in a sub-shell.
`VisibleProcessRunner` mirrors stdout/stderr to a tab in the IDE **Run** tool
window, so the user can watch the command live and kill it with the standard
stop button. Headless test contexts (no `Application` / no `Project`) fall
back transparently to a daemon stream-reader thread.

## Files

| Path | Role |
|---|---|
| `acp/client/intercept/AcpToolInterceptor.java` | Central interception logic. `@Nullable` returns so AcpClient can fall back to `fsHandler`. Uses ACP `args[]` array directly when present (no re-tokenization). |
| `acp/client/intercept/ShellRedirectPlanner.java` | Pure static classifier: `argv → RedirectPlan?`. All per-command flag parsing lives here. Project-free for testability. |
| `acp/client/intercept/RedirectPlan.java` | Immutable record carrying tool name, args, optional post-processor (e.g. strip `search_text` header), and exit-code function (e.g. grep returns 1 on no matches). |
| `acp/client/intercept/ShellCommandSplitter.java` | Argv tokenizer rejecting unsafe metacharacters; supports single/double quotes and backslash escapes. Used as fallback when ACP only sends `command` (no `args[]`). |
| `acp/client/intercept/VisibleProcessRunner.java` | Wraps `OSProcessHandler` + `ConsoleViewImpl` + `RunContentDescriptor`; degrades to daemon reader when no `Application`/`Project`. |
| `acp/client/AcpTerminalHandler.java` | Always uses `VisibleProcessRunner`. `ManagedTerminal` no longer owns its own reader thread. |
| `acp/client/AcpClient.java` | Holds the interceptor; `handleAgentRequest()` switch uses `redirected != null ? redirected : fsHandler.xxx()`. |

## Tests

- 14 `ShellCommandSplitterTest` cases covering quoting, escaping, every
  rejected metacharacter, blank input, and trailing whitespace.
- 3 `AcpToolInterceptorTest` cases for `isMcpError`.
- 51 `ShellRedirectPlannerTest` cases covering happy paths, fall-throughs,
  flag-parsing edge cases (combined short flags, `--lines=N`, `--glob=`,
  `--` separator), exit-code semantics, and `search_text` header stripping.
- All ACP tests pass; full plugin-core suite unchanged (one pre-existing
  flaky `EmbeddingServiceTest` on master, unrelated).

---

## Follow-ups

These are split between (1) **dead-code removal** that becomes possible *because*
interception exists, and (2) **expansion** of the interception surface itself.

### A. Dead-code removal (this is now ~~obsolete~~ logic)

Interception happens *before* the built-in tool runs. The previous defenses ran
*after* it ran and tried to retroactively undo or correct the damage. With
interception in place these layers are largely (sometimes wholly) obsolete:

- **`CopilotClient.misusedBuiltInTools` + `beforeSendPrompt()` reprimand**
  (`CopilotClient.java:113, 535–567`).
  Records intercepted built-in tool calls and prepends a "[System notice] use
  MCP instead" block to the next prompt. Since the offending call now resolves
  via MCP transparently, there is nothing to reprimand. **Candidate for full
  removal** once we confirm telemetry shows zero misused-built-in events.

- **`PsiBridgeService.setPendingNudge` / `addOnNudgeConsumed` plumbing**
  used only by the reprimand path above. Once the reprimand is gone, these
  hooks have no other call sites — search and delete.

- **`AcpClient.handleBlockedTool` ⇒ `excludeAgentBuiltInTools` denial**
  (`AcpClient.java:1611–1652`).
  This denies built-in tools at the permission layer. With interception, the
  built-in tool *succeeds* via MCP rather than being denied — which is what
  the user actually wanted in the first place. **Decision needed:** keep
  denial as a "strict mode" for tools we explicitly *don't* want intercepted
  (e.g. dangerous `bash`), or remove and rely entirely on interception +
  fall-through-to-visible-terminal.

- **`AgentProfile.excludeAgentBuiltInTools` setting** (`AgentProfile.java:95`).
  The whole point of this knob was "stop the agent using its built-in tools".
  Interception achieves that automatically and silently. The setting can either
  be (a) removed, (b) re-purposed as "force fall-through to visible terminal
  even for intercept-eligible commands" (rare, but useful for debugging), or
  (c) left as a kill-switch that disables interception itself for users who
  hit a regression.

- **`JunieClient` `.junie/allowlist.json` writer** (`JunieClient.java:149–180`).
  Pre-launch deny-list for Junie ≥ v888.212. Still useful as belt-and-braces
  defense (it stops the agent even *trying* the tool, saving a network round
  trip), but no longer load-bearing. Keep, but stop documenting it as the
  primary mitigation.

- **`default-startup-instructions.md` tool-policy block.**
  The aggressive "NEVER use these tools" prompt block was the only thing
  bending Opus 4.7. With interception, an agent that ignores the policy gets
  the right answer anyway. The block can be downgraded from "critical" to a
  short "note: built-in tools are silently redirected to MCP equivalents,
  prefer MCP names for clarity" so the model still emits useful tool names in
  its plans.

- **`detectSubAgentWriteTool` / `detectSubAgentGitWrite`** (Copilot sub-agent
  guards). These exist because sub-agents don't see the policy file. With
  interception, sub-agents calling `edit` / `bash git commit` succeed via MCP
  / `git_*` and the guards become moot for the safe cases. They should remain
  for the *destructive* fall-through cases (e.g. `bash rm -rf`) but can drop
  the per-tool name list.

- **`CopilotClient.EXCLUDED_BUILTIN_TOOLS` + the `--excluded-tools` flag.**
  No longer load-bearing. Can be deleted or kept as forward-compat for the
  day [#556](https://github.com/github/copilot-cli/issues/556) finally lands.

- **External-tool warning emoji (⚠ on tool chips).** Currently signals "the
  agent ignored our prompt and used a built-in tool". With interception the
  built-in tool *was* intercepted, so the chip should either (a) suppress the
  badge entirely or (b) flip to a different visual (e.g. ↻ "redirected") to
  show the user we caught it.

> **Process note:** none of the removals should land in this PR. They each
> need a separate PR with a few sessions of telemetry / sandbox runs to
> confirm the safety net was no longer doing real work.

### B. Expanding the interception surface

Already shipped in the follow-up commit:
- ✅ `git diff`, `git log`, `git branch` (read-only forms)
- ✅ `head -n N <file>`
- ✅ `grep -F/-E`, `egrep`, `fgrep`, `rg` → `search_text`

Still on the list:
- **More git read ops:** `git show`, `git remote -v`, `git tag`,
  `git stash list`, `git status --short`. Each maps cleanly to an existing
  MCP `git_*` tool.
- **`rg <pat> <path>`** — currently rejected because `search_text` doesn't
  accept a directory scope. Could map `<path>` to a `file_pattern` glob like
  `<path>/**` if telemetry shows agents commonly write the path form.
- **Plain `grep`** — needs BRE → Java-regex translation (`\(` → `(`, `+`/`?`
  literal vs metachar). Doable but brittle; defer until we see a real demand.
- **`find` / `fd` → MCP `list_project_files` (glob).** Trickier flag surface;
  start with `find <dir> -name <pattern>`.
- **`ls` → MCP `list_directory_tree`.** Only the no-flag form, and only when
  the target is a directory (semantics differ for `ls file`).
- **`tail -n N <file>` → MCP `read_file` (last N lines).** Requires a
  2-step approach because `read_file` truncates large files to the first
  ~2000 lines; need to fetch the line count first, then request the range.
- **`wc -l <file>` → MCP `read_file` + linecount.** Marginal.
- **OpenCode validation.** OpenCode's ACP semantics are slightly different
  (its agents auto-execute more eagerly). Run the four-client matrix to
  confirm interception fires for OpenCode's `view`/`edit`/`bash` analogues.
- **Junie validation.** Junie's `read`/`write`/`bash` should already flow
  through `fs/read_text_file` / `fs/write_text_file` / `terminal/create`,
  but verify before dropping the prompt-engineering safety net.
- **Telemetry:** emit a counter per `(client, intercepted_tool, outcome)`
  so we can quantify how often interception fires per session. Required
  before removing any of the layers in section A.
- **Sub-agent reach:** confirm interception fires for *sub-agent* tool calls
  too (Copilot's `task` tool spawns isolated sub-agents). The dispatch path
  is the same `handleAgentRequest()`, so it should — but verify.

## References

- PR: [#319](https://github.com/catatafishen/agentbridge/pull/319)
- Branch: `feat/acp-tool-interception-v2`
- Related docs:
  - [`docs/bugs/CLI-BUG-556-WORKAROUND.md`](bugs/CLI-BUG-556-WORKAROUND.md)
  - [`docs/JUNIE-TOOL-WORKAROUND.md`](JUNIE-TOOL-WORKAROUND.md)
  - [`docs/JUNIE-CLI-TOOL-FILTERING.md`](JUNIE-CLI-TOOL-FILTERING.md)
  - [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — overall ACP/MCP layering
- Upstream issues this sidesteps: copilot-cli
  [#556](https://github.com/github/copilot-cli/issues/556),
  [#1574](https://github.com/github/copilot-cli/issues/1574),
  [#2059](https://github.com/github/copilot-cli/issues/2059).
