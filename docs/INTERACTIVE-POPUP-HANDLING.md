# Interactive Popup Handling

**Status:** implemented on `feat/interactive-popup-handling` after `fix/edt-freeze-on-popup-quickfix` and `fix/popup-interception-in-apply-action` landed in `master`.

## Why this feature exists

Some IntelliJ intentions and quick-fixes do not complete synchronously. Instead they open a heavyweight `JBPopup` chooser on the EDT and wait for a user selection. A concrete case was `apply_action("Import class 'Cell'")`, which opened the ambiguous-import popup and froze the IDE until the process was killed.

PR #363 fixed the immediate freeze by cancelling such popups and returning a structured error. This feature adds the second half: the agent can now inspect the popup choices and explicitly continue the action through a follow-up MCP tool call.

## The core constraint

**We cannot keep the popup open between MCP tool calls.**

`JBPopup` drives a nested AWT event loop on the EDT. If we left it open while waiting for the agent's next tool call:

- other MCP calls would pile up behind EDT timeouts,
- unrelated IDE work would stall,
- we would reintroduce the original freeze under a different name.

That constraint drives the whole design.

## High-level design

The implementation uses **snapshot + cancel + replay**.

1. `apply_action` / `apply_quickfix` run under `PopupInterceptor`.
2. If no popup opens, they behave normally.
3. If a popup opens, the interceptor snapshots its content, cancels it immediately, and the tool returns a success message with a `popup_id` and the choice list.
4. The agent calls `popup_respond` with `action="select"` or `action="cancel"`.
5. For `select`, the original tool is re-run with a `PopupHandler.SelectByValue` that drives the popup to the chosen item.

This means the original action runs **twice** in popup cases: once to discover the popup, once to complete it.

## User-visible tool flow

### Initial tool call

Example:

```text
apply_action(file=..., line=..., action_name="Import class 'Cell'")
```

If a popup opens, the tool returns success text describing:

- the popup title,
- a generated `popup_id`,
- the available choices,
- how to call `popup_respond`.

### Follow-up tool call

```text
popup_respond(popup_id="...", action="select", value_id="...")
```

or

```text
popup_respond(popup_id="...", action="cancel")
```

## Blocking model while a popup is pending

While a popup is pending, **every other tool call is blocked** except `popup_respond`.

This is intentional:

- the popup originated from a live IDE action and still owns the next meaningful step,
- allowing unrelated tools would let the agent mutate state between snapshot and replay,
- even read-only IDE work is not useful enough to justify the extra complexity and race surface.

Auto-cancel rules:

- pending popup expires after **5 minutes**, or
- after **5 unrelated tool calls from the same MCP session**.

Calls from a different MCP session are blocked too, but they do **not** consume the owning session's auto-cancel budget.

## Main components

| File | Responsibility |
| --- | --- |
| `PopupHandler.java` | Sealed strategy passed into popup-aware tool execution. Variants: `Cancel`, `Snapshot`, `SelectByValue`. |
| `PopupInterceptor.java` | Detects newly opened `JBPopup`s, snapshots or cancels them, or schedules replay selection. |
| `PopupContentExtractor.java` | Converts a live popup into a `PopupSnapshot`. Prefers `ListPopupStep`, falls back to raw `JList`/`JTree`. |
| `PopupChoice.java` | One popup row with stable `valueId`, display text, and metadata like `hasSubstep`. |
| `PopupSnapshot.java` | Title + popup kind + immutable choice list + `contentDigest()` for loop detection. |
| `ContextFingerprint.java` | Captures action identity + file path + document modification stamp for replay validation. |
| `PendingPopupService.java` | App-level single-slot store for the currently pending popup. Holds ownership, timeout, and replay metadata. |
| `PopupGateLogic.java` | Pure decision function used by `McpProtocolHandler` to allow, block, or auto-cancel pending-popup state. |
| `PopupRespondTool.java` | New MCP tool that resolves the pending popup by selecting a captured choice or cancelling it. |
| `Replayable.java` | Bridge interface implemented by replay-capable tools (`ApplyActionTool`, `ApplyQuickfixTool`). |
| `McpCallContext.java` | Thread-local carrying the current MCP session key from protocol layer into tool execution. |
| `QualityToolFactory.java` | Registers `popup_respond` and wires replayable tools into it. |

## Why the design looks the way it does

### 1. Explicit handler object instead of hidden global mode

`PopupHandler` is threaded explicitly through popup-aware tool paths. We did **not** use a thread-local for the handler mode because the popup listener fires on the EDT in a different stack from the caller. Capturing the handler in the listener closure is simpler and easier to reason about.

The only thread-local involved here is `McpCallContext`, and it carries session ownership information, not popup behavior.

### 2. `ListPopupStep` is the source of truth

The preferred popup representation comes from `ListPopupImpl` + `ListPopupStep`, not from Swing row scraping.

Why:

- list renderers are presentation, not identity,
- rows can include disabled values or separators,
- replay needs to select the *same semantic value*, not just the same visual row.

Fallback extraction from `JList` / `JTree` exists only so we can still describe less structured popups instead of failing silently.

### 3. Stable `valueId` instead of raw index

Choices are identified by `PopupChoice.valueId()`, derived from popup text plus index. Raw indices alone are too fragile if the popup reorders slightly on replay.

Replay first tries to find the matching `valueId`; it only falls back to `fallbackIndex` when the row text still matches.

### 4. Single-slot pending store

`PendingPopupService` intentionally stores **at most one** pending popup.

Reasons:

- the IDE effectively has one meaningful pending chooser at a time for this workflow,
- the gating model blocks all other tool calls anyway,
- one-slot state keeps the agent contract simple and reduces failure modes.

### 5. Replay validation via document modification stamp

