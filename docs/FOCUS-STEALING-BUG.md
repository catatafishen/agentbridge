# Focus-Stealing Bug — Issue #275

**Issue**: https://github.com/catatafishen/agentbridge/issues/275  
**Status**: Substantially fixed as of PR #276 + PR #280 (see remaining edge cases below)  
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
if (req.chatWasActive()) fireFocusRestoreEvent();  // line 450
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
if (PsiBridgeService.isChatToolWindowActive(project)) {
    tw.show();    // no focus steal
} else {
    tw.activate(null);  // steals focus
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
new OpenFileDescriptor(project, vf, midLine - 1, 0).navigate(focus);
```

Note: this check IS inside `invokeLater` (the EDT operation), so the value is fresh. ✓
When `focus=true` (chat not active), `navigate(true)` opens the file AND steals focus.

### 5. `selectInProjectView` (FileTool.java:283)

```java
if (PsiBridgeService.isChatToolWindowActive(project)) return;
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

---

## Remaining Root Causes

### RC4: `restoreFocusIfNeeded` in ProjectBuildSupport uses `invokeLater` unconditionally

**File**: ProjectBuildSupport.java:92
```java
EdtUtil.invokeLater(() -> {
    fem.openFile(previousEditor.getFile(), true);  // focus=true
});
```

This fires after build completes and opens the previous file with focus. But if user has
already navigated somewhere else, this is an unwanted steal.

**Fix**: Check if the user is still in the same context before restoring — or don't restore
at all (let user manage their own focus).

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

| File | Line | Status |
|------|------|--------|
| `PsiBridgeService.java` | 321 | ✅ `chatWasActive` captured at start, completion re-checks |
| `PsiBridgeService.java` | 469 | ✅ Focus restore requires both start+end active |
| `ChatToolWindowContent.kt` | 165 | ✅ 150ms alarm checks chat-active before firing |
| `FocusGuard.java` | all | ✅ Synchronous reclaim with `hasReclaimed` one-shot guard |
| `FileTool.java` | 257 | ✅ `navigate(focus)` check is inside `invokeLater` |
| `FileTool.java` | 286 | ✅ `selectInProjectView` skips if chat active; only scrolls if already open |
| `GitTool.java` | 400 | ✅ VCS `show()`/`activate()` check is inside `invokeLater` |
| `BuildProjectTool.java` | 82 | ✅ Build window check is inside `invokeLater` |
| `Tool.java` | 204 | ✅ Run panel `activateToolWindow` check is inside `invokeLater` |
| `SearchTextTool.java` | 175 | ✅ Find tool window skipped when chat active |
| `PlatformApiCompat.java` | ~475 | ✅ VCS log `showRevisionInMainLog` guarded by chat check |
| `ProjectBuildSupport.java` | 87 | ⚠️ `restoreFocusIfNeeded` unconditional invokeLater (RC4) |

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
