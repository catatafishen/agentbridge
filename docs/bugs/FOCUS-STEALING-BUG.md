# Focus-Stealing Bug — Issue #275

> **See also**: [`COMMIT-NOT-FOUND-IN-LOG-BUG.md`](COMMIT-NOT-FOUND-IN-LOG-BUG.md) — the
> follow-along VCS Log navigation interacts with the focus guards described here.
> Any change that tightens or relaxes the `isChatToolWindowActive(project)` guard
> in the `git_commit` / `git_log` / `git_show` follow-along path can regress one of
> these two bugs in opposite directions. Read both docs before editing the shared
> wiring.

**Issue**: https://github.com/catatafishen/agentbridge/issues/275  
**Status**: Under investigation — mitigations in PR #276, PR #280, Attempt 11 (VetoableChangeListener FocusGuard), not
confirmed fixed  
**Scope**: Broader than the title — affects editor focus, terminal tabs, run/search tool windows

---

## Problem Description

When the agent executes tools, the IDE focus unexpectedly jumps away from wherever the user is
working:

1. **Cursor focus jumps to the edited file** — while user is typing elsewhere, a file edit
   causes the editor to grab focus.
2. **Terminal/search/run panel gets closed or switched** — switching to the Run or Search tool
   window causes the user's terminal to close, or their search query to be lost.
3. **Focus returns to chat input uninvited** — after tools complete, focus snaps back to the
   chat input even if the user has already navigated elsewhere.
4. **Project View gets stolen** — the project tree expands and scrolls to the file being edited,
   pulling the view away from wherever the user was.
5. **Git Log / Find tool window opened while typing in chat** — `git_log` shows a commit in the
   VCS Log, and `search_text` opens the Find tool window, both stealing focus from the chat prompt.

The issue only mentions editors, but the same mechanism affects: Run tool window, VCS tool window,
Build tool window, Find tool window, and the chat input focus-restore.

---

## Architecture: How Focus Is Managed

### 1. `chatWasActive` flag (PsiBridgeService.java:321)

```java
boolean chatWasActive = isChatToolWindowActive(project);
// ...
if(req.

chatWasActive())

fireFocusRestoreEvent();  // line 450
```

Captured at tool call **start**. After the tool finishes, if `chatWasActive=true`, fires a
focus-restore event that returns focus to the chat input.

**Problem**: Tools can take seconds or minutes. If user switches focus during execution,
`chatWasActive` is stale and still `true`, so focus is wrongly stolen back after completion.

### 2. `isChatToolWindowActive` check in tools (at time of UI operation)

Used by: FileTool, GitCommitTool, GitTool, BuildProjectTool, Tool, CreateScratchFileTool,
OpenInEditorTool, SearchTextTool, PlatformApiCompat.

Pattern:

```java
if(PsiBridgeService.isChatToolWindowActive(project)){
        tw.

show();    // no focus steal
}else{
        tw.

activate(null);  // steals focus
}
```

**Problem 1**: The check happens at tool call start (non-EDT), but the actual UI operation
happens in `invokeLater` on the EDT — potentially much later. The chat state may have changed.

**Problem 2**: `isChatToolWindowActive` has a 100ms timeout for EDT refresh. If EDT is busy,
it returns a cached (stale) value.

### 3. `FocusRestoreListener` / 150ms alarm (ChatToolWindowContent.kt:165)

After tool completion, fires `promptTextArea.requestFocusInWindow()` with 150ms delay.

**Why 150ms**: To fire after secondary focus events from `navigate()` calls or `tw.show()`.

**Problem**: If the user typed into another field between tool completion and the 150ms delay,
focus is stolen mid-keystroke.

### 4. `FileTool.followFileIfEnabled` (FileTool.java:257)

```java
boolean focus = !PsiBridgeService.isChatToolWindowActive(project);
new

OpenFileDescriptor(project, vf, midLine -1, 0).

navigate(focus);
```