`ContextFingerprint` prevents replaying a stale popup into a changed file.

If the file changed after the snapshot was captured, `popup_respond` rejects the replay and tells the agent to re-run the original tool. This is safer than trying to guess whether the change was compatible.

### 6. Different suspend behavior in `ApplyActionTool` vs `ApplyQuickfixTool`

This asymmetry is intentional.

- `ApplyActionTool` tries harder to recover if the document changed before the popup opened. It attempts an undo-and-recheck path before deciding whether it can safely suspend.
- `ApplyQuickfixTool` is stricter: if the document modification stamp changed before suspension, it refuses to suspend and falls back to the PR #363 error path.

The simpler quick-fix path is deliberate: popup-before-mutation is common enough there that safety matters more than squeezing out a few extra suspendable cases.

### 7. Pure gate logic

`PopupGateLogic.evaluate(...)` is pure by design. It does not mutate service state; it only returns one of:

- allow,
- allow-with-auto-cancel note,
- block.

That separation makes the gating contract testable without bringing up an IntelliJ application.

## Actual control flow in code

### Snapshot path

1. Tool enters popup-aware execution (`ApplyActionTool` or `ApplyQuickfixTool`).
2. Tool calls `PopupInterceptor.runDetectingPopups(..., new PopupHandler.Snapshot(...), ...)`.
3. Interceptor detects newly opened popup(s) relative to a baseline.
4. `PopupContentExtractor` builds a `PopupSnapshot`.
5. Interceptor cancels the popup.
6. Tool validates the action is still suspendable.
7. Tool registers a `PendingPopupService.Pending` record.
8. Tool returns a success message instructing the agent to call `popup_respond`.

### Replay path

1. Agent calls `popup_respond`.
2. Tool validates `popup_id`, action, and selected choice.
3. Tool validates the current `ContextFingerprint` against the captured one.
4. Tool takes the pending slot.
5. Tool re-runs the original action through `Replayable.replay(...)` using `PopupHandler.SelectByValue`.
6. `PopupInterceptor` waits for the popup to reopen and schedules `ListPopupImpl.selectAndExecuteValue(...)` on the EDT.
7. If replay succeeds, normal tool output is returned.

## Safety rules and invariants

These are load-bearing and should not be removed casually.

### Popup must always be dismissed before returning

This is the core freeze-prevention invariant. Any future change that tries to hold a popup open across tool calls must first solve the nested-EDT-loop problem.

### Pending popup blocks all unrelated tools

Without this, the agent could modify files between snapshot and replay and invalidate the captured context in harder-to-debug ways.

### `ContextFingerprint.documentModStamp` is not optional

It is the key guard against stale replay into a mutated document.

### Cross-session ownership matters

The session ownership branch in `PopupGateLogic` prevents one MCP session from accidentally consuming another session's auto-cancel budget.

### `PopupSnapshot.contentDigest()` is for loop detection

If replay opens the *same* chooser again, we fail fast instead of endlessly letting the agent retry.

## Current limitations

These are intentional v1 boundaries, not accidental gaps.

### Chained popups are not supported

If a choice has `hasSubstep=true`, `popup_respond` rejects it. We do not yet support recursively snapshotting and replaying popup trees.

### Only popup-capable tools are replayable

Right now the replay contract is implemented for:

- `apply_action`
- `apply_quickfix`

Other tools would need explicit `Replayable` support and their own suspend-safety analysis.

### Looping popups fail closed

If replay opens the same popup again, we return an error and tell the agent to fall back to a manual path such as `edit_text`.

### Non-list popups are best-effort only

When the popup is not a `ListPopupImpl`, extraction falls back to visible Swing content. That is useful for diagnostics, but less reliable than the structured `ListPopupStep` path.

### No persistent multi-step transaction across IDE restarts

Pending popup state is in-memory only. If the IDE restarts or the tool session disappears, the popup is gone and the agent must restart from the original tool call.

## Testing strategy

The feature is mostly covered by pure unit tests around the logic that *can* be isolated:

- `PopupChoiceTest`
- `PopupSnapshotTest`
- `ContextFingerprintTest`
- `PopupGateLogicTest`
- updated `PopupInterceptorTest`

These validate stable IDs, digest behavior, mismatch reporting, and the gate rules.

What is **not** fully unit-tested:

- end-to-end replay against a live `ListPopupImpl`,
- real IDE popup opening behavior,
- service behavior that depends on a live IntelliJ application instance.

Those parts are validated by integration/manual testing.

## Future work

Likely follow-ups if this area needs to grow:

1. support chained popup flows (`hasSubstep=true`),
2. add broader replay support to more tools beyond quality actions,
3. add dedicated integration tests around live `ListPopupImpl` behavior,
4. improve extraction for more popup types if we encounter them in practice,
5. consider richer agent messaging for replay failures (for example, explicit retry hints by failure type).

## Debugging tips for future maintainers

If popup handling appears broken, check these in order:

1. **Was the popup actually a `JBPopup`?** If not, this subsystem may not apply.
2. **Was it a `ListPopupImpl`?** If not, extraction is fallback-only.
3. **Did the document modification stamp change before replay?** That will intentionally block resume.
4. **Did the same popup reopen?** Loop detection may be doing its job.
5. **Was another MCP session involved?** Cross-session blocking is expected.
6. **Did the pending popup age out or consume its 5-call budget?** Then `popup_respond` will no longer find it.

## Related documents

- `.agent-work/freeze-investigation-2026-04-30.md` - original freeze investigation and root cause
- `.agent-work/popup-interaction-design-2026-04-30.md` - deeper implementation rationale captured during development
- `docs/ACP-TOOL-INTERCEPTION.md` - broader context on tool execution limitations in the Copilot CLI / ACP stack
