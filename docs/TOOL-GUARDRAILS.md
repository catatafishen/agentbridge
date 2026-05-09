# Tool Guardrails

AgentBridge provides several layers of guardrails to ensure AI coding agents
use IDE-integrated MCP tools instead of raw CLI tools. This document explains
why these guardrails exist, what they do, and how they work together.

---

## Why guardrails?

Agent CLIs (Copilot, Claude, etc.) come with built-in tools like `bash`, `grep`,
`view`, and `edit`. These tools operate directly on the filesystem, bypassing the
IDE entirely. When an agent uses them instead of the AgentBridge MCP equivalents,
three categories of harm occur:

### 1. Tool hooks are bypassed

The plugin injects bot identity tokens, audit trails, and short-lived OAuth
credentials into every MCP tool call. When the agent uses `bash` with `gh api`
or `curl` instead of `http_request`, API calls are attributed to the **user's
personal identity** rather than the bot. Git commits use the wrong author. The
audit log has gaps.

### 2. Follow-agent mode is bypassed

AgentBridge makes agent actions visible to the user — terminal commands appear
in the IDE, file changes are highlighted, git operations show in the VCS panel.
Native tools are invisible: the user sees nothing and has no way to monitor or
intervene in what the agent is doing.

### 3. IDE buffer sync is lost

The IDE maintains live editor buffers with unsaved edits, a PSI index that
understands code semantically, and a VCS layer that knows what is staged.
Native CLI tools read and write raw disk files behind the IDE's back:

- Unsaved edits become invisible to the agent
- Subsequent reads through the IDE appear stale
- Formatters and inspections run against disk content that doesn't match
  what the user sees on screen
- Git commands via bash modify files and refs outside the VCS layer,
  desynchronizing the editor state

---

## Guardrail layers

The plugin uses a defense-in-depth approach with multiple independent layers:

### Layer 1: PATH sanitization

**What:** Strips non-essential directories from the agent CLI process's `PATH`
before launch. Only keeps essential system directories (`/usr/bin`, `/bin`),
Node.js, and the CLI binary itself.

**Why:** Agent CLIs inspect `PATH` at startup to detect available tools and
advertise them in the system prompt (e.g., Copilot's `<environment_context>`
block: "Available tools: git, curl, gh"). When the agent sees these tools
listed, it's more likely to use them directly instead of MCP equivalents.

**Configuration:** Per-agent checkbox in Settings → AgentBridge → [Agent Name]
→ "Hide system PATH from agent". Enabled by default for Copilot.

**Limitations:** Tools installed in shared system directories (e.g., `git` in
`/usr/bin`) cannot be hidden via PATH stripping alone. The other layers provide
additional enforcement.

**Implementation:** `PathSanitizer.java`, `AcpClient.shouldStripNonEssentialPath()`

### Layer 2: Startup instructions

**What:** Mandatory tool policy injected into the agent's system prompt at
session start. Lists every forbidden built-in tool and its MCP replacement.

**Why:** Even with PATH stripping, agent CLIs expose their own built-in tools
(bash, grep, view, edit, etc.) that don't depend on system PATH. The startup
instructions explicitly forbid these and explain the three categories of harm.

**Where:** `default-startup-instructions.md` → prepended to the agent's
instruction file (e.g., `.github/copilot-instructions.md` for Copilot).

**Implementation:** `StartupInstructionsSettings`, `InstructionsManager`

### Layer 3: Agent definitions

**What:** Each agent definition (e.g., `intellij-default.md`) includes a system
prompt that states: "You do NOT have direct access to git, curl, gh, or any CLI
tool — use the agentbridge equivalents instead."

**Why:** Agent definitions are loaded per-agent and carry high weight in the
model's decision-making. They reinforce the startup instructions with
agent-specific context.

**Implementation:** `CopilotClient.buildDefaultAgentDefinition()` and variants

### Layer 4: Reprimand nudges

**What:** When the agent uses a native built-in tool despite the instructions,
the plugin detects it in real-time and injects a corrective nudge into the next
MCP tool result: `[User nudge]: You used native bash tools. Use Agentbridge MCP
tools instead — native tools bypass the plugin's identity hooks, follow-agent
visibility, and IDE buffer sync.`

**Why:** Instructions are not always followed — models sometimes fall back to
familiar patterns, especially under complex multi-step reasoning. The nudge
provides immediate, contextual feedback that steers the agent back on track.

**Configuration:** Settings → AgentBridge → Agent Settings → "Reprimand nudge
mode": Disabled / Send silently / Enabled (with cancel bubble).

**Implementation:** `CopilotClient.buildReprimand()`, `AgentNudgeService`,
`PsiBridgeService` (injection point)

### Layer 5: --excluded-tools CLI flag

**What:** The plugin passes `--excluded-tools view,edit,create,bash,grep,glob,...`
when launching the Copilot CLI.

**Why:** This is the intended upstream mechanism for hiding built-in tools.

**Status:** Currently broken in Copilot CLI's ACP mode (upstream bug #556).
When fixed, this will be the most reliable layer since it prevents the tools
from appearing in the agent's tool list entirely.

**Implementation:** `CopilotClient.buildCommand()`

---

## How the layers work together

```
Agent CLI startup
  │
  ├─ Layer 1: PATH sanitized → CLI can't detect git/gh/curl on system
  ├─ Layer 5: --excluded-tools → built-in tools hidden (when bug #556 is fixed)
  │
  ▼
Agent session starts
  │
  ├─ Layer 2: Startup instructions → "do NOT use bash/grep/view/edit"
  ├─ Layer 3: Agent definition → "you do NOT have direct access to git/curl/gh"
  │
  ▼
Agent makes tool calls
  │
  ├─ Uses MCP tool → ✓ hooks fire, follow-agent works, buffers sync
  └─ Uses native tool → Layer 4: reprimand nudge injected into next MCP result
```

No single layer is sufficient on its own. Each addresses a different failure mode:

| Failure mode                                  | Addressed by        |
|-----------------------------------------------|---------------------|
| CLI detects system tools and advertises them   | Layer 1 (PATH)      |
| CLI exposes built-in tools (bash, grep, etc.)  | Layer 5 (--excluded) |
| Agent ignores tool list and uses what it knows | Layers 2+3 (instructions) |
| Agent ignores instructions under pressure      | Layer 4 (nudges)    |

---

## Adding guardrails for a new agent

When adding support for a new agent CLI:

1. **PATH stripping:** Override `shouldStripNonEssentialPath()` in the client
   class (or set `stripNonEssentialPath = true` in the `AgentProfile`).
2. **Startup instructions:** Ensure the agent profile sets `prependInstructionsTo`
   or uses `session/message` for instruction injection.
3. **Agent definitions:** Write agent definition files that include the
   "no direct CLI access" framing.
4. **Reprimand nudges:** Ensure `processUpdate()` detects native tool usage
   and calls `AgentNudgeService.addNudge()`.
5. **--excluded-tools:** Add the flag to `buildCommand()` if the CLI supports it.