Note: this check IS inside `invokeLater` (the EDT operation), so the value is fresh. ✓
When `focus=true` (chat not active), `navigate(true)` opens the file AND steals focus.

### 5. `selectInProjectView` (FileTool.java:283)

```java
if(PsiBridgeService.isChatToolWindowActive(project))return;
// ... scroll Project tree (only if already visible)
```

If chat is NOT active, scrolls the Project tree to the current file — but only if the Project
window is already visible. Does not force the window open.

### 6. `ProjectBuildSupport.restoreFocusIfNeeded` (ProjectBuildSupport.java:87)

After build completes, if "Follow Agent Files" is **disabled**, re-opens the previously selected
editor file with `focus=true`. This is well-intentioned (restore pre-build focus) but fires
`invokeLater` which may arrive after user has already navigated.

### 7. `SearchTextTool.showResultsInUsageView` (SearchTextTool.java)

When `search_text` runs, it collects match positions and calls `UsageViewManager.showUsages()`
in an `invokeLater` — which opens the **Find** tool window and activates it. This `invokeLater`
fires **after** the tool call returns and the `FocusGuard` is already uninstalled.

### 8. `PlatformApiCompat.showRevisionInLogAfterRefresh` (PlatformApiCompat.java)

After `git_log` / `git_commit`, shows the commit in the VCS Log via a `DataPackChangeListener`
that fires asynchronously after VCS refresh. By the time it fires, the `FocusGuard` is gone.
Internally calls `VcsProjectLog.showRevisionInMainLog()` which activates the VCS Log pane.

### 9. `FocusGuard` — VetoableChangeListener (FocusGuard.java)

The last line of defense during tool execution. Uses Java's `VetoableChangeListener` on
`KeyboardFocusManager.focusOwner` to **prevent** programmatic focus changes from happening at all.

**How it works**:

1. Installed on EDT at tool call start (if chat is focused)
2. When any code tries to move focus to a component **outside** the chat tool window:
    - If target is in the same Window as chat (editor, tool window) → **vetoed** (focus stays in chat)
    - If target is in a different Window (dialog, popup, lookup) → **allowed** (legitimate IDE UI)
    - If triggered by user input (mouse click, keystroke) → **allowed** (user-initiated)
3. Uninstalled on EDT after tool completes (synchronous via CountDownLatch)

**Why preventive > reactive**: The previous `PropertyChangeListener` approach reclaimed focus
*after* it moved. This required `requestFocusInWindow()` which could mis-route in JCEF contexts,
requiring a `hasReclaimed` one-shot flag that limited protection to a single steal per tool call.
The `VetoableChangeListener` approach prevents the focus change entirely — no reclaim needed,
no JCEF mis-routing, no one-shot limitation. Every programmatic focus steal is vetoed.

---

## What Has Been Tried

### Attempt 1: `show()` vs `activate()` split (commit `92690bc3`, tag v1.56.1, 2026-04-15)

**Commit**: `fix: preserve chat prompt focus during agent tool execution`  
**What**:

- Made `isChatToolWindowActive()` synchronous on EDT (no more stale async cache)
- Added `tw.show()` vs `tw.activate()` guards to FileTool, BuildProjectTool, GitCommitTool, GitTool
- `selectInProjectView`: skip when chat is active
- `Tool.java` Run panel: added `activateRunPanel = !isChatToolWindowActive()` — but captured
  **BEFORE** `invokeLater`, so the check is stale by the time the UI operation runs (the bug
  this commit introduced that is now fixed in RC2)

**Result**: Significantly improved. Tool windows no longer steal focus when chat is in foreground.
**Still broken**:

- `activateRunPanel` in Tool.java is stale (RC2 — now fixed in this branch)
- `chatWasActive` for focus restore is captured at call start, not at completion (RC1)
- If user switches focus during long-running tool, focus is stolen back on completion (RC1)

