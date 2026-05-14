# Pause / Resume Agent Execution

The **Pause** feature lets you temporarily hold an agent mid-run so you can review what it's
doing, compose a nudge, and decide whether to let it continue — without the bluntness of a
full Stop.

---

## Why pause instead of stop?

Stopping an agent discards the current turn. If it turns out the agent was actually behaving
correctly, or only needed a small nudge in the right direction, stopping was too aggressive.

Pausing keeps the current turn alive. The next tool call is held at the door while you decide
what to do. You can:

- Read the tool arguments before they execute
- Type a follow-up nudge and send it (this automatically resumes the agent)
- Click Resume to let the blocked call proceed unchanged
- Or click Stop if you do want to abort the run entirely

---

## How it works

Pause intercepts the **next incoming MCP tool call** from the agent and holds it before:

1. Permission hook evaluation (deny/allow rules)
2. Argument mutation (pre-hooks)
3. Actual tool execution

This means the agent sees no error — it receives the tool result later than usual, as if the
call took a long time. From the agent's perspective, the pause is invisible.

---

## Button states

The Pause/Resume button appears in the chat toolbar while the agent is running (when the feature
is enabled in settings). It is hidden when the agent is idle — only the Stop/Disconnect button
remains visible in that case.

| State        | Icon       | Visible | Meaning                                                    |
|--------------|------------|---------|------------------------------------------------------------|
| Agent idle   | —          | Hidden  | Nothing to pause; button not shown                         |
| **Pause**    | ⏸ enabled  | Yes     | Agent is running — click to pause                          |
| **Pausing…** | ⏸ disabled | Yes     | Pause requested; waiting for the agent to make a tool call |
| **Resume**   | ▶ enabled  | Yes     | A tool call is blocked — click to let it proceed           |

The **Pausing…** state exists because there is always a gap between clicking Pause and the
agent's next tool call arriving. The button is disabled during this window to make the pending
state visible.

---

## Auto-resume on send

Sending a nudge or a new message automatically resumes the agent. This covers the most common
use case: you clicked Pause to think, typed something, and hit Send — the pause lifts
immediately so the nudge is included in the current turn.

---

## Auto-pause on input focus (optional)

When **Auto-pause when chat input is focused** is enabled in settings, the agent is
automatically paused whenever you click into the chat input field, and automatically resumed
when focus leaves the input — unless you have manually paused the agent yourself.

This is useful if you frequently type nudges mid-run: the agent pauses the moment you start
typing and resumes when you send or move focus away.

---

## Settings

Open **AgentBridge › UI/UX** in the IDE settings:

| Setting                                   | Default | Description                                        |
|-------------------------------------------|---------|----------------------------------------------------|
| **Enable pause/resume button**            | On      | Shows the Pause button in the toolbar              |
| **Auto-pause when chat input is focused** | Off     | Pauses automatically when you click the chat input |

The auto-pause setting is only available when the pause feature is enabled.

---

## Implementation notes

- **`McpPauseService`** — project-level service; uses `ReentrantLock` + `Condition` for
  thread-safe blocking. Three states: `RUNNING`, `PENDING`, `PAUSED`.
- **Blocking point** — `McpPauseService.awaitResumeIfPaused()` is called on the MCP worker
  thread in `McpProtocolHandler`, before permission hooks and tool execution.
- **No error to the agent** — the agent's transport simply waits for a JSON response that
  arrives later. No timeout is imposed by the pause mechanism itself.
- **Auto-resume** — `setPaused(false)` is called before `promptOrchestrator.execute()` in
  both `onSendStopClicked` and `sendPromptDirectly`, covering user-typed sends and auto-nudges.