### Attempt 2: Focus restore via message bus + 150ms alarm (commit `92690bc3`)

**What**: After tool call, fire `FOCUS_RESTORE_TOPIC`. `ChatToolWindowContent` uses an alarm
with 150ms delay to return focus to the chat input AFTER any secondary window operations.

**Why 150ms**: `navigate()` and `tw.show()` themselves use `invokeLater`, so without the delay,
the focus restore fires BEFORE those operations and is immediately overridden.

**Result**: Focus correctly returns to chat after tool calls.
**Still broken**: If user switches focus during tool execution, the 150ms alarm still fires and
steals focus. Also, 150ms is fragile — depends on timing of EDT queue drain.

### Attempt 3: `isChatToolWindowActive` caching with EDT refresh (current code)

**What**: Added EDT-thread-aware caching to `isChatToolWindowActive` with 100ms EDT timeout.
Non-EDT callers post a `CountDownLatch` lambda to EDT to get a fresh value.

**Result**: Reduced stale values.
**Still broken**: Between the check and the `invokeLater` for the actual UI operation, the
user may change focus. The window of stale-check → UI-op is unbounded.

### Attempt 4: RC1 + RC2 guards (commit `79029ae4`, PR #276, 2026-04-17)

**What**:

- RC1: gate `fireFocusRestoreEvent()` on `chatWasActive && isChatToolWindowActive(project)` so
  we never steal focus back when the user explicitly switched away during tool execution.
- RC2: inline `activateRunPanel` capture INSIDE the `invokeLater` lambda in `Tool.java`, so the
  `isChatToolWindowActive` check is fresh at the moment the Run panel is shown.

**Result**: Focus no longer snaps back to chat after user manually leaves chat during tool call.
**Still broken** (user report on 2026-04-17): When typing in the chat prompt while a tool fires,
a few characters still land in the editor before the 150 ms alarm restores focus. The symptom
proves that `openFile(vf, false)` / `navigate(false)` / tab-creation side effects move Swing
focus even when `focusEditor=false` is requested — because JCEF (where the chat lives) holds
keyboard focus invisibly to the Java KeyboardFocusManager, so Swing treats the newly created
editor as a valid focus target.

### Attempt 5: synchronous `FocusGuard` during tool execution (commit `09c50808`, PR #276)

**What**: Install a `PropertyChangeListener` on `KeyboardFocusManager.focusOwner` at the start
of every tool call where chat is focused. When focus moves to any component outside the chat
tool window, the listener synchronously calls `requestFocusInWindow()` on the original chat
focus owner — *before* any KeyEvent is dispatched to the new component. User-initiated focus
changes (mouse clicks, tab key) are detected via `EventQueue.getCurrentEvent()` being an
`InputEvent` and are allowed through unchanged.

**Why it worked** (in-execution steals):

- Property-change dispatch happens on the EDT synchronously with the focus transfer. If we
  reclaim focus inside the listener, the focus owner is corrected before the EDT yields to
  process queued `KeyEvent`s.
- Because we only reclaim on non-`InputEvent`-triggered changes, the user retains full control
  to click into an editor or terminal during long-running tools.
- The guard is installed in `PsiBridgeService.callTool` and removed in the `finally` block, so
  it matches tool-execution lifetime exactly.

**Critical regression introduced**: JCEF chat panel completely frozen.

**Root cause of freeze**: `requestFocusInWindow()` on the JCEF OSR component (`JBCefOsrComponent`
extends `JPanel`) does not necessarily route focus back to `chatFocusOwner` itself — JCEF's
internal focus delegation may land on a sibling or parent component. That intermediate component
is:

1. Not `chatFocusOwner` → passes the `newComp == chatFocusOwner` guard
2. Not inside the chat tool window → passes `isInsideChatToolWindow` guard (OSR components can
   fail the `SwingUtilities.isDescendingFrom` check depending on JCEF's window mode)
3. Not triggered by a user `InputEvent` → passes the input-event guard

Result: listener fires again → another `requestFocusInWindow()` → another focus change event →
listener fires again → **infinite focus ping-pong storm**. This saturates the EDT with focus
events, starving JCEF's mouse-event forwarding and rendering the chat panel completely
unresponsive (cannot scroll, select text, click, or type).

**Fixed in Attempt 6.**

**Files**: `plugin-core/.../psi/FocusGuard.java` (new), `PsiBridgeService.java:callTool`
(install/uninstall).

### Attempt 6: `hasReclaimed` — limit guard to one reclaim per tool call (commit `7842de29`, PR #276)

**What**: Add `private final AtomicBoolean hasReclaimed` to `FocusGuard`. In `propertyChange()`,
call `if (!hasReclaimed.compareAndSet(false, true)) return;` before `requestFocusInWindow()`.
This ensures the guard fires at most once per tool call lifetime.

**Why this breaks the storm loop**:

- The first focus steal is reclaimed synchronously — preventing any in-flight keystrokes.
- If the reclaim routes focus to an intermediate component, the second invocation of
  `propertyChange` hits `hasReclaimed=true` and returns immediately.
- No more ping-pong. The existing 150ms alarm handles any remaining post-tool focus clean-up.

**Result**: JCEF panel no longer freezes. First keystroke protection preserved.

**Files**: `FocusGuard.java` (`hasReclaimed` field + `compareAndSet` guard).

**Regression tests added**: `FocusGuardTest` (4 tests) in `plugin-core/src/test/.../psi/`.

### Attempt 7: 150ms alarm guard — don't steal focus from user who clicked away (PR #276)

**What**: Add `isChatToolWindowActive(project)` check inside the 150ms alarm callback in
`subscribeToFocusRestoreEvents()`. Previously the alarm fired unconditionally; now it checks
whether chat still holds focus before calling `promptTextArea.requestFocusInWindow()`.

**Why needed alongside FocusGuard**: FocusGuard handles steals *during* tool execution
synchronously (hasReclaimed ensures exactly one reclaim). The alarm covers a distinct window:
queued `invokeLater` tasks from the tool that run *after* the guard is removed (between
`uninstall()` and T+150ms). Without the check, the alarm would steal focus from users who
clicked elsewhere in those 150ms.

**Files**: `ChatToolWindowContent.kt` (`subscribeToFocusRestoreEvents` alarm callback).

### Attempt 8: Guard post-tool async UI operations (PR #280, 2026-04-19)

**What**: Three targeted guards for UI operations that fire asynchronously *after* the
`FocusGuard` has been uninstalled:

**Fix A — `SearchTextTool.showResultsInUsageView`**: Added `isChatToolWindowActive` check
inside the `invokeLater` before `UsageViewManager.showUsages()`. When chat is active, skip
opening the Find tool window entirely — results are visible in the chat response.

**Fix B — `PlatformApiCompat.showRevisionInLogAfterRefresh`**: Added `isChatToolWindowActive`
check inside the `DataPackChangeListener` callback's `invokeLater` before calling
`VcsProjectLog.showRevisionInMainLog()`. Prevents VCS Log pane from activating/stealing focus
after git operations when the user is typing in chat.

**Fix C — `FileTool.selectInProjectView`**: Removed the `tw.show()` call that opened the
Project tool window when not already visible. Now the method only scrolls the tree if the
Project window is *already open*. Prevents the Project panel from being forced visible when
the user is focused on a terminal or other panel.

**Result**: Git log, Find tool window, and Project Explorer no longer force themselves visible
or steal focus when the user is in the chat prompt.

### Attempt 9: Guard ACP-side "follow agent files" in FileNavigator (current branch)

**What**: `PromptOrchestrator.handleStreamingToolCall()` (line 623) triggers
`FileNavigator(project).handleFileLink()` via `invokeLater` for **every** tool call with file
paths — including sub-agent internal tool calls that are NOT our MCP tools (built-in Copilot CLI
tools like `view`, `read`). Previously, `handleFileLink` always called `navigate(true)`,
unconditionally stealing focus.

**Root cause**: Two independent file-opening paths exist:

1. **MCP path** (`PsiBridgeService.callTool()` → `FileTool.followFileIfEnabled()`): Protected by
   `FocusGuard` + `isChatToolWindowActive` check. ✓
2. **ACP path** (`PromptOrchestrator` → `FileNavigator.handleFileLink()`): Runs for ALL tool calls
   regardless of MCP correlation. Previously unprotected. ✗

For sub-agent non-MCP tool calls, only path 2 runs — no `FocusGuard` is installed, and
`handleFileLink` opens the file with `focus=true`, stealing keyboard focus.

**Fix**: In `FileNavigator.handleFileLink()`, check `PsiBridgeService.isChatToolWindowActive(project)`
inside the `invokeLater` lambda (fresh EDT value) and pass `focus = !chatActive` to `navigate()`.
When the user is in the chat prompt, files open without stealing focus.

**Why this also explains the dashed-border correlation**: Sub-agent internal tool calls to built-in
Copilot CLI tools (e.g. `view`) are never sent to our MCP server, so `ToolChipRegistry` never
matches them — they remain `PENDING` state with dashed borders. Yet files still opened because
the ACP-side path in `PromptOrchestrator` extracted file paths from the ACP event and called
`handleFileLink` regardless.

**Files**: `FileNavigator.kt` (`handleFileLink` method).

### Attempt 10: PlatformApiCompat race-condition path, ProjectBuildSupport guard, FocusGuard uninstall timing

**New incidents observed**:

- At **19:51**: Focus went to Build tool window, then to Git Log tool window during `git_commit`
- At **20:48-20:49**: Focus went into editor while user was typing in chat

**Root cause analysis** (comprehensive audit of all focus-changing paths):

1. **`PlatformApiCompat.showRevisionInLogAfterRefresh()`** — The method has two code paths that
   call `VcsProjectLog.showRevisionInMainLog()`: a `DataPackChangeListener` path and a
   race-condition early-return path for when the commit is already indexed. The listener path
   had an `isChatToolWindowActive` guard; the race-condition path at line 490-494 did **not**.
   When `git_commit` triggers this method and the commit is already indexed (common when
   IntelliJ auto-refreshes from a filesystem event), the unguarded path fires and activates
   the Git Log tool window with focus. **This was the 19:51 incident.**

2. **`ProjectBuildSupport.restoreFocusIfNeeded()`** — When `followAgentFiles=false`, this method
   calls `openFile(file, true)` via `invokeLater` without any `isChatToolWindowActive` check.
   The CompilerManager callback fires asynchronously after FocusGuard is uninstalled.
   **Contributing factor to the 19:51 build tool window focus.**

3. **`FocusGuard.uninstall()` timing bug** — When called from a background thread (the normal
   case from `callTool`'s finally block), `uninstall()` set `uninstalled = true` eagerly on the
   calling thread, then enqueued the actual listener removal on the EDT via `invokeLater`. But
   `followFileIfEnabled`'s `invokeLater(navigate)` was enqueued *before* the removal. EDT
   processes these FIFO, so `navigate` fires first — but `uninstalled = true` makes
   `propertyChange()` return early, leaving `navigate(false)` free to steal focus with no
   protection. The 150ms alarm then checks `isChatToolWindowActive`, but the editor already
   has focus, so it doesn't restore. **This was the 20:48-20:49 incident.**

**Fixes applied**:

1. **PlatformApiCompat line 492**: Added `isChatToolWindowActive` guard to the race-condition
   early-return path, matching the existing guard on the listener path.

2. **ProjectBuildSupport line 92**: Added `isChatToolWindowActive` guard inside the `invokeLater`,
   skipping the `openFile(file, true)` call when chat is focused.

3. **FocusGuard.uninstall()**: Changed from eager `uninstalled = true` + async removal to
   **synchronous EDT removal via CountDownLatch**. When called from a background thread, the
   removal is posted to EDT and the caller blocks (up to 200ms) until it completes. This ensures
   all `invokeLater` callbacks enqueued during tool execution (e.g. `followFileIfEnabled`'s
   `navigate(false)`) are processed while the FocusGuard is still active. The guard can catch
   and reclaim a transient focus steal from `navigate(false)` before the listener is removed.

**Files**: `PlatformApiCompat.java`, `ProjectBuildSupport.java`, `FocusGuard.java`.

**Known limitation**: FocusGuard's `hasReclaimed` flag limits reclaim to one per tool call. If
a focus steal during tool execution already consumed the reclaim, a later `navigate(false)` in
`followFileIfEnabled` won't be caught. This is unlikely for file tools (no EDT focus events
during background I/O) but possible for build tools. Fix #2 (ProjectBuildSupport guard) provides
defense-in-depth for that case. **Resolved in Attempt 11** by switching to VetoableChangeListener.

### Attempt 11: VetoableChangeListener — preventive focus protection (zero keystroke leakage)

**Problem**: The `PropertyChangeListener` approach in FocusGuard was *reactive* — it reclaimed
focus *after* it had already moved to the wrong component. While the reclaim happened synchronously
(before `KeyEvent` dispatch, so no keystroke leakage with a single steal), it had a fundamental
limitation: the `hasReclaimed` one-shot flag meant only ONE reclaim per tool call. If an earlier
focus steal consumed the flag, subsequent steals (e.g., from `followFileIfEnabled`'s deferred
`navigate(false)`) went unprotected, with only the 150ms alarm as a fallback.

**Root cause**: The reactive approach had an impedance mismatch with JCEF. When
`requestFocusInWindow()` reclaims focus for a JCEF component, the Swing focus system sometimes
routes focus to a parent or sibling component rather than the exact `chatFocusOwner`. This
triggers the guard again (new component ≠ `chatFocusOwner`, not inside chat TW), creating a
ping-pong focus storm that freezes the EDT. The `hasReclaimed` flag was a necessary mitigation
for this, but it created the one-shot limitation.

**Solution**: Switch FocusGuard from `PropertyChangeListener` (reactive reclaim) to
`VetoableChangeListener` (preventive veto). Instead of allowing focus to move and then reclaiming,
the guard now *prevents* the focus change from happening at all by throwing a
`PropertyVetoException`. This eliminates the ping-pong problem entirely — focus never moves,
so there's no reclaim to mis-route, and no `hasReclaimed` flag needed.

**Key design decisions**:

1. **Targeted veto, not blanket**: The guard only vetoes focus changes to components in the
   *same Window* as the chat tool window (i.e., the IDE main frame). Focus changes to dialog
   windows, popups, completion lookups, and other separate windows are allowed through. This
   prevents interference with IDE plumbing.

2. **User-initiated changes respected**: Same `InputEvent` check as before — mouse clicks and
   keystrokes that move focus are allowed through.

3. **Circuit breaker**: After 20 vetoes in a single guard lifecycle, the guard disables itself
   and logs a warning. Safety net against pathological scenarios where a component retries focus
   acquisition in a loop.

4. **No `hasReclaimed` needed**: Since focus never moves, there's no reclaim to mis-route, and
   no risk of ping-pong. Every programmatic focus steal is vetoed, providing complete protection
   for the entire tool execution duration.

**How `VetoableChangeListener` works in Java's focus system**:

- `KeyboardFocusManager.setGlobalFocusOwner()` fires `fireVetoableChange("focusOwner", ...)`
  *before* changing the property
- If any listener throws `PropertyVetoException`, the focus change is **cancelled** — the old
  focus owner is restored, and no `PropertyChangeEvent` is fired
- The exception is **not rethrown** to callers — `Component.requestFocusInWindow()` simply
  returns `false`
- This is an officially supported Java API mechanism, not a hack

**Files**: `FocusGuard.java` (rewritten from `PropertyChangeListener` to `VetoableChangeListener`),
`FocusGuardTest.java` (updated to test veto semantics, added circuit breaker and window-targeting tests).

### Attempt 12: Run panel — gate `setAutoFocusContent` (was always true)

**Problem**: User reported that running `run_command` while typing in the chat prompt still
stole focus into the new Run console tab — despite `Tool.java` setting
`withActivateToolWindow(!chatActive)` and being inside `invokeLater`.

**Root cause**: `RunContentExecutor` exposes **two** independent flags, both initialised to
`true` in the constructor:

| Builder method                 | Field                  | Effect                                                                                                                   |
|--------------------------------|------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `withActivateToolWindow(bool)` | `myActivateToolWindow` | `descriptor.setActivateToolWindowWhenAdded(...)` — controls whether the Run tool window is brought to front / activated. |
| `withFocusToolWindow(bool)`    | `myFocusToolWindow`    | `descriptor.setAutoFocusContent(...)` — controls whether the **content tab** auto-grabs keyboard focus when added.       |

Confirmed by decompiling `RunContentExecutor.class` from `idea-2025.3/lib/app.jar`:

```
49: iconst_1
50: putfield myActivateToolWindow:Z   // default true
54: iconst_1
55: putfield myFocusToolWindow:Z      // default true
...
85: getfield myActivateToolWindow → setActivateToolWindowWhenAdded
94: getfield myFocusToolWindow    → setAutoFocusContent
```

We only set `withActivateToolWindow(false)`, so `setAutoFocusContent(true)` was still being
called. Even when the Run tool window itself was not activated, adding the new content
descriptor with `autoFocusContent=true` made the new tab grab keyboard focus the moment it
was added to an already-visible Run window — bypassing the chat. FocusGuard could not catch
this because the content-add path uses internal `IdeFocusManager.requestFocusInProject` which
in some scenarios does not route through the property-veto chain.

**Fix**: Also set `withFocusToolWindow(!chatActive)` alongside `withActivateToolWindow`. Both
must be gated together — gating only one is insufficient.

**Files**: `Tool.java` (`executeInRunPanel`).

---

### Attempt 13: Audit other tools for the same family of focus footguns

After fixing the `RunContentExecutor` two-flag bug, audited all tool-window-opening paths in
`psi/tools/**` for analogous footguns. Found three more:

| File                                              | Symptom                                                                                                                                | Fix                                                                                                                                                          |
|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HttpRequestTool.java:329`                        | `RunContentExecutor` builder pinned `withActivateToolWindow(false)` but left `withFocusToolWindow` at default `true` — same as Run.    | Added `.withFocusToolWindow(false)` next to the existing pin.                                                                                                |
| `TerminalTool.java:113`                           | `TerminalToolWindowManager.createNewSession(basePath, title, shellCommand, true, true)` — 4th param is `requestFocus`, hard-coded `true`. | Switched to `boolean requestFocus = !PsiBridgeService.isChatToolWindowActive(project);`                                                                      |
| `GitStageTool.java:113`<br>`DatabaseTool.java:72` | Both unconditionally called `tw.activate(null)` on Local Changes / Database tool windows when "Follow Agent Files" was on.             | Wrapped in the same `chatActive ? tw.show() : tw.activate(null)` pattern already used by `GitTool`/`GitCommitTool`/`BuildProjectTool`.                       |

**Pattern to look for going forward**: any IntelliJ API that takes both an *activate / show*
flag **and** a *request focus / autoFocusContent* flag — they must be gated **together** on
`PsiBridgeService.isChatToolWindowActive(project)`. Examples already audited:

- `RunContentExecutor`: `withActivateToolWindow` + `withFocusToolWindow`
- `TerminalToolWindowManager.createNewSession`: `requestFocus` arg
- `ToolWindow.activate(runnable)` vs `ToolWindow.show()` — `activate` always grabs focus, `show` does not

**Files**: `HttpRequestTool.java`, `TerminalTool.java`, `GitStageTool.java`, `DatabaseTool.java`.

---

## Remaining Root Causes

_All previously documented root causes (RC1–RC4) have been fixed. See Attempts 1–10 below._

---

## Known Edge Cases

- **Permission prompt focus**: When tool permission is ASK, the chat input gets focus for the
  prompt. After permission granted, tool runs and then focus-restore fires again — double-steal.
- **Rapid tool calls**: With write batching, multiple tools run sequentially. Each fires the
  focus restore. With the 150ms alarm's `cancelAllRequests`, only the last one fires — good.
  But the 150ms from the *last* tool may fire long after the sequence started.
- **"Follow Agent Files" disabled**: `followFileIfEnabled` returns early without any navigation,
  so RC2 doesn't apply. But `selectInProjectView` still runs if chat is not active.

---

## Code Locations (Current State)

| File                       | Line | Status                                                                                                                                                       |
|----------------------------|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PsiBridgeService.java`    | 321  | ✅ `chatWasActive` captured at start, completion re-checks                                                                                                    |
| `PsiBridgeService.java`    | 469  | ✅ Focus restore requires both start+end active                                                                                                               |
| `ChatToolWindowContent.kt` | 165  | ✅ 150ms alarm checks chat-active before firing                                                                                                               |
| `FocusGuard.java`          | all  | ✅ `VetoableChangeListener` vetoes programmatic focus steals; targeted to same-Window only; circuit breaker at 20 vetoes; synchronous EDT uninstall via latch |
| `FileTool.java`            | 257  | ✅ `navigate(focus)` check is inside `invokeLater`                                                                                                            |
| `FileTool.java`            | 286  | ✅ `selectInProjectView` skips if chat active; only scrolls if already open                                                                                   |
| `GitTool.java`             | 400  | ✅ VCS `show()`/`activate()` check is inside `invokeLater`                                                                                                    |
| `BuildProjectTool.java`    | 82   | ✅ Build window check is inside `invokeLater`                                                                                                                 |
| `Tool.java`                | 209  | ✅ Run panel `activateToolWindow` AND `focusToolWindow` (Attempt 12) gated by chat-active check inside `invokeLater`                                          |
| `SearchTextTool.java`      | 175  | ✅ Find tool window skipped when chat active                                                                                                                  |
| `PlatformApiCompat.java`   | ~475 | ✅ VCS log `showRevisionInMainLog` guarded (both listener and race-condition paths)                                                                           |
| `FileNavigator.kt`         | 30   | ✅ `handleFileLink` passes `focus=!isChatToolWindowActive()`                                                                                                  |
| `ProjectBuildSupport.java` | 92   | ✅ `restoreFocusIfNeeded` skips when chat active                                                                                                              |

---

## Test Plan

Manual test steps to verify a fix:

1. Start a long-running tool (e.g., run_command with sleep)
2. While it runs, click into a Terminal tab and start typing
3. Tool completes — verify your terminal cursor position is preserved
4. Run `search_text` while chat is focused
5. Verify the Find tool window does NOT open
6. Run `git_log` while chat is focused
7. Verify the VCS Log pane does NOT activate/steal focus
8. Run any file navigation tool while chat is focused
9. Verify Project Explorer does NOT force itself open
10. Start a build and switch to terminal — verify terminal retains focus on build complete
11. Launch a sub-agent (e.g., `explore`) while typing in chat prompt
12. Verify sub-agent tool calls show dashed borders but do NOT steal focus
13. Verify files referenced by sub-agent tools open in background (visible but unfocused)
